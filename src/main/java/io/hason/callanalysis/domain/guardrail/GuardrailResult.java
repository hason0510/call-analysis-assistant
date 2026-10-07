package io.hason.callanalysis.domain.guardrail;

import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Kết quả kiểm đầu ra AI.
 *
 * - PASSED: dùng được nguyên vẹn.
 * - FLAGGED: hợp lệ về hình thức nhưng lệch rule; verdict/issueCategory ở đây là giá trị ĐÃ
 *   điều chỉnh (lệch verdict → UNKNOWN), report phải gắn cờ "cần kiểm tra".
 * - REJECTED: không dùng được; bên gọi fallback về rule verdict. verdict/issueCategory là null.
 */
public record GuardrailResult(Status status, Verdict verdict, boolean qualityFlag,
                              IssueCategory issueCategory, List<Violation> violations) {

    public enum Status { PASSED, FLAGGED, REJECTED }

    /** Mã ca kiểm thử MVP mục 6.4 kèm mô tả, để report và Evaluation Runner đếm được theo loại. */
    public record Violation(String code, String detail) {
        @Override
        public String toString() {
            return code + ": " + detail;
        }
    }

    public GuardrailResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean usable() {
        return status != Status.REJECTED;
    }
}
