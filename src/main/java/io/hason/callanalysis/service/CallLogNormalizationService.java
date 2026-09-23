package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.parse.DetectedLogType;
import io.hason.callanalysis.domain.parse.EndCallLogParser;
import io.hason.callanalysis.domain.parse.FileTypeDetector;
import io.hason.callanalysis.domain.parse.ParseContext;
import io.hason.callanalysis.domain.parse.ParseResult;
import io.hason.callanalysis.domain.parse.ParseWarning;
import io.hason.callanalysis.domain.parse.WebRtcLogParser;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import io.hason.callanalysis.domain.signaling.SignalingNormalizer;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineBuilder;
import io.hason.callanalysis.domain.timeline.TimelineNote;
import io.hason.callanalysis.service.port.SignalingSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Điều phối bước chuẩn hoá: nhận nội dung các file đính kèm + truy vấn signaling,
 * trả về toàn bộ canonical event của một cuộc gọi.
 *
 * Service này không đọc đĩa — nó nhận sẵn nội dung file. Nhờ vậy test được bằng
 * Map viết tay, không cần thư mục mẫu.
 */
@Service
public class CallLogNormalizationService {

    private final SignalingSource signalingSource;

    private final FileTypeDetector detector = new FileTypeDetector();
    private final EndCallLogParser endCallParser = new EndCallLogParser();
    private final WebRtcLogParser webRtcParser = new WebRtcLogParser();
    private final SignalingNormalizer signalingNormalizer = new SignalingNormalizer();
    private final TimelineBuilder timelineBuilder = new TimelineBuilder();

    public CallLogNormalizationService(SignalingSource signalingSource) {
        this.signalingSource = signalingSource;
    }

    /** Kết quả parse một file đính kèm, kèm loại đã nhận diện được. */
    public record FileOutcome(String fileName, DetectedLogType detectedType, Leg leg,
                              int lineCount, ParseResult result) {}

    public record CallOutcome(String callId, ParseResult signaling, List<FileOutcome> files) {

        public ParseResult combined() {
            ParseResult all = signaling;
            for (FileOutcome f : files) {
                all = all.merge(f.result());
            }
            return all;
        }
    }

    /** Chuẩn hoá rồi dựng luôn timeline — đầu vào cho T6 (chỉ số) và T7 (evidence). */
    public CallTimeline buildTimeline(String callId, Map<String, List<String>> attachedFiles) {
        // Truy vấn MỘT lần rồi dùng lại: trước đây hàm này gọi fetchByCallId hai lượt,
        // một lượt cho normalize và một lượt cho leg, tức hai vòng tới Elasticsearch.
        SignalingFetch fetch = signalingSource.fetchByCallId(callId);
        CallOutcome outcome = normalize(callId, attachedFiles, fetch);
        LegAssignment legs = LegAssignment.fromFirstInitCall(
                fetch == null ? List.of() : fetch.records());

        List<TimelineNote> seedNotes = new ArrayList<>(truncationNotes(fetch));
        seedNotes.addAll(attachedFileWarningNotes(outcome));

        return timelineBuilder.build(callId, outcome.combined().events(), legs, seedNotes);
    }

    /** Nhiều nhất chừng này dòng cảnh báo được nêu tên; phần còn lại chỉ đếm. */
    private static final int WARNING_SAMPLE_SIZE = 2;

    /**
     * Tóm tắt cảnh báo của TỪNG file đính kèm thành ghi chú giới hạn dữ liệu.
     *
     * Không có bước này thì cảnh báo parse chết tại chỗ: ca kiểm thử F03 của MVP mục 6.4
     * (file đính kèm mang Call-ID khác) sinh đúng một ParseWarning, và report sẽ không
     * hề nhắc tới nó — người đọc tưởng dữ liệu sạch.
     *
     * Tóm tắt theo file chứ không liệt kê từng dòng: một file hỏng nặng có thể sinh
     * hàng nghìn cảnh báo, đổ hết vào report thì mục "Giới hạn dữ liệu" thành vô dụng.
     */
    private static List<TimelineNote> attachedFileWarningNotes(CallOutcome outcome) {
        List<TimelineNote> notes = new ArrayList<>();
        for (FileOutcome file : outcome.files()) {
            List<ParseWarning> warnings = file.result().warnings();
            if (warnings.isEmpty()) {
                continue;
            }
            String samples = warnings.stream()
                    .limit(WARNING_SAMPLE_SIZE)
                    .map(w -> "dòng " + w.lineNumber() + ": " + w.reason())
                    .collect(java.util.stream.Collectors.joining("; "));
            String more = warnings.size() > WARNING_SAMPLE_SIZE
                    ? " (và " + (warnings.size() - WARNING_SAMPLE_SIZE) + " cảnh báo khác)"
                    : "";
            notes.add(TimelineNote.of(TimelineNote.Kind.DATA_LIMITATION,
                    file.fileName() + ": " + warnings.size() + " dòng có vấn đề khi đọc — "
                            + samples + more));
        }
        return notes;
    }

