package io.hason.callanalysis.domain.signaling;

/**
 * Mot event signaling o dang THO, dung 12 truong quan sat duoc trong ai20k_sample.
 *
 * {@code timestamp} co tinh giu nguyen chuoi goc thay vi parse san thanh Instant:
 * gia tri co 9 chu so thap phan (2026-09-21T08:44:28.953756952Z) va viec chuan hoa
 * la viec cua tang domain (T3), khong phai cua adapter Elasticsearch.
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
