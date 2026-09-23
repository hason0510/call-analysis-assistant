package io.hason.callanalysis.domain.taxonomy;

/**
 * Độ tin cậy suy ra bằng logic tất định, KHÔNG để AI tự sinh số (MVP mục 7.2).
 * Sprint 1 chỉ dùng cho rule verdict; Sprint 3 sẽ mở rộng khi có đối chiếu AI vs rule.
 */
public enum ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}
