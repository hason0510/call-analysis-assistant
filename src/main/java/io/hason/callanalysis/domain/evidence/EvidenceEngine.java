package io.hason.callanalysis.domain.evidence;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.timeline.CallTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chon ra cac su kien dang lam bang chung va gan ID on dinh.
 *
 * Phai LOC MANH: mot cuoc goi sinh toi 28 000 su kien, trong do chi khoang mot chuc
 * dong thuc su co y nghia. MVP muc 3.3 cung yeu cau LLM chi nhan timeline, evidence va
 * chi so da chuan hoa, khong nhan raw log.
 *
 * ID duoc gan sau khi sap xep tat dinh; thu tu doi giua cac lan chay se lam Evidence ID
 * nhay va pha tinh nhat quan do o Sprint 2.
 */
public class EvidenceEngine {

    /** Lenh signaling danh dau moc cua cuoc goi — chi lay lan XUAT HIEN DAU. */
    private static final List<String> MILESTONE_COMMANDS = List.of(
            "INIT_CALL", "INVITE", "TRYING", "RINGING", "OK", "OK_ACK_OK",
            "BYE", "CANCEL", "FAIL_HARD");

    private static final int MAX_EVIDENCE = 40;

    public List<Evidence> collect(CallTimeline timeline) {
        Set<CanonicalEvent> selected = new LinkedHashSet<>();

        for (String command : MILESTONE_COMMANDS) {
            timeline.firstSignaling(command).ifPresent(selected::add);
        }

        timeline.allEvents().stream()
                .filter(e -> e.attribute("iceStateTo") != null)
                .forEach(selected::add);

        timeline.allEvents().stream()
                .filter(e -> e.type() == EventType.CALL_SUMMARY)
                .forEach(selected::add);

        // Ban ghi chi so CUOI cung cua moi file — mang gia tri ket thuc cua cuoc goi
        timeline.allEvents().stream()
                .filter(e -> e.type() == EventType.MEDIA_STATS)
                .collect(java.util.stream.Collectors.groupingBy(e -> e.sourceRef().fileName()))
                .forEach((file, events) -> selected.add(events.getLast()));

        // Dong ket thuc cuoc goi phia client: mang codeReason va failReason bang tieng Viet
        timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> {
                    String msg = e.attribute("msg");
                    return msg != null && msg.startsWith("_emitBye");
                })
                .forEach(selected::add);

        List<CanonicalEvent> ordered = new ArrayList<>(selected);
        ordered.sort(EVIDENCE_ORDER);

        List<Evidence> evidence = new ArrayList<>();
        int index = 1;
        for (CanonicalEvent event : ordered) {
            if (index > MAX_EVIDENCE) {
                break;
            }
            evidence.add(new Evidence(
                    String.format("EV%02d", index++),
                    event.source(),
                    timeLabel(event),
                    describe(event),
                    event));
        }
        return List.copyOf(evidence);
    }

    private static String timeLabel(CanonicalEvent event) {
        return switch (event.time()) {
            case EventTime.Absolute a -> a.instant().toString();
            case EventTime.Relative r -> "+" + r.sinceLogStart().toMillis() + "ms (tuong doi)";
        };
    }

    private static String describe(CanonicalEvent event) {
        String iceTo = event.attribute("iceStateTo");
        if (iceTo != null) {
            return "ICE chuyen " + event.attribute("iceStateFrom") + " => " + iceTo;
        }
        if (event.type() == EventType.CALL_SUMMARY) {
            return "Call summary " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss=" + orDash(event.attribute("audio.packetLostPercent")) + "%"
                    + ", RTT=" + orDash(event.attribute("transport.currentRttMs")) + "ms"
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        if (event.type() == EventType.MEDIA_STATS) {
            return "Chi so media " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss=" + orDash(event.attribute("audio.packetLostPercent")) + "%"
                    + ", mediaFail=" + orDash(event.attribute("transport.hasMediaFail"))
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        String msg = event.attribute("msg");
        if (msg != null && msg.startsWith("_emitBye")) {
            return "Ket thuc phia client: " + msg;
        }
        if (event.source() == LogSource.SIGNALING) {
            return "Signaling " + event.name()
                    + (event.leg() != io.hason.callanalysis.domain.event.Leg.UNKNOWN
                    ? " tu " + event.leg().name().toLowerCase() : "");
        }
        return event.name();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Su kien co gio tuyet doi truoc, roi den su kien tuong doi; tie-break den so dong. */
    private static final Comparator<CanonicalEvent> EVIDENCE_ORDER =
            Comparator.comparingInt((CanonicalEvent e) ->
                            e.time() instanceof EventTime.Absolute ? 0 : 1)
                    .thenComparingLong(e -> e.time().sortKeyNanos())
                    .thenComparing(e -> e.source().ordinal())
                    .thenComparing(e -> e.sourceRef().fileName())
                    .thenComparingInt(e -> e.sourceRef().lineNumber());
}
