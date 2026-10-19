package io.hason.callanalysis.domain.taxonomy;

/**
 * Độ tin cậy suy ra bằng logic tất định, KHÔNG để AI tự sinh số (MVP mục 7.2).
 * Mức cuối do ConfidencePolicy quyết định theo bảng MVP 7.2 (độ mạnh bằng chứng, AI khớp rule, dữ liệu thiếu).
 */
public enum ConfidenceLevel {
    HIGH,
    MEDIUM,
    LOW
}
