package io.hason.callanalysis.domain.evaluation;

import java.util.List;

/**
 * Kết quả chấm một lượt Evaluation Runner: các metric MVP mục 6.5 và bảng AI vs Rule.
 *
 * @param caseErrors case không chạy được (file case sai, không tìm thấy file log) — nêu tên, không bỏ qua im lặng
 */
public record EvaluationSummary(int cases, int requests, int repeat, List<Metric> metrics,
                                List<AiRuleRow> aiVsRule, List<ViolationRow> violations, List<String> caseErrors) {

    public EvaluationSummary {
        metrics = List.copyOf(metrics);
        aiVsRule = List.copyOf(aiVsRule);
        violations = List.copyOf(violations);
        caseErrors = List.copyOf(caseErrors);
    }

    /**
     * Một vi phạm Guardrails của một lần chạy — để biết AI sai gì (số bịa nào, evidence nào không có),
     * không chỉ biết là có sai.
     */
    public record ViolationRow(String caseId, int questionIndex, int repeat, String violation) {}

    /**
     * Một dòng của bảng metric.
     *
     * @param value       "92.3%" hoặc "N/A (lý do)" — không bao giờ để trống hay về 0 khi không đo được
     * @param numerator   null khi metric không phải tỉ lệ hoặc N/A
     * @param level       "Bắt buộc" / "Báo cáo" theo cột "Mức đạt" của mục 6.5
     * @param note        cách đo cụ thể, số tách theo nguồn…
     */
    public record Metric(String name, String value, Integer numerator, Integer denominator,
                         String target, String level, String note) {}

    /**
     * Một dòng của bảng AI vs Rule (mục 6.5). Mỗi cột là phân bố qua mọi lần chạy của case, ví dụ
     * "FAIL" khi mọi lần như nhau, "FAIL ×14, UNKNOWN ×1" khi không.
     */
    public record AiRuleRow(String caseId, String ruleVerdict, String aiVerdict, String finalVerdict,
                            String groundTruth) {}
}
