package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Kết luận cuối sau AI + Guardrails, cùng phần chữ AI viết — thứ {@link ReportBuilder} cần để dựng
 * report. Tách khỏi kiểu của tầng service để domain không phụ thuộc cách điều phối.
 *
 * @param reviewNotes      lý do lệch rule (G04), nêu ở "Giới hạn dữ liệu" khi needsReview
 * @param citedEvidenceIds evidence AI trích làm căn cứ (đã qua Guardrails G01: ID nào cũng tồn tại)
 */
public record ReportDecision(
        CallReport.AnalysisSource source,
        Verdict verdict,
        boolean qualityFlag,
        IssueCategory issueCategory,
        boolean needsReview,
        String fallbackReason,
        String summary,
        String analysis,
        List<String> suggestions,
        List<String> reviewNotes,
        List<String> citedEvidenceIds
) {

    public ReportDecision {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        reviewNotes = reviewNotes == null ? List.of() : List.copyOf(reviewNotes);
        citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
    }

    public boolean degraded() {
        return source == CallReport.AnalysisSource.RULE && fallbackReason != null;
    }
}
