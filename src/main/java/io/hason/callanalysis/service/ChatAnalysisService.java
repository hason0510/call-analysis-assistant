package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.observability.RequestRecord;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import io.hason.callanalysis.service.port.RequestRecordSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
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
    private final RequestRecordSink records;

    @Autowired
    public ChatAnalysisService(RequestParserService requestParser, AnalyzeCallService analyzeService,
                               AiVerdictService aiVerdictService, ReportSchemaValidator schemaValidator,
                               ObjectProvider<RequestRecordSink> records) {
        this(requestParser, analyzeService, aiVerdictService, schemaValidator,
                records.getIfAvailable(() -> RequestRecordSink.NONE));
    }

    public ChatAnalysisService(RequestParserService requestParser, AnalyzeCallService analyzeService,
                               AiVerdictService aiVerdictService, ReportSchemaValidator schemaValidator) {
        this(requestParser, analyzeService, aiVerdictService, schemaValidator, RequestRecordSink.NONE);
    }

    ChatAnalysisService(RequestParserService requestParser, AnalyzeCallService analyzeService,
                        AiVerdictService aiVerdictService, ReportSchemaValidator schemaValidator,
                        RequestRecordSink records) {
        this.requestParser = requestParser;
        this.analyzeService = analyzeService;
        this.aiVerdictService = aiVerdictService;
        this.schemaValidator = schemaValidator;
        this.records = records;
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
                        AiContext aiContext, List<Evidence> evidence) {}

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
        // request_id trong MDC: mọi dòng log của request này (kể cả từ adapter không biết request_id, như dòng
        // truy vấn Elasticsearch) mang cùng mã — log JSON lọc được theo một request (MVP 7.1 T5).
        MDC.put("request_id", requestId);
        long started = System.nanoTime();
        try {
            return handle(requestId, question, files, knownCallId);
        } catch (RuntimeException e) {
            // Lỗi không lường trước vẫn để lại một bản ghi (T9: Kibana view cho "lỗi"); chỉ tên lớp, không thông điệp
            records.record(new RequestRecord(Instant.now(), requestId, "ERROR", null, null, null, false,
                    files == null ? 0 : files.size(),
                    null, null, null, null, null, null, null, null, elapsedMs(started), null, null, null, null, null,
                    null, e.getClass().getSimpleName()));
            throw e;
        } finally {
            MDC.remove("request_id");
        }
    }

    private Trace handle(String requestId, String question, List<AttachedFile> files, String knownCallId) {
        long started = System.nanoTime();

        long step = System.nanoTime();
        ParsedRequest request = requestParser.parse(requestId, question);
        long intentMs = elapsedMs(step);
        if (request.isOutOfScope()) {
            log.atInfo().addKeyValue("request_id", requestId).addKeyValue("result_status", "OUT_OF_SCOPE")
                    .addKeyValue("latency_ms", elapsedMs(started)).addKeyValue("intent_ms", intentMs)
                    .log("Request done");
            records.record(noReport(requestId, "OUT_OF_SCOPE", request, files, elapsedMs(started), intentMs));
            return new Trace(new Response(requestId, Type.OUT_OF_SCOPE, request.intent(), null,
                    request.outOfScopeReply(), null, null), request, null, null, List.of(), null, List.of());
        }
        String callId = request.callId() != null ? request.callId() : knownCallId;
        if (files.isEmpty() && callId == null) {
            log.atInfo().addKeyValue("request_id", requestId).addKeyValue("result_status", "INVALID_REQUEST")
                    .addKeyValue("latency_ms", elapsedMs(started)).addKeyValue("intent_ms", intentMs)
                    .log("Request done");
            records.record(noReport(requestId, "INVALID_REQUEST", request, files, elapsedMs(started), intentMs));
            return new Trace(new Response(requestId, Type.INVALID_REQUEST, request.intent(), request.focus(),
                    NOTHING_TO_ANALYZE, null, null), request, null, null, List.of(), null, List.of());
        }

        step = System.nanoTime();
        AnalyzeCallService.Analysis analysis = analyzeService.analyze(callId, files);
        AiContext context = analyzeService.aiContext(analysis, requestId, request.question(), request.focus());
        long analysisMs = elapsedMs(step);
        step = System.nanoTime();
        AiVerdictService.Outcome outcome = aiVerdictService.decide(context, analysis.verdict(), analysis.metrics());
        long aiMs = elapsedMs(step);

        step = System.nanoTime();
        List<String> notes = withUnverifiedWebRtcNote(request, analysis);
        CallReport report = analyzeService.finalReport(analysis, outcome, notes);
        ReportSchemaValidator.Result check = schemaValidator.validate(report);
        if (!check.valid() && outcome.source() == AiVerdictService.Source.AI) {
            // MVP mục 6.1 T2: JSON phải qua schema trước khi render. Phần AI làm hỏng → dựng lại từ rule.
            log.atWarn().addKeyValue("request_id", requestId).addKeyValue("result_status", "FALLBACK")
                    .addKeyValue("fallback_reason", "REPORT_SCHEMA_INVALID").addKeyValue("errors", check.errors().size())
                    .log("Report schema");
            report = analyzeService.finalReport(analysis,
                    AiVerdictService.Outcome.ruleFallback(analysis.verdict(), "REPORT_SCHEMA_INVALID"), notes);
            check = schemaValidator.validate(report);
        }
        if (!check.valid()) {
            throw new IllegalStateException("Report dựng từ rule không hợp lệ theo schema: " + check.errors());
        }

        String rendered = renderer.render(report);
        long reportMs = elapsedMs(step);

        // Latency từng bước (MVP 7.1 T5): analysis_ms gồm parse log + truy vấn ES (dòng "Signaling fetch" có riêng
        // signaling_ms) + rule; ai_ms gồm lời gọi AI + Guardrails (dòng "AI verdict" có riêng guardrails_ms).
        log.atInfo().addKeyValue("request_id", requestId).addKeyValue("call_id", context.logCallId())
                .addKeyValue("result_status", "REPORT").addKeyValue("verdict", report.verdict())
                .addKeyValue("confidence", report.confidenceLevel()).addKeyValue("source", report.analysisSource())
                .addKeyValue("degraded", report.degraded()).addKeyValue("fallback_reason", report.fallbackReason())
                .addKeyValue("latency_ms", elapsedMs(started)).addKeyValue("intent_ms", intentMs)
                .addKeyValue("analysis_ms", analysisMs).addKeyValue("ai_ms", aiMs).addKeyValue("report_ms", reportMs)
                .log("Request done");
        TokenUsage analysisTokens = outcome.ai() == null ? null : outcome.ai().usage();
        records.record(new RequestRecord(Instant.now(), requestId, "REPORT", context.logCallId(),
                request.intent() == null ? null : request.intent().name(), request.source().name(), request.garbled(),
                files.size(), report.verdict().name(),
                report.issueCategory() == null ? null : report.issueCategory().name(), report.qualityFlag(),
                report.confidenceLevel().name(), report.analysisSource().name(), report.degraded(),
                report.fallbackReason(), report.needsReview(), elapsedMs(started), intentMs, analysisMs, aiMs, reportMs,
                sum(request.usage(), analysisTokens, TokenUsage::promptTokens),
                sum(request.usage(), analysisTokens, TokenUsage::completionTokens), null));
        return new Trace(new Response(requestId, Type.REPORT, request.intent(), request.focus(), null, report,
                rendered), request, analysis.verdict(), outcome, analysis.report().metrics(),
                context, analysis.evidence());
    }

    /**
     * Call-ID lấy từ câu hỏi mà log máy chỉ có WebRTC log: loại log này không ghi Call-ID, nên không gì kiểm được nó thuộc
     * cuộc gọi người dùng nêu. Dán nhầm Call-ID là ra kết luận về cuộc khác (Peer Evaluation 2026-10-20) — không loại file
     * vì đây cũng là cách dùng hợp lệ (chỉ có WebRTC log thì phải nói Call-ID), nhưng nói rõ trong "Giới hạn dữ liệu".
     * Call-ID do runner / CLI truyền vào (từ file case, tên thư mục) là nguồn tin cậy nên không ghi.
     */
    static List<String> withUnverifiedWebRtcNote(ParsedRequest request, AnalyzeCallService.Analysis analysis) {
        CallTimeline timeline = analysis.timeline();
        if (request.callId() == null || timeline.hasSource(LogSource.ENDCALL) || !timeline.hasSource(LogSource.WEBRTC)) {
            return request.notes();
        }
        List<String> notes = new ArrayList<>(request.notes());
        notes.add("Call-ID " + request.callId() + " lấy từ câu hỏi, nhưng chỉ có WebRTC log mà loại log này không ghi "
                + "Call-ID: không kiểm chứng được log thuộc cuộc gọi này. Kết luận không khớp thực tế thì kiểm lại Call-ID, "
                + "hoặc gửi kèm end call log");
        return notes;
    }

    private static RequestRecord noReport(String requestId, String status, ParsedRequest request,
                                          List<AttachedFile> files, long latencyMs, long intentMs) {
        return new RequestRecord(Instant.now(), requestId, status, null,
                request.intent() == null ? null : request.intent().name(), request.source().name(), request.garbled(),
                files.size(), null, null, null, null, null, null, null, null, latencyMs, intentMs, null, null, null,
                sum(request.usage(), null, TokenUsage::promptTokens),
                sum(request.usage(), null, TokenUsage::completionTokens), null);
    }

    /** Token của hai lời gọi AI cộng lại; null khi không lời gọi nào trả lời. */
    private static Integer sum(TokenUsage intent, TokenUsage analysis, java.util.function.ToIntFunction<TokenUsage> part) {
        if (intent == null && analysis == null) {
            return null;
        }
        return (intent == null ? 0 : part.applyAsInt(intent)) + (analysis == null ? 0 : part.applyAsInt(analysis));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
