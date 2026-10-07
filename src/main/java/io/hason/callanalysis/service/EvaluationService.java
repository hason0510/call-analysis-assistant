package io.hason.callanalysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.evaluation.EvaluationRun;
import io.hason.callanalysis.domain.evaluation.SensitiveValues;
import io.hason.callanalysis.domain.evaluation.TemplateCompliance;
import io.hason.callanalysis.domain.guardrail.GuardrailResult;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.validation.AttachedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluation Runner (MVP mục 6.1 T9): chạy từng câu hỏi của một case, lặp nhiều lần, qua ĐÚNG luồng
 * của Chat API ({@link ChatAnalysisService}) — hệ thống được chấm điểm là hệ thống người dùng dùng trên web,
 * không phải một đường riêng cho benchmark.
 *
 * {@code call_id} của case được truyền vào pipeline như khi người dùng ghi Call-ID trong câu hỏi: WebRTC log
 * không mang Call-ID, nên cuộc gọi chỉ có WebRTC log mà không có Call-ID thì không lấy được signaling và
 * luôn ra UNKNOWN — mẫu MVP mục 6.3 có sẵn trường call_id, runner dùng nó. Call-ID ghi trong câu hỏi
 * vẫn được ưu tiên.
 *
 * Security Leakage (MVP mục 6.5) đo ngay trong lần chạy, trên bộ nhớ: giá trị nhạy cảm GỐC của case
 * ({@link SensitiveValues}) có còn nguyên văn trong chuỗi gửi AI và trong phản hồi không. Chỉ lưu số đếm.
 *
 * Lỗi của một lần chạy không dừng cả lượt: lần đó ghi mã lỗi và tính vào Pipeline Success Rate.
 */
@Service
public class EvaluationService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final ChatAnalysisService chat;
    private final ObjectMapper mapper;

    public EvaluationService(ChatAnalysisService chat, ObjectMapper mapper) {
        this.chat = chat;
        this.mapper = mapper;
    }

    /**
     * Thứ tự tất định: câu hỏi theo thứ tự trong case, mỗi câu chạy đủ {@code repeat} lần rồi mới sang câu sau.
     *
     * @param rawSignaling signaling THÔ của cuộc gọi — chỉ để lấy giá trị nhạy cảm gốc; rỗng khi không truy vấn được
     */
    public List<EvaluationRun> runCase(BenchmarkCase c, List<AttachedFile> files,
                                       List<RawSignalingRecord> rawSignaling, int repeat) {
        SensitiveValues sensitive = SensitiveValues.collect(files, rawSignaling);
        List<EvaluationRun> runs = new ArrayList<>();
        for (int qi = 0; qi < c.questions().size(); qi++) {
            BenchmarkCase.Question q = c.questions().get(qi);
            for (int rep = 1; rep <= repeat; rep++) {
                EvaluationRun run = runOnce(c, qi + 1, q, rep, files, sensitive);
                log.info("{} câu {}/{} lần {}/{}: {} {} ms", c.caseId(), qi + 1, c.questions().size(), rep, repeat,
                        run.succeeded() ? run.outcomeKey() + (run.degraded() ? " (degraded " + run.fallbackReason() + ")" : "")
                                : "LỖI " + run.error(), run.latencyMs());
                runs.add(run);
            }
        }
        return runs;
    }

    private EvaluationRun runOnce(BenchmarkCase c, int index, BenchmarkCase.Question q, int repeat,
                                  List<AttachedFile> files, SensitiveValues sensitive) {
        String caseId = c.caseId();
        long started = System.nanoTime();
        ChatAnalysisService.Trace trace;
        try {
            trace = chat.handleTraced(q.text(), files, c.callId());
        } catch (RuntimeException e) {
            // Chỉ giữ tên lỗi: thông điệp exception có thể mang một phần nội dung file.
            return new EvaluationRun(caseId, index, null, repeat, q.expectedIntent(), e.getClass().getSimpleName(),
                    null, null, null, null, null, null, null, null, null, null, null, null, false, null, false,
                    List.of(), null, null, sensitive.count(), null, null, null, null, elapsedMs(started));
        }
        long latency = elapsedMs(started);

        ChatAnalysisService.Response response = trace.response();
        CallReport report = response.report();
        AiVerdictService.Outcome outcome = trace.outcome();
        AiAnalysis ai = outcome == null ? null : outcome.ai();
        GuardrailResult guardrail = outcome == null ? null : outcome.guardrail();
        return new EvaluationRun(caseId, index, trace.request().question(), repeat, q.expectedIntent(), null,
                response.type().name(), response.intent(), trace.request().source().name(),
                report == null ? null : report.verdict(),
                report == null ? null : report.qualityFlag(),
                report == null ? null : report.issueCategory(),
                report == null ? null : report.confidenceLevel().name(),
                trace.rule() == null ? null : trace.rule().verdict(),
                trace.rule() == null ? null : trace.rule().issueCategory(),
                ai == null ? null : ai.verdict(),
                ai == null ? null : ai.issueCategory(),
                report == null ? null : report.analysisSource().name(),
                report != null && report.degraded(),
                report == null ? null : report.fallbackReason(),
                report != null && report.needsReview(),
                guardrail == null ? List.of()
                        : guardrail.violations().stream().map(GuardrailResult.Violation::toString).toList(),
                report == null ? null : TemplateCompliance.check(response.rendered()),
                report == null ? null : report.metrics().equals(trace.calculatorMetrics()),
                sensitive.count(),
                sensitive.foundIn(aiInput(trace)),
                sensitive.foundIn(json(response)),
                trace.request().usage(),
                ai == null ? null : ai.usage(),
                latency);
    }

    /**
     * Mọi chữ rời khỏi máy tới AI: câu hỏi đã làm sạch (gửi cho bước phân loại) và — khi có chạy phân tích —
     * cùng các phần như OpenAiAnalyzer.userMessage: câu hỏi, trọng tâm, JSON của context.
     * Đo cả khi chưa có key: đó là chuỗi SẼ được gửi.
     */
    private String aiInput(ChatAnalysisService.Trace trace) {
        StringBuilder text = new StringBuilder(nullToEmpty(trace.request().question()));
        AiContext context = trace.aiContext();
        if (context != null) {
            text.append('\n').append(nullToEmpty(context.question()))
                    .append('\n').append(nullToEmpty(context.focus()))
                    .append('\n').append(json(context.payload()));
        }
        return text.toString();
    }

    /** Toàn bộ phản hồi trả người dùng — report JSON, bản render, câu trả lời — như Chat API trả ra. */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
