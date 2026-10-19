package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.batch.BatchResult;
import io.hason.callanalysis.domain.batch.BatchSummary;
import io.hason.callanalysis.domain.batch.SourceGroup;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.service.port.BatchResultSink;
import io.hason.callanalysis.service.port.CallCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Phân tích hàng loạt (MVP 7.1 T10): "Phân tích hàng loạt các Call-ID trong ES local, thống kê phân bố verdict /
 * issue category".
 *
 * - Danh sách Call-ID lấy từ nguồn signaling ({@link CallCatalog}), không từ thư mục log.
 * - Mỗi cuộc đi qua ĐÚNG pipeline của Web UI ({@link AnalyzeCallService}: File Validator → timeline → rule → report),
 *   không có đường phân tích riêng. Log máy (end call, WebRTC) là tuỳ chọn: {@code clientLogs} trả danh sách rỗng thì
 *   cuộc đó chỉ có signaling, và {@link SourceGroup} ghi lại điều đó.
 * - Chỉ rule, không gọi AI: kết luận của AI hoặc trùng rule, hoặc bị Guardrails hạ thành UNKNOWN — không bao giờ là kết
 *   luận khác. AI không thêm thông tin cho phân bố, chỉ thêm tiền và nhiễu.
 * - Kết quả ghi vào {@link BatchResultSink} với Call-ID mã giả (MVP 6.2: dữ liệu ghi vào ES local qua Output
 *   Sanitizer). Ghi lỗi không làm hỏng lượt chạy — báo cáo vẫn ra, {@link Batch#sinkStatus()} nói rõ.
 */
@Service
public class BatchAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(BatchAnalysisService.class);

    private final CallCatalog catalog;
    private final AnalyzeCallService analyzeService;
    private final BatchResultSink sink;
    private final SensitiveDataSanitizer sanitizer;

    public BatchAnalysisService(CallCatalog catalog, AnalyzeCallService analyzeService, BatchResultSink sink,
                                SensitiveDataSanitizer sanitizer) {
        this.catalog = catalog;
        this.analyzeService = analyzeService;
        this.sink = sink;
        this.sanitizer = sanitizer;
    }

    /**
     * @param results    theo thứ tự Call-ID của {@link CallCatalog}; Call-ID gốc (báo cáo ghi ra máy)
     * @param errors     cuộc không phân tích được — đã bỏ khỏi thống kê, không im lặng
     * @param sinkStatus kết quả ghi vào sink, để in vào báo cáo
     */
    public record Batch(Instant ranAt, List<BatchResult> results, BatchSummary summary, List<String> errors,
                        String sinkStatus) {
    }

    /**
     * @param clientLogs log máy của một Call-ID (rỗng nếu không có); signaling luôn lấy từ nguồn signaling
     * @throws RuntimeException khi không đọc được danh sách Call-ID — không có gì để phân tích
     */
    public Batch run(Function<String, List<AttachedFile>> clientLogs) {
        Instant ranAt = Instant.now();
        List<String> callIds = catalog.callIds();
        log.atInfo().addKeyValue("calls", callIds.size()).log("Batch analysis started");

        List<BatchResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String callId : callIds) {
            try {
                AnalyzeCallService.Analysis analysis = analyzeService.analyze(callId, clientLogs.apply(callId));
                results.add(BatchResult.of(analysis.report(), SourceGroup.of(analysis.timeline())));
            } catch (RuntimeException e) {
                // Pipeline không ném với log hỏng (ParseWarning); tới đây là lỗi I/O khi đọc thư mục hoặc ES rớt giữa
                // chừng. Bỏ cuộc này, ghi lại, chạy tiếp — một cuộc hỏng không được làm mất cả lượt.
                errors.add(callId + ": " + e.getClass().getSimpleName());
                log.atWarn().addKeyValue("error", e.getClass().getSimpleName()).log("Batch analysis: skipped one call");
            }
        }

        BatchSummary summary = BatchSummary.of(results);
        return new Batch(ranAt, List.copyOf(results), summary, List.copyOf(errors), write(ranAt, results));
    }

    private String write(Instant ranAt, List<BatchResult> results) {
        List<BatchResult> pseudonymized = results.stream()
                .map(r -> r.withCallId(sanitizer.pseudonymizeCallId(r.callId())))
                .toList();
        try {
            sink.replaceAll(ranAt, pseudonymized);
            return "đã ghi " + results.size() + " bản ghi (Call-ID mã giả), thay lượt trước";
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("error", e.getClass().getSimpleName()).log("Batch analysis: sink write failed");
            return "KHÔNG ghi được (" + e.getClass().getSimpleName() + ") — báo cáo file vẫn đầy đủ";
        }
    }
}
