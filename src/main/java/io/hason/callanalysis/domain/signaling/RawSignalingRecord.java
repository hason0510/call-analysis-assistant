package io.hason.callanalysis.domain.signaling;

/**
 * Một event signaling ở dạng THÔ: các trường quan sát được trong hai định dạng export (ai20k_sample, ai20k_new_dataset).
 *
 * {@code timestamp} cố tình giữ nguyên chuỗi gốc thay vì parse sẵn thành Instant:
 * giá trị có 9 chữ số thập phân (2026-09-21T08:44:28.953756952Z) và việc chuẩn hoá
 * là việc của tầng domain (T3), không phải của adapter Elasticsearch.
 *
 * @param noSessionsFound cờ "No sessions found" đọc từ {@code message} lúc import ({@link SignalingMessages});
 *                        null khi bản export không có {@code message} (bản gọn) hoặc index nạp trước khi có trường này
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
        Integer latencyMs,
        Boolean noSessionsFound
) {

    /** Bản ghi không có cờ "No sessions found" — bản export gọn, như mọi file của ai20k_sample. */
    public RawSignalingRecord(String callId, int ordinal, String timestamp, String service, String level, String cmd,
                              String csid, String requestId, String appUserId, String callSessionId, String isp,
                              String asn, String countryCode, Integer latencyMs) {
        this(callId, ordinal, timestamp, service, level, cmd, csid, requestId, appUserId, callSessionId, isp, asn,
                countryCode, latencyMs, null);
    }
}
