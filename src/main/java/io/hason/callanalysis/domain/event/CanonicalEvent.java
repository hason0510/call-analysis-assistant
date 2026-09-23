package io.hason.callanalysis.domain.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model chung ma ca ba nguon log deu do vao. Moi thanh phan sau (Timeline, Metrics,
 * Evidence, Rule) chi lam viec voi kieu nay.
 *
 * {@code sourceRef} la BAT BUOC: MVP muc 8.2 yeu cau moi evidence trace duoc ve dong
 * log goc, va mau report muc 4.5 in ra dang [EV05][callee_endcall.log 10:00:41.000].
 */
public record CanonicalEvent(
        String eventId,
        String callId,
        Leg leg,
        LogSource source,
        EventTime time,
        EventType type,
        String name,
        Map<String, String> attributes,
        Severity severity,
        SourceRef sourceRef
) {

    public CanonicalEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(sourceRef, "sourceRef");
        leg = leg == null ? Leg.UNKNOWN : leg;
        type = type == null ? EventType.LOG_MESSAGE : type;
        severity = severity == null ? Severity.INFO : severity;
        // LinkedHashMap giu thu tu chen: thu tu doi giua cac lan chay la mat tinh tat dinh,
        // va Consistency >= 95% (MVP muc 6.5) se fail ma khong ro ly do.
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    /**
     * Gan lai leg sau khi buoc correlate xac dinh duoc chu so huu that cua file.
     * Can thiet vi ten file khong dang tin: data mau co `calleer_webrtc.log` thuc chat
     * la log cua caller.
     */
    public CanonicalEvent withLeg(Leg newLeg) {
        return newLeg == leg ? this : new CanonicalEvent(
                eventId, callId, newLeg, source, time, type, name, attributes, severity, sourceRef);
    }
}
