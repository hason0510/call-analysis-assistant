package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Một case của benchmark, đúng các trường mẫu MVP mục 6.3.
 *
 * Trường ground truth nào null là "chưa có nhãn": runner KHÔNG chấm trường đó và báo metric tương
 * ứng là N/A — không mặc định về một giá trị nào (cùng nguyên tắc N/A của MVP mục 4.3).
 *
 * @param files                 tên file trần (tìm trong thư mục trùng {@code callId}) hoặc đường dẫn
 *                              tương đối tới thư mục log gốc
 * @param expectedVerdict       null với case chỉ có câu OUT_OF_SCOPE: không chạy pipeline thì không có verdict
 * @param expectedQualityFlag   null khi ground truth không ghi
 * @param expectedIssueCategory null khi ground truth không ghi
 * @param expectedEvidence      null khi ground truth không ghi
 */
public record BenchmarkCase(String caseId, String callId, List<String> files, List<Question> questions,
                            Verdict expectedVerdict, Boolean expectedQualityFlag,
                            IssueCategory expectedIssueCategory, List<String> expectedEvidence, String split) {

    public BenchmarkCase {
        files = files == null ? List.of() : List.copyOf(files);
        questions = questions == null ? List.of() : List.copyOf(questions);
        expectedEvidence = expectedEvidence == null ? null : List.copyOf(expectedEvidence);
    }

    /**
     * Một cách hỏi. Mẫu 6.3 chỉ có chuỗi câu hỏi; {@code expectedIntent} là phần mở rộng tuỳ chọn để đo
     * Intent Accuracy (mục 6.5) — null thì câu này không tính vào Intent Accuracy.
     */
    public record Question(String text, Intent expectedIntent) {

        /** Câu hỏi phân tích (không bị đánh dấu OUT_OF_SCOPE) — tính vào Verdict Accuracy, Consistency. */
        public boolean expectsAnalysis() {
            return expectedIntent != Intent.OUT_OF_SCOPE;
        }
    }

    /**
     * Case được chấm Issue Category Accuracy: MVP mục 6.5 chỉ tính case FAIL và SUCCESS có cờ chất
     * lượng, và chỉ khi ground truth có ghi category.
     */
    public boolean categoryScored() {
        return expectedIssueCategory != null && (expectedVerdict == Verdict.FAIL
                || (expectedVerdict == Verdict.SUCCESS && Boolean.TRUE.equals(expectedQualityFlag)));
    }
}
