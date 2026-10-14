package io.hason.callanalysis.domain.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Report theo mẫu MVP mục 4.5. Khớp 1-1 với resources/schema/report-v1.schema.json.
 *
 * AI chỉ được điền field của record này (summary, analysis, suggestions), bố cục do code dựng
 * ({@link ReportRenderer}, MVP mục 3.2).
 *
 * @param confidenceReasons vì sao ở mức confidenceLevel (MVP mục 7.2) — do ConfidencePolicy viết, không do AI
 * @param analysis       phần phân tích của AI, ưu tiên trọng tâm câu hỏi; null khi report dựng từ rule
 * @param analysisSource AI khi kết luận đi qua AI + Guardrails; RULE khi chỉ có rule (CLI Sprint 1 hoặc fallback)
 * @param degraded       đã thử AI nhưng phải lùi về rule (MVP mục 6.1 T8)
 * @param needsReview    AI lệch rule (Guardrails G04) — "gắn cờ cần kiểm tra" (MVP mục 3.2)
 * @param fallbackReason mã lý do lùi về rule (TIMEOUT, GUARDRAIL_REJECTED…); null khi không degraded
 * @param aiSuggestions  đề xuất AI BỔ SUNG cho bộ đề xuất chuẩn của rule ({@code suggestions}); rỗng khi report dựng
 *                       từ rule hoặc AI lệch rule (MVP 7.1 T2)
 * @param citedEvidenceIds evidence AI dùng làm căn cứ cho phần phân tích (MVP mục 3.3: mọi kết luận trace
 *                         được về evidence); rỗng khi report dựng từ rule
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallReport(
        String callId,
        Verdict verdict,
        boolean qualityFlag,
        IssueCategory issueCategory,
        ConfidenceLevel confidenceLevel,
        List<String> confidenceReasons,
        String summary,
        List<EvidenceEntry> evidence,
        List<MetricEntry> metrics,
        PossibleCauses possibleCauses,
        List<String> suggestions,
        List<String> dataLimitations,
        boolean degraded,
        String analysis,
        AnalysisSource analysisSource,
        boolean needsReview,
        String fallbackReason,
        List<String> citedEvidenceIds,
        List<String> aiSuggestions
) {

    public enum AnalysisSource { AI, RULE }

    public CallReport {
        confidenceReasons = confidenceReasons == null ? List.of() : List.copyOf(confidenceReasons);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
        analysisSource = analysisSource == null ? AnalysisSource.RULE : analysisSource;
        citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
        aiSuggestions = aiSuggestions == null ? List.of() : List.copyOf(aiSuggestions);
    }

    /** Report chỉ từ rule, không thử AI (luồng CLI của Sprint 1). */
    public CallReport(String callId, Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                      ConfidenceLevel confidenceLevel, String summary, List<EvidenceEntry> evidence,
                      List<MetricEntry> metrics, PossibleCauses possibleCauses, List<String> suggestions,
                      List<String> dataLimitations, boolean degraded) {
        this(callId, verdict, qualityFlag, issueCategory, confidenceLevel, List.of(), summary, evidence, metrics,
                possibleCauses, suggestions, dataLimitations, degraded, null, AnalysisSource.RULE, false, null, List.of(),
                List.of());
    }

    public record EvidenceEntry(String id, String source, String timestamp,
                                String description, String sourceRef) {}

    /** value và naReason loại trừ nhau — schema ép bằng oneOf. */
    public record MetricEntry(String name, String value, String unit,
                              String naReason, String source) {}

    /**
     * Mục "Vấn đề chất lượng / nguyên nhân khả dĩ" trả lời ba trong bốn câu MVP 7.1 T2 đòi phần phân tích trả lời
     * được, kể cả khi không có AI: chuyện gì xảy ra ({@code primary}), vì sao kết luận vậy ({@code reasoning}), evidence
     * nào hỗ trợ ({@code basisEvidenceIds}). Câu thứ tư — cần điều tra gì tiếp — là mục Đề xuất.
     *
     * @param reasoning        vì sao kết luận vậy: lý do của rule, do code viết; null khi không có kết luận
     * @param basisEvidenceIds evidence là căn cứ của rule ({@code Evidence.ruleBasis}); rỗng khi kết luận không dựa
     *                         trên dòng lỗi nào (cuộc gọi bình thường)
     */
    public record PossibleCauses(String primary, List<String> alternatives, String reasoning,
                                 List<String> basisEvidenceIds) {
        public PossibleCauses {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
            basisEvidenceIds = basisEvidenceIds == null ? List.of() : List.copyOf(basisEvidenceIds);
        }

        public PossibleCauses(String primary, List<String> alternatives) {
            this(primary, alternatives, null, List.of());
        }
    }
}
