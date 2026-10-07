package io.hason.callanalysis.domain.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Một mục trong sensitive-data-inventory.yaml.
 *
 * @param label nhãn thay thế: [LABEL_REDACTED] khi MASK, LABEL_a81f2c khi PSEUDONYMIZE
 */
public record SensitiveField(
        String id,
        List<String> fieldNames,
        Pattern valuePattern,
        DataClassification classification,
        HandlingPolicy policy,
        String label,
        String rationale,
        Origin origin
) {

    /** Nhóm có tên bắt đầu bằng chữ này là phần bị thay; phần còn lại của chuỗi khớp được giữ. */
    public static final String VALUE_GROUP_PREFIX = "value";

    public SensitiveField {
        fieldNames = fieldNames == null ? List.of() : List.copyOf(fieldNames);
        label = label == null || label.isBlank() ? id.toUpperCase().replace('-', '_') : label;
    }

    /** Mục đến từ danh sách gốc của đề bài hay từ việc rà soát data mẫu. */
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

    /** Dữ liệu KHÔNG được gửi sang AI dưới dạng gốc. */
    public boolean mustNotReachAi() {
        return policy == HandlingPolicy.DROP
                || classification == DataClassification.SECRET;
    }

    /** Tên các nhóm `value…` của pattern, theo thứ tự tên — rỗng nếu thay cả chuỗi khớp. */
    public List<String> valueGroups() {
        if (valuePattern == null) {
            return List.of();
        }
        return valuePattern.namedGroups().keySet().stream()
                .filter(n -> n.startsWith(VALUE_GROUP_PREFIX))
                .sorted()
                .toList();
    }
}
