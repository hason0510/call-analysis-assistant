package io.hason.callanalysis.domain.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ghép các lần chạy đã lưu (file .json của Evaluation Runner) với file case HIỆN TẠI để chấm lại mà không gọi
 * lại AI — dùng khi có nhãn mới (mentor bổ sung expected_issue_category) hoặc khi đổi cách tính metric.
 *
 * Chỉ đáp án được lấy từ file case hiện tại; câu trả lời của hệ thống giữ nguyên như lúc chạy. Vì vậy câu hỏi
 * phải giữ đúng thứ tự: lần chạy được ghép theo {@code caseId} + số thứ tự câu. Ghép không được thì nêu tên,
 * không bỏ qua im lặng.
 */
public final class RunAlignment {

    /**
     * @param cases  case có ít nhất một lần chạy ghép được, theo thứ tự file case
     * @param runs   lần chạy ghép được, nhãn intent lấy từ file case hiện tại
     * @param errors lỗi case của lượt chạy gốc, rồi các lần chạy / case không ghép được
     */
    public record Aligned(List<BenchmarkCase> cases, List<EvaluationRun> runs, List<String> errors) {}

    private RunAlignment() {
    }

    public static Aligned align(List<BenchmarkCase> cases, List<EvaluationRun> runs, List<String> previousErrors) {
        Map<String, BenchmarkCase> byId = new LinkedHashMap<>();
        cases.forEach(c -> byId.put(c.caseId(), c));

        List<EvaluationRun> aligned = new ArrayList<>();
        Map<String, Integer> missingCase = new LinkedHashMap<>();
        Set<String> missingQuestion = new LinkedHashSet<>();
        Set<String> withRuns = new LinkedHashSet<>();
        for (EvaluationRun run : runs) {
            BenchmarkCase c = byId.get(run.caseId());
            if (c == null) {
                missingCase.merge(run.caseId(), 1, Integer::sum);
            } else if (run.questionIndex() < 1 || run.questionIndex() > c.questions().size()) {
                missingQuestion.add(run.caseId() + " câu " + run.questionIndex() + ": file case chỉ còn "
                        + c.questions().size() + " câu — không chấm");
            } else {
                aligned.add(run.withExpectedIntent(c.questions().get(run.questionIndex() - 1).expectedIntent()));
                withRuns.add(c.caseId());
            }
        }

        List<String> errors = new ArrayList<>(previousErrors);
        missingCase.forEach((id, n) -> errors.add(id + ": có " + n + " lần chạy nhưng không còn trong file case — không chấm"));
        errors.addAll(missingQuestion);
        for (BenchmarkCase c : cases) {
            boolean alreadyReported = previousErrors.stream().anyMatch(e -> e.startsWith(c.caseId() + ":"));
            if (!withRuns.contains(c.caseId()) && !alreadyReported) {
                errors.add(c.caseId() + ": chưa có lần chạy nào trong file kết quả — chạy --evaluate để chấm");
            }
        }
        List<BenchmarkCase> ran = cases.stream().filter(c -> withRuns.contains(c.caseId())).toList();
        return new Aligned(ran, aligned, errors);
    }
}
