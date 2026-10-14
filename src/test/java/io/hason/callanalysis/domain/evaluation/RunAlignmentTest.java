package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunAlignmentTest {

    private static BenchmarkCase failCase(String id, IssueCategory category, BenchmarkCase.Question... questions) {
        return new BenchmarkCase(id, "CALL", List.of("caller_endcall.log"), List.of(questions), Verdict.FAIL,
                null, category, null, "dev");
    }

    private static BenchmarkCase.Question q(Intent expected) {
        return new BenchmarkCase.Question("q", expected);
    }

    private static EvaluationRun run(String caseId, int qi, Intent expected) {
        return new EvaluationRun(caseId, qi, "q", 1, expected, null, "REPORT", Intent.ANALYZE_CALL, "AI",
                Verdict.FAIL, false, IssueCategory.ICE_FAILURE, "MEDIUM", Verdict.FAIL, IssueCategory.ICE_FAILURE,
                "FAIL", "ICE_FAILURE", "AI", false, null, false, List.of(), true, true, 0, Map.of(), Map.of(),
                Map.of(), null, null, null, null, 100);
    }

    @Test
    @DisplayName("nhãn mới trong file case được dùng để chấm — Issue Category Accuracy hết N/A mà không chạy lại")
    void newLabelsAreScored() {
        List<EvaluationRun> stored = List.of(run("c1", 1, null), run("c1", 1, null));
        BenchmarkCase relabelled = failCase("c1", IssueCategory.ICE_FAILURE, q(null));

        RunAlignment.Aligned a = RunAlignment.align(List.of(relabelled), stored, List.of());
        EvaluationSummary s = new EvaluationScorer().score(a.cases(), a.runs(), 2, a.errors());

        EvaluationSummary.Metric category = s.metrics().stream()
                .filter(m -> m.name().equals("Issue Category Accuracy")).findFirst().orElseThrow();
        assertThat(category.value()).isEqualTo("100.0%");
        assertThat(a.errors()).isEmpty();
    }

    @Test
    @DisplayName("nhãn intent lấy từ file case hiện tại, không từ lúc chạy")
    void intentLabelComesFromCurrentCaseFile() {
        RunAlignment.Aligned a = RunAlignment.align(List.of(failCase("c1", null, q(Intent.ANALYZE_WITH_FOCUS))),
                List.of(run("c1", 1, null)), List.of());

        assertThat(a.runs()).singleElement()
                .extracting(EvaluationRun::expectedIntent).isEqualTo(Intent.ANALYZE_WITH_FOCUS);
    }

    @Test
    @DisplayName("lần chạy không ghép được thì nêu tên, không chấm và không bỏ qua im lặng")
    void unmatchedRunsAreReported() {
        List<EvaluationRun> stored = List.of(run("c1", 1, null), run("c1", 3, null), run("gone", 1, null),
                run("gone", 1, null));
        List<BenchmarkCase> cases = List.of(failCase("c1", null, q(null), q(null)), failCase("new", null, q(null)));

        RunAlignment.Aligned a = RunAlignment.align(cases, stored, List.of());

        assertThat(a.runs()).hasSize(1);
        assertThat(a.cases()).extracting(BenchmarkCase::caseId).containsExactly("c1");
        assertThat(a.errors()).containsExactly(
                "gone: có 2 lần chạy nhưng không còn trong file case — không chấm",
                "c1 câu 3: file case chỉ còn 2 câu — không chấm",
                "new: chưa có lần chạy nào trong file kết quả — chạy --evaluate để chấm");
    }

    @Test
    @DisplayName("case đã bị bỏ qua lúc chạy giữ đúng lỗi gốc, không nêu lại lần hai")
    void previousErrorsKeptOnce() {
        String skipped = "c2: không tìm thấy thư mục log — đã bỏ qua case này";

        RunAlignment.Aligned a = RunAlignment.align(List.of(failCase("c1", null, q(null)), failCase("c2", null, q(null))),
                List.of(run("c1", 1, null)), List.of(skipped));

        assertThat(a.errors()).containsExactly(skipped);
    }
}
