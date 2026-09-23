package io.hason.callanalysis.domain.security;

import java.util.List;
import java.util.regex.Pattern;

/** Mot muc trong sensitive-data-inventory.yaml. */
public record SensitiveField(
        String id,
        List<String> fieldNames,
        Pattern valuePattern,
        DataClassification classification,
        HandlingPolicy policy,
        String rationale,
        Origin origin
) {

    public SensitiveField {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
    }

    /** Muc den tu danh sach goc cua de bai hay tu viec ra soat data mau. */
    public enum Origin {
        MVP_5_2,
        SAMPLE_REVIEW
    }

    public boolean matchesFieldName(String name) {
        return name != null && fieldNames.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }

    public boolean matchesValue(String value) {
        return valuePattern != null && value != null && valuePattern.matcher(value).find();
    }

    /** Du lieu KHONG duoc gui sang AI duoi dang goc. */
    public boolean mustNotReachAi() {
        return policy == HandlingPolicy.DROP
                || classification == DataClassification.SECRET;
    }
}
