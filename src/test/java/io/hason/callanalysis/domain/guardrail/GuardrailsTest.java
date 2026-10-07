package io.hason.callanalysis.domain.guardrail;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.guardrail.GuardrailResult.Status;
import io.hason.callanalysis.domain.guardrail.GuardrailResult.Violation;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricKey;
import io.hason.callanalysis.domain.metrics.MetricValue;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ca kiểm thử G01-G05 của MVP mục 6.4. */
class GuardrailsTest {

    private final Guardrails guardrails = new Guardrails();

    private static final RuleVerdict RULE_ICE_FAIL = new RuleVerdict(Verdict.FAIL, false,
            IssueCategory.ICE_FAILURE, ConfidenceLevel.HIGH, "ICE chuyển sang failed", List.of());

    private static final CallMetrics METRICS = new CallMetrics("CALL-1", List.of(
            CallMetric.of(MetricKey.SETUP_TIME, MetricValue.millis(1505)),
            CallMetric.of(MetricKey.MOS, Leg.CALLER, MetricValue.of(new BigDecimal("4.35"), "")),
            CallMetric.of(MetricKey.PACKET_LOSS, Leg.CALLER, MetricValue.unavailable("leg chưa có media"))));

    private static final List<String> EVIDENCE = List.of("EV01", "EV02", "EV03");
    private static final List<String> GROUNDING = List.of("[EV02] ACK kèm callErrorCode: 428");

    private static AiAnalysis ai(String verdict, String category, List<String> evidence, String analysis) {
        return new AiAnalysis(verdict, false, category, "HIGH",
                "Cuộc gọi thất bại do ICE.", evidence, analysis, List.of("Kiểm tra mạng phía callee"));
    }

    private GuardrailResult check(AiAnalysis ai) {
        return guardrails.check(ai, RULE_ICE_FAIL, EVIDENCE, METRICS, GROUNDING);
    }

    private static List<String> codes(GuardrailResult r) {
        return r.violations().stream().map(Violation::code).toList();
    }

    @Test
    @DisplayName("câu trả lời hợp lệ, khớp rule -> PASSED, giữ nguyên kết luận")
    void validAnswerPasses() {
        GuardrailResult r = check(ai("FAIL", "ICE_FAILURE", List.of("EV01", "EV03"),
                "Thiết lập mất 1505 ms, MOS caller 4,35 nhưng ICE failed."));

        assertThat(r.status()).isEqualTo(Status.PASSED);
        assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(r.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(r.violations()).isEmpty();
    }

    @Test
    @DisplayName("G01 — evidence ID không tồn tại -> REJECTED")
    void g01UnknownEvidenceIdIsRejected() {
        GuardrailResult r = check(ai("FAIL", "ICE_FAILURE", List.of("EV01", "EV99"), "ICE failed."));

        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(codes(r)).containsExactly("G01");
        assertThat(r.violations().getFirst().detail()).contains("EV99");
    }

    @Test
    @DisplayName("G02 — thiếu summary, FAIL không có category, kết luận không trích evidence -> REJECTED")
    void g02MalformedAnswerIsRejected() {
        GuardrailResult r = check(new AiAnalysis("FAIL", false, "NONE", "HIGH", " ",
                List.of(), "ICE failed.", List.of()));

        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(codes(r)).containsOnly("G02").hasSize(3);
    }

    @Test
    @DisplayName("G02 — AI không trả gì -> REJECTED")
    void g02NullAnswerIsRejected() {
        assertThat(check(null).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("G03 — verdict / issueCategory ngoài taxonomy -> REJECTED")
    void g03OutOfTaxonomyIsRejected() {
        GuardrailResult r = check(ai("PARTIAL_SUCCESS", "CODEC_FAILURE", List.of("EV01"), "ICE failed."));

        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(codes(r)).containsExactly("G03", "G03");
    }

    @Test
    @DisplayName("G03 — viết thường cũng là ngoài taxonomy, không tự sửa hộ AI")
    void g03LowercaseIsNotAccepted() {
        assertThat(check(ai("fail", "ICE_FAILURE", List.of("EV01"), "ICE failed.")).status())
                .isEqualTo(Status.REJECTED);
    }

    @Test
    @DisplayName("G04 — AI kết luận khác rule -> FLAGGED, verdict cuối là UNKNOWN")
    void g04VerdictMismatchBecomesUnknown() {
        GuardrailResult r = check(new AiAnalysis("SUCCESS", false, "NONE", "HIGH",
                "Cuộc gọi bình thường.", List.of("EV01"), "Không thấy lỗi.", List.of()));

        assertThat(r.status()).isEqualTo(Status.FLAGGED);
        assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(codes(r)).containsExactly("G04");
    }

    @Test
    @DisplayName("G04 — cùng verdict khác category -> FLAGGED, giữ category của rule (tất định)")
    void g04CategoryMismatchKeepsRuleCategory() {
        GuardrailResult r = check(ai("FAIL", "TURN_FAILURE", List.of("EV01"), "TURN lỗi."));

        assertThat(r.status()).isEqualTo(Status.FLAGGED);
        assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(r.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
    }

    @Test
    @DisplayName("G05 — số liệu không có trong bộ chỉ số (packet loss 12 % khi chỉ số là N/A) -> REJECTED")
    void g05InventedNumberIsRejected() {
        GuardrailResult r = check(ai("FAIL", "ICE_FAILURE", List.of("EV01"),
                "Packet loss lên tới 12 %, MOS 4.35."));

        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(codes(r)).containsExactly("G05");
        assertThat(r.violations().getFirst().detail()).contains("12");
    }

    @Test
    @DisplayName("G05 — số có trong evidence (mã 428), ID dạng EV02 và Call-ID không bị coi là số bịa")
    void g05AllowsNumbersFromContextAndIgnoresIdentifiers() {
        GuardrailResult r = check(ai("FAIL", "ICE_FAILURE", List.of("EV02"),
                "Theo EV02 server trả mã 428 cho cuộc 2D9057AA; thiết lập 1505 ms."));

        assertThat(r.status()).isEqualTo(Status.PASSED);
    }

    @Test
    @DisplayName("G05 — đổi đơn vị (1505 ms thành 1,5 giây) cũng là số không có trong input")
    void g05RejectsUnitConversion() {
        GuardrailResult r = check(ai("FAIL", "ICE_FAILURE", List.of("EV01"), "Thiết lập mất 1,5 giây."));

        assertThat(r.status()).isEqualTo(Status.REJECTED);
        assertThat(codes(r)).containsExactly("G05");
    }
}
