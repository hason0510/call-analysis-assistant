package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.evaluation.EvaluationSummary.AiRuleRow;
import io.hason.callanalysis.domain.evaluation.EvaluationSummary.Metric;

import java.util.Map;

/**
 * Báo cáo Markdown của một lượt Evaluation Runner: metric mục 6.5 + bảng AI vs Rule. Chi tiết từng lần
 * chạy nằm ở file JSON đi kèm — báo cáo này để đọc, file JSON để audit.
 */
public final class EvaluationReportRenderer {

    /** @param setup cấu hình của lượt chạy (file case, thư mục log, model…), in đúng thứ tự truyền vào */
    public String render(EvaluationSummary s, Map<String, String> setup) {
        StringBuilder out = new StringBuilder();
        line(out, "# Kết quả Evaluation Runner");
        line(out, "");
        setup.forEach((k, v) -> line(out, "- " + k + ": " + v));
        line(out, "- Case: " + s.cases() + " · lần chạy: " + s.requests() + " · lặp mỗi câu: " + s.repeat());

        line(out, "");
        line(out, "## Metric (MVP mục 6.5)");
        line(out, "");
        line(out, "| Metric | Kết quả | Số lần | Target | Mức | Cách đo |");
        line(out, "| --- | --- | --- | --- | --- | --- |");
        for (Metric m : s.metrics()) {
            String count = m.numerator() != null ? m.numerator() + "/" + m.denominator()
                    : m.denominator() != null ? String.valueOf(m.denominator()) : "-";
            line(out, "| " + cell(m.name()) + " | " + cell(m.value()) + " | " + count + " | " + cell(m.target())
                    + " | " + cell(m.level()) + " | " + cell(m.note() == null ? "" : m.note()) + " |");
        }

        line(out, "");
        line(out, "## AI vs Rule");
        line(out, "");
        line(out, "AI Verdict là đầu ra AI TRƯỚC Guardrails; Final là kết luận report cuối. \"-\": AI không trả lời"
                + " (chưa có key, lỗi, quá giờ).");
        line(out, "");
        line(out, "| Case | Rule Verdict | AI Verdict | Final | Ground Truth |");
        line(out, "| --- | --- | --- | --- | --- |");
        for (AiRuleRow r : s.aiVsRule()) {
            line(out, "| " + cell(r.caseId()) + " | " + cell(r.ruleVerdict()) + " | " + cell(r.aiVerdict()) + " | "
                    + cell(r.finalVerdict()) + " | " + cell(r.groundTruth()) + " |");
        }
        if (s.aiVsRule().isEmpty()) {
            line(out, "| - | - | - | - | - |");
        }

        line(out, "");
        line(out, "## Vi phạm Guardrails");
        line(out, "");
        line(out, "G01 / G05 là unsupported claim; G04 là AI lệch rule (gắn cờ cần kiểm tra).");
        line(out, "");
        if (s.violations().isEmpty()) {
            line(out, "- Không có.");
        }
        s.violations().forEach(v -> line(out, "- " + v.caseId() + " câu " + v.questionIndex() + " lần " + v.repeat()
                + ": " + cell(v.violation())));

        line(out, "");
        line(out, "## Case không chạy được");
        line(out, "");
        if (s.caseErrors().isEmpty()) {
            line(out, "- Không có.");
        }
        s.caseErrors().forEach(e -> line(out, "- " + e));
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    private static String cell(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ").replace("|", "\\|");
    }
}
