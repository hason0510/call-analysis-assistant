package io.hason.callanalysis.domain.signaling;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;
import io.hason.callanalysis.domain.parse.ParseResult;
import io.hason.callanalysis.domain.parse.ParseWarning;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chuẩn hoá bản ghi signaling thô (lấy từ Elasticsearch) sang canonical event.
 *
 * Lớp này thuần: không chạm Elasticsearch, nên test được bằng RawSignalingRecord
 * dựng tay. Adapter chỉ làm việc cơ học là cố ý.
 */
public class SignalingNormalizer {

    /**
     * Nguồn trong SourceRef của event signaling. Signaling không đến từ file người dùng đính
     * kèm mà từ Elasticsearch, nên vị trí là thứ tự sự kiện, trích dẫn dạng `signaling#87`
     * (xem SourceRef.SIGNALING). Không dùng tên `signaling.json`: người đọc sẽ mở file đó
     * và tìm nhầm ở dòng 87.
     */
    private static final String SOURCE = SourceRef.SIGNALING;

    public ParseResult normalize(SignalingFetch fetch) {
        if (fetch == null) {
            return ParseResult.empty();
        }
        return normalize(fetch, LegAssignment.fromFirstInitCall(fetch.records()));
    }

    public ParseResult normalize(SignalingFetch fetch, LegAssignment legs) {
        if (fetch == null || fetch.isEmpty()) {
            return ParseResult.empty();
        }

        List<CanonicalEvent> events = new ArrayList<>(fetch.records().size());
        List<ParseWarning> warnings = new ArrayList<>();

        for (RawSignalingRecord record : fetch.records()) {
            Instant instant = parseTimestamp(record.timestamp());
            if (instant == null) {
                warnings.add(new ParseWarning(SOURCE, record.ordinal() + 1,
                        "timestamp không đọc được: " + record.timestamp()));
                continue;
            }
            events.add(toEvent(record, instant, legs, fetch.callId()));
        }

        if (fetch.truncated()) {
            warnings.add(new ParseWarning(SOURCE, 0,
                    "bản export signaling bị cắt bớt: trả về " + fetch.returned()
                            + "/" + fetch.totalMatching() + " event, thiếu " + fetch.missingCount()));
        }

        return ParseResult.of(events, warnings);
    }

    private CanonicalEvent toEvent(RawSignalingRecord r, Instant instant,
                                   LegAssignment legs, String callId) {
        Map<String, String> attributes = new LinkedHashMap<>();
        put(attributes, "service", r.service());
        put(attributes, "csid", r.csid());
        put(attributes, "requestId", r.requestId());
        put(attributes, "appUserId", r.appUserId());
        put(attributes, "callSessionId", r.callSessionId());
        put(attributes, "isp", r.isp());
        put(attributes, "asn", r.asn());
        put(attributes, "countryCode", r.countryCode());
        if (r.latencyMs() != null) {
            attributes.put("latencyMs", String.valueOf(r.latencyMs()));
        }

        return new CanonicalEvent(
                SOURCE + "#" + (r.ordinal() + 1),
                callId,
                legs.legOf(r.appUserId()),
                LogSource.SIGNALING,
                EventTime.absolute(instant, ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND,
                r.cmd() == null ? "UNKNOWN" : r.cmd(),
                attributes,
                severityOf(r.level()),
                // Vị trí = ordinal + 1: sự kiện thứ N (đếm từ 1) trong mảng `events` của bản
                // export, cũng là ordinal N - 1 trong index signaling-events.
                new SourceRef(SOURCE, r.ordinal() + 1, r.timestamp() + " " + r.cmd()));
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Signaling chỉ sinh INFO và WARN — ERROR chưa từng xuất hiện trong data mẫu.
     * WARN có ở cả cuộc gọi thành công (223/1059 event) nên KHÔNG được coi là tín hiệu lỗi.
     */
    private static Severity severityOf(String level) {
        if (level == null) {
            return Severity.INFO;
        }
        return switch (level.toUpperCase()) {
            case "WARN", "WARNING" -> Severity.WARN;
            case "ERROR", "FATAL" -> Severity.ERROR;
            default -> Severity.INFO;
        };
    }

    /** Timestamp có 9 chữ số thập phân; Instant.parse giữ nguyên độ chính xác nano. */
    private static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
