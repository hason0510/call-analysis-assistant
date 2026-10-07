package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.evaluation.EvaluationSummary.Metric;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScorerTest {

    private final EvaluationScorer scorer = new EvaluationScorer();

    private static BenchmarkCase.Question q(Intent expected) {
        return new BenchmarkCase.Question("q", expected);
    }

    private static BenchmarkCase failCase(String id, IssueCategory category, BenchmarkCase.Question... questions) {
        return new BenchmarkCase(id, "CALL", List.of("caller_endcall.log"), List.of(questions), Verdict.FAIL,
                null, category, null, "dev");
    }

    /** Lần chạy ra report; AI trả lời khi aiVerdict khác null. */
    private static EvaluationRun report(String caseId, int qi, int rep, Intent expected, Verdict verdict,
                                        IssueCategory category, String aiVerdict, List<String> violations,
                                        String fallbackReason, long latency) {
        return new EvaluationRun(caseId, qi, "q", rep, expected, null, "REPORT", Intent.ANALYZE_CALL,
                aiVerdict == null ? "KEYWORD" : "AI", verdict, false, category, "MEDIUM", Verdict.FAIL,
                IssueCategory.ICE_FAILURE, aiVerdict, null, fallbackReason == null ? "AI" : "RULE",
                fallbackReason != null, fallbackReason, false, violations, true, true, 10, Map.of(), Map.of(),
                aiVerdict == null ? null : new TokenUsage(600, 10),
                aiVerdict == null ? null : new TokenUsage(3000, 200), latency);
    }

    private static EvaluationRun refused(String caseId, int qi, int rep, Intent expected) {
        return new EvaluationRun(caseId, qi, "q", rep, expected, null, "OUT_OF_SCOPE", Intent.OUT_OF_SCOPE, "AI",
                null, null, null, null, null, null, null, null, null, false, null, false, List.of(), null, null,
                10, Map.of(), Map.of(), new TokenUsage(600, 10), null, 1);
    }

    private static Metric metric(EvaluationSummary s, String name) {
        return s.metrics().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Verdict Accuracy tính theo lần chạy; câu OUT_OF_SCOPE không tính")
    void verdictAccuracyPerRun() {
        BenchmarkCase c = failCase("c1", null, q(null), q(Intent.OUT_OF_SCOPE));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, "FAIL", List.of(), null, 100),
                report("c1", 1, 2, null, Verdict.UNKNOWN, null, "UNKNOWN", List.of(), null, 100),
                refused("c1", 2, 1, Intent.OUT_OF_SCOPE));

        Metric m = metric(scorer.score(List.of(c), runs, 2, List.of()), "Verdict Accuracy");

        assertThat(m.numerator()).isEqualTo(1);
        assertThat(m.denominator()).isEqualTo(2);
        assertThat(m.value()).isEqualTo("50.0%");
    }

    @Test
    @DisplayName("thiếu nhãn category -> N/A kèm lý do, KHÔNG phải 0%")
    void missingCategoryLabelIsNotAvailable() {
        BenchmarkCase c = failCase("c1", null, q(null));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), "NOT_CONFIGURED", 1));

        Metric m = metric(scorer.score(List.of(c), runs, 1, List.of()), "Issue Category Accuracy");

        assertThat(m.value()).startsWith("N/A (").contains("expected_issue_category");
        assertThat(m.numerator()).isNull();
    }

    @Test
    @DisplayName("Issue Category Accuracy chấm khi có nhãn")
    void categoryAccuracyWithLabel() {
        BenchmarkCase c = failCase("c1", IssueCategory.ICE_FAILURE, q(null));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, "FAIL", List.of(), null, 1),
                report("c1", 1, 2, null, Verdict.FAIL, IssueCategory.TURN_FAILURE, "FAIL", List.of(), null, 1));

        Metric m = metric(scorer.score(List.of(c), runs, 2, List.of()), "Issue Category Accuracy");

        assertThat(m.value()).isEqualTo("50.0%");
    }

    @Test
    @DisplayName("Consistency: lần chạy trùng kết quả số đông của case; case 1 lần chạy không tính")
    void consistencyAgainstModalOutcome() {
        BenchmarkCase stable = failCase("a", null, q(null));
        BenchmarkCase shaky = failCase("b", null, q(null), q(null));
        BenchmarkCase single = failCase("c", null, q(null));
        List<EvaluationRun> runs = new ArrayList<>();
        runs.add(report("a", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));
        runs.add(report("a", 1, 2, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));
        runs.add(report("b", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));
        runs.add(report("b", 1, 2, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));
        runs.add(report("b", 2, 1, null, Verdict.FAIL, IssueCategory.TURN_FAILURE, null, List.of(), null, 1));
        runs.add(report("b", 2, 2, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));
        runs.add(report("c", 1, 1, null, Verdict.FAIL, IssueCategory.ICE_FAILURE, null, List.of(), null, 1));

        Metric m = metric(scorer.score(List.of(stable, shaky, single), runs, 2, List.of()), "Consistency");

        assertThat(m.numerator()).isEqualTo(5);                    // a: 2/2, b: 3/4
        assertThat(m.denominator()).isEqualTo(6);
        assertThat(m.note()).contains("1/2 case giống hệt mọi lần");
    }

    @Test
    @DisplayName("Intent Accuracy tách theo nguồn phân loại; câu không có nhãn không tính")
    void intentAccuracyBySource() {
        BenchmarkCase c = failCase("c1", null, q(Intent.ANALYZE_CALL), q(Intent.OUT_OF_SCOPE), q(null));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, Intent.ANALYZE_CALL, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", 1),
                refused("c1", 2, 1, Intent.OUT_OF_SCOPE),
                report("c1", 3, 1, null, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", 1));

        Metric m = metric(scorer.score(List.of(c), runs, 1, List.of()), "Intent Accuracy");

        assertThat(m.value()).isEqualTo("100.0%");
        assertThat(m.denominator()).isEqualTo(2);
        assertThat(m.note()).contains("AI 1/1", "KEYWORD 1/1", "1 lần chạy câu OUT_OF_SCOPE");
    }

    @Test
    @DisplayName("Unsupported Claim: đếm G01/G05 trên đầu ra AI trước Guardrails; G04 không tính")
    void unsupportedClaims() {
        BenchmarkCase c = failCase("c1", null, q(null));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, null, "FAIL",
                        List.of("G05: số liệu không có trong input: 8.6"), "GUARDRAIL_REJECTED", 1),
                report("c1", 1, 2, null, Verdict.UNKNOWN, null, "SUCCESS",
                        List.of("G04: AI kết luận SUCCESS, rule kết luận FAIL"), null, 1),
                report("c1", 1, 3, null, Verdict.FAIL, null, "FAIL", List.of(), null, 1),
                report("c1", 1, 4, null, Verdict.FAIL, null, null, List.of(), "TIMEOUT", 1));

        EvaluationSummary s = scorer.score(List.of(c), runs, 4, List.of());
        Metric raw = metric(s, "Unsupported Claim Rate (AI trước Guardrails)");
        Metric shown = metric(s, "Unsupported Claim Rate (report cuối)");

        assertThat(raw.numerator()).isEqualTo(1);
        assertThat(raw.denominator()).isEqualTo(3);                // lần TIMEOUT: AI không trả lời
        assertThat(shown.numerator()).isZero();                    // lần G05 đã lùi về rule
        assertThat(shown.denominator()).isEqualTo(2);              // chỉ report dùng phần phân tích của AI
        assertThat(s.violations()).extracting(EvaluationSummary.ViolationRow::violation)
                .containsExactly("G05: số liệu không có trong input: 8.6", "G04: AI kết luận SUCCESS, rule kết luận FAIL");
    }

    @Test
    @DisplayName("mã vi phạm tách từ chi tiết; G04 không phải unsupported claim")
    void violationCodesFromDetails() {
        EvaluationRun r = report("c1", 1, 1, null, Verdict.FAIL, null, "FAIL",
                List.of("G01: evidence ID không tồn tại: EV99", "G01: evidence ID không tồn tại: EV98", "G04: x"),
                "GUARDRAIL_REJECTED", 1);

        assertThat(r.violationCodes()).containsExactly("G01", "G04");
        assertThat(r.hasUnsupportedClaim()).isTrue();
        assertThat(report("c1", 1, 1, null, Verdict.FAIL, null, "FAIL", List.of("G04: x"), null, 1)
                .hasUnsupportedClaim()).isFalse();
    }

    @Test
    @DisplayName("lần chạy lỗi tính vào Pipeline Success Rate; latency chỉ trên lần ra report")
    void pipelineSuccessAndLatency() {
        BenchmarkCase c = failCase("c1", null, q(null));
        List<EvaluationRun> runs = new ArrayList<>();
        for (int i = 1; i <= 19; i++) {
            runs.add(report("c1", 1, i, null, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", i * 10L));
        }
        runs.add(new EvaluationRun("c1", 1, null, 20, null, "IllegalStateException", null, null, null, null, null,
                null, null, null, null, null, null, null, false, null, false, List.of(), null, null, 10, null, null, null, null, 5));

        EvaluationSummary s = scorer.score(List.of(c), runs, 20, List.of());

        assertThat(metric(s, "Pipeline Success Rate").value()).isEqualTo("95.0%");     // lần lỗi: thất bại
        assertThat(metric(s, "Latency").value()).isEqualTo("P50 100 ms, P95 190 ms");
        assertThat(metric(s, "Fallback (degraded)").note()).contains("NOT_CONFIGURED ×19");
    }

    @Test
    @DisplayName("Pipeline Success Rate: chỉ câu phân tích; bị từ chối nhầm là thất bại, câu OUT_OF_SCOPE không tính")
    void pipelineSuccessOnlyAnalysisQuestions() {
        BenchmarkCase c = failCase("c1", null, q(Intent.ANALYZE_CALL), q(Intent.ANALYZE_CALL), q(Intent.OUT_OF_SCOPE));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, Intent.ANALYZE_CALL, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", 1),
                refused("c1", 2, 1, Intent.ANALYZE_CALL),
                refused("c1", 3, 1, Intent.OUT_OF_SCOPE));

        Metric m = metric(scorer.score(List.of(c), runs, 1, List.of()), "Pipeline Success Rate");

        assertThat(m.numerator()).isEqualTo(1);
        assertThat(m.denominator()).isEqualTo(2);
    }

    @Test
    @DisplayName("Security Leakage: đầu vào = lộ ÷ phát hiện, đầu ra = số giá trị lộ; chỉ in loại, không in giá trị")
    void leakageMetrics() {
        BenchmarkCase c = failCase("c1", null, q(null));
        EvaluationRun clean = report("c1", 1, 1, null, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", 1);
        EvaluationRun leaky = new EvaluationRun("c1", 1, "q", 2, null, null, "REPORT", Intent.ANALYZE_CALL, "AI",
                Verdict.FAIL, false, null, "MEDIUM", Verdict.FAIL, null, null, null, "RULE", true, "NOT_CONFIGURED",
                false, List.of(), true, true, 10, Map.of("ip", 1), Map.of("userId", 2), null, null, 1);

        EvaluationSummary s = scorer.score(List.of(c), List.of(clean, leaky), 2, List.of());

        Metric input = metric(s, "Security Leakage (input)");
        assertThat(input.numerator()).isEqualTo(1);
        assertThat(input.denominator()).isEqualTo(20);
        assertThat(input.value()).isEqualTo("5.0%");
        assertThat(input.note()).contains("lộ: ip ×1");
        Metric output = metric(s, "Security Leakage (output)");
        assertThat(output.value()).isEqualTo("2");
        assertThat(output.note()).contains("lộ: userId ×2");
    }

    @Test
    @DisplayName("Token: trung bình theo từng loại lời gọi, cộng tổng; lần AI không trả lời không tính")
    void tokenMetric() {
        BenchmarkCase c = failCase("c1", null, q(null), q(Intent.OUT_OF_SCOPE));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, null, "FAIL", List.of(), null, 1),     // 600+10, 3000+200
                report("c1", 1, 2, null, Verdict.FAIL, null, null, List.of(), "TIMEOUT", 1), // AI không trả lời
                refused("c1", 2, 1, Intent.OUT_OF_SCOPE));                                    // 600+10

        Metric m = metric(scorer.score(List.of(c), runs, 2, List.of()), "Token");

        assertThat(m.value()).isEqualTo("phân tích: 3000 vào + 200 ra (1 lời gọi); phân loại: 600 vào + 10 ra (2 lời gọi)");
        assertThat(m.note()).contains("tổng 4200 vào + 220 ra trên 3 lời gọi");
    }

    @Test
    @DisplayName("bảng AI vs Rule: phân bố qua các lần chạy, '-' khi AI không trả lời")
    void aiVsRuleTable() {
        BenchmarkCase c = failCase("c1", null, q(null));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, null, Verdict.FAIL, null, "FAIL", List.of(), null, 1),
                report("c1", 1, 2, null, Verdict.UNKNOWN, null, "SUCCESS", List.of("G04"), null, 1),
                report("c1", 1, 3, null, Verdict.FAIL, null, "FAIL", List.of(), null, 1));
        BenchmarkCase noAi = failCase("c2", null, q(null));
        List<EvaluationRun> all = new ArrayList<>(runs);
        all.add(report("c2", 1, 1, null, Verdict.FAIL, null, null, List.of(), "NOT_CONFIGURED", 1));

        List<EvaluationSummary.AiRuleRow> rows = scorer.score(List.of(c, noAi), all, 3, List.of()).aiVsRule();

        assertThat(rows.get(0)).isEqualTo(new EvaluationSummary.AiRuleRow("c1", "FAIL", "FAIL ×2, SUCCESS ×1",
                "FAIL ×2, UNKNOWN ×1", "FAIL"));
        assertThat(rows.get(1).aiVerdict()).isEqualTo("-");
    }

    @Test
    @DisplayName("cùng đầu vào -> cùng báo cáo, từng ký tự")
    void deterministic() {
        BenchmarkCase c = failCase("c1", IssueCategory.ICE_FAILURE, q(Intent.ANALYZE_CALL));
        List<EvaluationRun> runs = List.of(
                report("c1", 1, 1, Intent.ANALYZE_CALL, Verdict.FAIL, IssueCategory.ICE_FAILURE, "FAIL", List.of(), null, 3));
        EvaluationReportRenderer renderer = new EvaluationReportRenderer();

        String first = renderer.render(scorer.score(List.of(c), runs, 1, List.of("x: lỗi")), java.util.Map.of());
        String second = renderer.render(scorer.score(List.of(c), runs, 1, List.of("x: lỗi")), java.util.Map.of());

        assertThat(first).isEqualTo(second).contains("| Verdict Accuracy | 100.0% | 1/1 |", "- x: lỗi",
                "## Vi phạm Guardrails");
    }
}
