package io.hason.callanalysis.domain.taxonomy;

import java.util.List;

/** Mot muc trong taxonomy.yaml. */
public record IssueDefinition(
        IssueCategory id,
        String definition,
        List<String> symptoms,
        List<RequiredEvidence> requiredEvidence,
        List<String> detectionConditions,
        String knownAmbiguity,
        Calibration calibration
) {

    public IssueDefinition {
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
        detectionConditions = detectionConditions == null ? List.of() : List.copyOf(detectionConditions);
    }

    public record RequiredEvidence(String source, String field) {}

    /** Dieu kien phat hien da kiem chung tren data hay chua. */
    public record Calibration(Status status, String evidence) {
        public enum Status { VALIDATED, UNVALIDATED }

        public boolean isValidated() {
            return status == Status.VALIDATED;
        }
    }
}
