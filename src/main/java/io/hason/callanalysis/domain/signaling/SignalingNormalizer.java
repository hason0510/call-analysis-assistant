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
 * Chuan hoa ban ghi signaling tho (lay tu Elasticsearch) sang canonical event.
 *
 * Lop nay thuan: khong cham Elasticsearch, nen test duoc bang RawSignalingRecord
 * dung tay. Adapter chi lam viec co hoc la co y.
 */
public class SignalingNormalizer {

    /** Ten file ao dung cho SourceRef — signaling khong den tu file nguoi dung dinh kem. */
    private static final String VIRTUAL_FILE = "signaling.json";

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
                warnings.add(new ParseWarning(VIRTUAL_FILE, record.ordinal() + 1,
                        "timestamp khong doc duoc: " + record.timestamp()));
                continue;
            }
            events.add(toEvent(record, instant, legs, fetch.callId()));
        }

        if (fetch.truncated()) {
            warnings.add(new ParseWarning(VIRTUAL_FILE, 0,
                    "ban export signaling bi cat bot: tra ve " + fetch.returned()
                            + "/" + fetch.totalMatching() + " event, thieu " + fetch.missingCount()));
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
                VIRTUAL_FILE + "#" + r.ordinal(),
                callId,
                legs.legOf(r.appUserId()),
                LogSource.SIGNALING,
                EventTime.absolute(instant, ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND,
                r.cmd() == null ? "UNKNOWN" : r.cmd(),
                attributes,
                severityOf(r.level()),
                // ordinal + 1 de so dong bat dau tu 1, thong nhat voi cac parser file
                new SourceRef(VIRTUAL_FILE, r.ordinal() + 1, r.timestamp() + " " + r.cmd()));
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * Signaling chi sinh INFO va WARN — ERROR chua tung xuat hien trong data mau.
     * WARN co o ca cuoc goi thanh cong (223/1059 event) nen KHONG duoc coi la tin hieu loi.
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

    /** Timestamp co 9 chu so thap phan; Instant.parse giu nguyen do chinh xac nano. */
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