    /**
     * Bản export bị cắt bớt là dữ kiện nằm ở METADATA của nguồn, không nằm trong
     * chuỗi sự kiện. Nó phải được đưa vào timeline dưới dạng ghi chú CÓ KIỂU thì
     * tầng rule mới thấy được.
     */
    private static List<TimelineNote> truncationNotes(SignalingFetch fetch) {
        if (fetch == null || !fetch.truncated()) {
            return List.of();
        }
        return List.of(TimelineNote.of(TimelineNote.Kind.SIGNALING_TRUNCATED,
                "Bản export signaling bị cắt bớt: trả về " + fetch.returned()
                        + "/" + fetch.totalMatching() + " event, thiếu " + fetch.missingCount()));
    }

    public CallOutcome normalize(String callId, Map<String, List<String>> attachedFiles) {
        return normalize(callId, attachedFiles, signalingSource.fetchByCallId(callId));
    }

    private CallOutcome normalize(String callId, Map<String, List<String>> attachedFiles,
                                  SignalingFetch fetch) {
        ParseResult signaling = signalingNormalizer.normalize(fetch);

        List<FileOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : orderedByName(attachedFiles).entrySet()) {
            String fileName = entry.getKey();
            List<String> lines = entry.getValue();

            DetectedLogType type = detector.detect(lines);
            // Signaling KHÔNG lấy từ file đính kèm mà truy vấn từ Elasticsearch.
            //
            // MVP mục 1.2 phân vai rõ: end call log và WebRTC log là "người dùng đính kèm",
            // còn signaling thì "hệ thống tự truy vấn theo Call-ID". Mục 8.1 nhắc lại
            // bằng chữ "signaling tự lấy từ ES local".
            //
            // Thư mục data mẫu có sẵn signaling.json chỉ vì mentor phải giao data bằng
            // cách nào đó; file đó là nguyên liệu cho bước --import-signaling. Ngoài đời
            // người dùng chỉ có log trong máy họ, không có file signaling của server —
            // nên đọc thẳng file ở đây sẽ tạo ra thứ chỉ chạy được trên thư mục mẫu.
            if (type == DetectedLogType.SIGNALING_JSON) {
                continue;
            }

            Leg leg = legHintFromFileName(fileName);
            ParseContext ctx = ParseContext.of(fileName, callId, leg);
            ParseResult result = switch (type) {
                case ENDCALL_LOG -> endCallParser.parse(lines, ctx);
                case WEBRTC_IOS, WEBRTC_ANDROID -> webRtcParser.parse(lines, ctx);
                case SIGNALING_JSON, UNKNOWN -> ParseResult.empty();
            };
            outcomes.add(new FileOutcome(fileName, type, leg, lines.size(), result));
        }
        return new CallOutcome(callId, signaling, List.copyOf(outcomes));
    }

    private static Map<String, List<String>> orderedByName(Map<String, List<String>> files) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        files.keySet().stream().sorted().forEach(k -> ordered.put(k, files.get(k)));
        return ordered;
    }

    /**
     * Suy leg từ tên file — chỉ là GỢI Ý. Data mẫu có `calleer_webrtc.log` thực chất
     * là log của caller; xác định đúng chủ sở hữu cần đối chiếu format (iOS/Android)
     * với cột platform của bản ghi #H1, thuộc bước correlate của T4.
     */
    private static Leg legHintFromFileName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.startsWith("caller")) {
            return Leg.CALLER;
        }
        if (lower.startsWith("callee")) {
            return Leg.CALLEE;
        }
        return Leg.UNKNOWN;
    }
}
