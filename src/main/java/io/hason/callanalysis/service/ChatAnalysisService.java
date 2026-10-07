package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestration của Chat API (MVP mục 6.1 T2), đúng thứ tự sơ đồ mục 6:
 *
 *   Request Parser → (OUT_OF_SCOPE: dừng, trả câu từ chối cố định)
 *   → File Validator + pipeline Sprint 1 (chuẩn hoá, timeline, chỉ số, rule, evidence; signaling từ ES)
 *   → Input Sanitizer → AI → Guardrails → (fallback) → report → Output Sanitizer
 *   → kiểm schema → Renderer
 *
 * Không bước nào làm hỏng request (MVP mục 3.3): AI lỗi thì lùi về rule; report có phần AI mà sai
 * schema thì dựng lại từ rule. Chỉ khi cả report thuần rule cũng sai schema mới trả lỗi — đó là bug
 * của code, không phải của input.
 *
 * Log chỉ gồm request_id, kết quả, độ trễ — không log câu hỏi hay nội dung file.
 */
@Service
public class ChatAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ChatAnalysisService.class);

    public static final String NOTHING_TO_ANALYZE =
            "Cần đính kèm ít nhất một file log, hoặc ghi Call-ID trong câu hỏi để lấy signaling.";

    private final RequestParserService requestParser;
    private final AnalyzeCallService analyzeService;
    private final AiVerdictService aiVerdictService;
    private final ReportSchemaValidator schemaValidator;
    private final ReportRenderer renderer = new ReportRenderer();

    public ChatAnalysisService(RequestParserService requestParser, AnalyzeCallService analyzeService,
                               AiVerdictService aiVerdictService, ReportSchemaValidator schemaValidator) {
        this.requestParser = requestParser;
        this.analyzeService = analyzeService;
        this.aiVerdictService = aiVerdictService;
        this.schemaValidator = schemaValidator;
    }

    public enum Type {
        /** Report theo mẫu 4.5 (kể cả degraded). */
        REPORT,
        /** Câu hỏi ngoài phạm vi — câu từ chối cố định, không chạy pipeline. */
        OUT_OF_SCOPE,
        /** Không có gì để phân tích (không file, không Call-ID). */
        INVALID_REQUEST
    }

    /**
     * @param message  REPORT: null; còn lại: câu trả lời cho người dùng
     * @param report   JSON report đã qua schema; null khi không phải REPORT
     * @param rendered report dạng Markdown theo mẫu 4.5; null khi không phải REPORT
     */
    public record Response(String requestId, Type type, Intent intent, String focus, String message,
                           CallReport report, String rendered) {}

    /**
     * Response cùng các bước trung gian — cho Evaluation Runner (MVP mục 6.1 T9), không trả ra API.
     *
     * @param rule              kết luận của rule; null khi không chạy pipeline (OUT_OF_SCOPE, INVALID_REQUEST)
     * @param outcome           kết quả AI + Guardrails TRƯỚC khi ghép report (giữ verdict thô của AI, mã vi phạm)
     * @param calculatorMetrics bảng chỉ số dựng thẳng từ Metrics Calculator, chưa qua bước ghép phần AI
     * @param aiContext         context đã làm sạch gửi AI phân tích (đo Security Leakage đầu vào); null khi không chạy pipeline
     */
    public record Trace(Response response, ParsedRequest request, RuleVerdict rule,
                        AiVerdictService.Outcome outcome, List<CallReport.MetricEntry> calculatorMetrics,
                        AiContext aiContext) {}

    public Response handle(String question, List<AttachedFile> files) {
        return handleTraced(question, files, null).response();
    }

    /**
     * @param knownCallId Call-ID bên gọi đã biết (Evaluation Runner: trường {@code call_id} của case), xử lý như
     *                    khi người dùng ghi Call-ID trong câu hỏi. Call-ID ghi trong câu hỏi vẫn được ưu tiên.
     *                    Null: chỉ dựa vào câu hỏi và end call log, như Web UI.
     */
    public Trace handleTraced(String question, List<AttachedFile> files, String knownCallId) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();

        ParsedRequest request = requestParser.parse(requestId, question);
        if (request.isOutOfScope()) {
            log.info("request_id={} result_status=OUT_OF_SCOPE latency_ms={}", requestId, elapsedMs(started));
            return new Trace(new Response(requestId, Type.OUT_OF_SCOPE, request.intent(), null,
                    ParsedRequest.OUT_OF_SCOPE_REPLY, null, null), request, null, null, List.of(), null);
        }
        String callId = request.callId() != null ? request.callId() : knownCallId;
        if (files.isEmpty() && callId == null) {
            log.info("request_id={} result_status=INVALID_REQUEST latency_ms={}", requestId, elapsedMs(started));
            return new Trace(new Response(requestId, Type.INVALID_REQUEST, request.intent(), request.focus(),
                    NOTHING_TO_ANALYZE, null, null), request, null, null, List.of(), null);
        }

        AnalyzeCallService.Analysis analysis = analyzeService.analyze(callId, files);
        AiContext context = analyzeService.aiContext(analysis, requestId, request.question(), request.focus());
        AiVerdictService.Outcome outcome = aiVerdictService.decide(context, analysis.verdict(), analysis.metrics());

        CallReport report = analyzeService.finalReport(analysis, outcome, request.notes());
        ReportSchemaValidator.Result check = schemaValidator.validate(report);
        if (!check.valid() && outcome.source() == AiVerdictService.Source.AI) {
            // MVP mục 6.1 T2: JSON phải qua schema trước khi render. Phần AI làm hỏng → dựng lại từ rule.
            log.warn("request_id={} result_status=FALLBACK fallback_reason=REPORT_SCHEMA_INVALID errors={}",
                    requestId, check.errors().size());
            report = analyzeService.finalReport(analysis,
                    AiVerdictService.Outcome.ruleFallback(analysis.verdict(), "REPORT_SCHEMA_INVALID"), request.notes());
            check = schemaValidator.validate(report);
        }
        if (!check.valid()) {
            throw new IllegalStateException("Report dựng từ rule không hợp lệ theo schema: " + check.errors());
        }

        log.info("request_id={} result_status=REPORT verdict={} source={} degraded={} latency_ms={}",
                requestId, report.verdict(), report.analysisSource(), report.degraded(), elapsedMs(started));
        return new Trace(new Response(requestId, Type.REPORT, request.intent(), request.focus(), null, report,
                renderer.render(report)), request, analysis.verdict(), outcome, analysis.report().metrics(),
                context);
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
