package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiContextFixtures;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.service.AiVerdictService.Outcome;
import io.hason.callanalysis.service.AiVerdictService.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fallback (MVP mục 6.1 T8): mọi đường lỗi của AI kết thúc ở rule verdict, đánh dấu degraded. */
class AiVerdictServiceTest {

    private static final RuleVerdict RULE = new RuleVerdict(Verdict.FAIL, false,
            IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.HIGH, "Không tồn tại INVITE", List.of());
    private static final AiContext CONTEXT = AiContextFixtures.withEvidence("req-1", "Vì sao gọi không được?", "EV01");
    private static final CallMetrics NO_METRICS = new CallMetrics("CALL-1", List.of());
    private static final SensitiveDataSanitizer SANITIZER = new SensitiveDataSanitizer(
            new SensitiveDataInventoryLoader().load(), Pseudonymizer.withRandomKey());

    private static Outcome run(io.hason.callanalysis.service.port.AiAnalyzer analyzer) {
        return new AiVerdictService(analyzer, SANITIZER).decide(CONTEXT, RULE, NO_METRICS);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Reason.class)
    @DisplayName("AI không dùng được (thiếu key, timeout, lỗi provider, sai format, từ chối) -> rule, degraded")
    void aiUnavailableFallsBackToRule(Reason reason) {
        Outcome o = run(ctx -> { throw new AiUnavailableException(reason, "x"); });

        assertThat(o.source()).isEqualTo(Source.RULE_FALLBACK);
        assertThat(o.degraded()).isTrue();
        assertThat(o.fallbackReason()).isEqualTo(reason.name());
        assertThat(o.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(o.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
    }

    @Test
    @DisplayName("adapter ném lỗi không lường trước -> vẫn fallback, không làm hỏng pipeline")
    void unexpectedExceptionFallsBack() {
        Outcome o = run(ctx -> { throw new IllegalStateException("bug trong adapter"); });

        assertThat(o.source()).isEqualTo(Source.RULE_FALLBACK);
        assertThat(o.fallbackReason()).isEqualTo("UNEXPECTED_ERROR");
    }

    @Test
    @DisplayName("Guardrails loại câu trả lời (evidence bịa) -> fallback, giữ lại vi phạm để báo cáo")
    void rejectedByGuardrailsFallsBack() {
        Outcome o = run(ctx -> new AiAnalysis("FAIL", false, "SIGNALING_FAILURE", "HIGH",
                "Không gửi được INVITE.", List.of("EV42"), "Thiếu INVITE.", List.of()));

        assertThat(o.source()).isEqualTo(Source.RULE_FALLBACK);
        assertThat(o.fallbackReason()).isEqualTo("GUARDRAIL_REJECTED");
        assertThat(o.guardrail().violations()).extracting(v -> v.code()).containsExactly("G01");
    }

    @Test
    @DisplayName("AI hợp lệ và khớp rule -> dùng kết quả AI, không degraded")
    void validAnswerIsUsed() {
        Outcome o = run(ctx -> new AiAnalysis("FAIL", false, "SIGNALING_FAILURE", "HIGH",
                "Server chưa từng gọi tới callee.", List.of("EV01"), "Không có INVITE.", List.of()));

        assertThat(o.source()).isEqualTo(Source.AI);
        assertThat(o.degraded()).isFalse();
        assertThat(o.needsReview()).isFalse();
    }

    @Test
    @DisplayName("AI lệch rule -> dùng nhưng verdict UNKNOWN và cần kiểm tra")
    void mismatchIsFlagged() {
        Outcome o = run(ctx -> new AiAnalysis("SUCCESS", false, "NONE", "HIGH",
                "Bình thường.", List.of("EV01"), "Không thấy lỗi.", List.of()));

        assertThat(o.source()).isEqualTo(Source.AI);
        assertThat(o.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(o.needsReview()).isTrue();
    }

    @Test
    @DisplayName("S07 — AI trả lời chứa lại giá trị nhạy cảm -> được làm sạch trước Guardrails và trước report")
    void s07AiResponseIsSanitized() {
        Outcome o = run(ctx -> new AiAnalysis("FAIL", false, "SIGNALING_FAILURE", "HIGH",
                "Server chưa gọi tới callee; client 203.0.113.7, hotline 0912345678.", List.of("EV01"),
                "Token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln bị từ chối.",
                List.of("Liên hệ admin@example.com")));

        String all = o.ai().summary() + o.ai().analysis() + String.join(" ", o.ai().suggestions());
        assertThat(all).doesNotContain("203.0.113.7").doesNotContain("0912345678")
                .doesNotContain("eyJ").doesNotContain("admin@example.com");
        assertThat(o.source()).isEqualTo(Source.AI);       // làm sạch không làm hỏng câu trả lời hợp lệ
    }
}
