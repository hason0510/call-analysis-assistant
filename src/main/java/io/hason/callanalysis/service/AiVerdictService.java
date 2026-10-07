package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.guardrail.GuardrailResult;
import io.hason.callanalysis.domain.guardrail.Guardrails;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.service.port.AiAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/**
 * AI đề xuất → Guardrails kiểm → dùng, hoặc lùi về rule verdict (MVP mục 3.2, 6.1 T6 + T8).
 *
 * Mọi đường lỗi của AI — thiếu key, timeout, provider lỗi, sai format, bị Guardrails loại, hay
 * một exception không lường trước — đều kết thúc ở cùng một chỗ: report từ rule, đánh dấu
 * degraded. MVP mục 3.3: "AI failure không được làm hỏng pipeline".
 */
@Service
public class AiVerdictService {

    private static final Logger log = LoggerFactory.getLogger(AiVerdictService.class);

    public enum Source { AI, RULE_FALLBACK }

    /**
     * @param ai        null khi fallback do AI không trả được gì
     * @param guardrail null khi chưa tới bước Guardrails
     */
    public record Outcome(Source source, boolean degraded, String fallbackReason,
                          Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                          AiAnalysis ai, GuardrailResult guardrail) {

        /** Lệch rule: report phải gắn cờ "cần kiểm tra" (MVP mục 3.2). */
        public boolean needsReview() {
            return guardrail != null && guardrail.status() == GuardrailResult.Status.FLAGGED;
        }

        /**
         * Lùi về rule vì lý do phát hiện SAU bước AI — ví dụ report ghép phần AI không qua schema.
         * Không giữ đầu ra AI: report dựng lại hoàn toàn từ rule.
         */
        public static Outcome ruleFallback(RuleVerdict rule, String reason) {
            return new Outcome(Source.RULE_FALLBACK, true, reason, rule.verdict(), rule.qualityFlag(),
                    rule.issueCategory(), null, null);
        }
    }

    private final AiAnalyzer analyzer;
    private final SensitiveDataSanitizer sanitizer;
    private final Guardrails guardrails = new Guardrails();

    public AiVerdictService(AiAnalyzer analyzer, SensitiveDataSanitizer sanitizer) {
        this.analyzer = analyzer;
        this.sanitizer = sanitizer;
    }

    /**
     * Evidence ID và văn bản căn cứ cho G01 / G05 lấy từ chính context đã gửi AI, không nhận riêng:
     * bên gọi truyền một danh sách khác thì Guardrails kiểm theo thứ AI chưa từng thấy.
     */
    public Outcome decide(AiContext context, RuleVerdict rule, CallMetrics metrics) {
        long started = System.nanoTime();
        AiAnalysis ai;
        try {
            // Output Sanitizer (MVP mục 6.2, ca S07): input đã sạch nhưng model vẫn có thể bịa
            // hoặc lặp lại một chuỗi nhạy cảm — làm sạch TRƯỚC Guardrails và trước khi vào report.
            ai = sanitizeOutput(analyzer.analyze(context));
        } catch (AiUnavailableException e) {
            return fallback(context, rule, e.reason().name(), null, null, started);
        } catch (RuntimeException e) {
            // Lỗi không lường trước của adapter cũng không được lọt ra ngoài pipeline.
            return fallback(context, rule, "UNEXPECTED_ERROR", null, null, started);
        }

        GuardrailResult checked = guardrails.check(ai, rule, context.evidenceIds(), metrics,
                context.groundingTexts());
        if (!checked.usable()) {
            return fallback(context, rule, "GUARDRAIL_REJECTED", ai, checked, started);
        }
        log.info("request_id={} result_status={} latency_ms={}", context.requestId(),
                checked.status(), elapsedMs(started));
        return new Outcome(Source.AI, false, null, checked.verdict(), checked.qualityFlag(),
                checked.issueCategory(), ai, checked);
    }

    private static Outcome fallback(AiContext context, RuleVerdict rule, String reason,
                                    AiAnalysis ai, GuardrailResult checked, long started) {
        // Chỉ log mã lỗi và mã vi phạm — không log nội dung AI trả về (MVP mục 6.2).
        log.warn("request_id={} result_status=FALLBACK fallback_reason={} latency_ms={}{}",
                context.requestId(), reason, elapsedMs(started),
                checked == null ? "" : " violations=" + checked.violations().stream()
                        .map(GuardrailResult.Violation::code).distinct().toList());
        return new Outcome(Source.RULE_FALLBACK, true, reason, rule.verdict(), rule.qualityFlag(),
                rule.issueCategory(), ai, checked);
    }

    private AiAnalysis sanitizeOutput(AiAnalysis ai) {
        if (ai == null) {
            return null;
        }
        return new AiAnalysis(ai.verdict(), ai.qualityFlag(), ai.issueCategory(), ai.confidenceLevel(),
                clean(ai.summary()), ai.evidenceIds(), clean(ai.analysis()),
                ai.suggestions().stream().map(this::clean).toList(), ai.usage());
    }

    private String clean(String text) {
        return text == null ? null : sanitizer.sanitize(text).text();
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
