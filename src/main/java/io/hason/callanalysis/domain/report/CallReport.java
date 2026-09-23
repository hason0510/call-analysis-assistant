package io.hason.callanalysis.domain.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Report theo mẫu MVP mục 4.5. Khớp 1-1 với resources/schema/report-v1.schema.json.
 *
 * Sprint 2: AI chỉ được điền field của record này, bố cục do code dựng (MVP mục 3.2).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CallReport(
        String callId,
        Verdict verdict,
        boolean qualityFlag,
        IssueCategory issueCategory,
        ConfidenceLevel confidenceLevel,
        String summary,
        List<EvidenceEntry> evidence,
        List<MetricEntry> metrics,
        PossibleCauses possibleCauses,
        List<String> suggestions,
        List<String> dataLimitations,
        boolean degraded
) {

    public CallReport {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
    }

    public record EvidenceEntry(String id, String source, String timestamp,
                                String description, String sourceRef) {}

    /** value và naReason loại trừ nhau — schema ép bằng oneOf. */
    public record MetricEntry(String name, String value, String unit,
                              String naReason, String source) {}

    public record PossibleCauses(String primary, List<String> alternatives) {
        public PossibleCauses {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }
}
