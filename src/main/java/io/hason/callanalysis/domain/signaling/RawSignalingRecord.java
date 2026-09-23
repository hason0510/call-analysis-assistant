package io.hason.callanalysis.domain.signaling;

/**
 * Một event signaling ở dạng THÔ, đúng 12 trường quan sát được trong ai20k_sample.
 *
 * {@code timestamp} cố tình giữ nguyên chuỗi gốc thay vì parse sẵn thành Instant:
 * giá trị có 9 chữ số thập phân (2026-09-21T08:44:28.953756952Z) và việc chuẩn hoá
 * là việc của tầng domain (T3), không phải của adapter Elasticsearch.
 */
public record RawSignalingRecord(
        String callId,
        int ordinal,
        String timestamp,
        String service,
        String level,
        String cmd,
        String csid,
        String requestId,
        String appUserId,
        String callSessionId,
        String isp,
        String asn,
        String countryCode,
        Integer latencyMs
) {}
