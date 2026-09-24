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
 * Chọn ra các sự kiện đáng làm bằng chứng và gán ID ổn định.
 *
 * Phải LỌC MẠNH: một cuộc gọi sinh tới 28 000 sự kiện, trong đó chỉ khoảng một chục
 * dòng thực sự có ý nghĩa. MVP mục 3.3 cũng yêu cầu LLM chỉ nhận timeline, evidence và
 * chỉ số đã chuẩn hoá, không nhận raw log.
 *
 * ID được gán sau khi sắp xếp tất định; thứ tự đổi giữa các lần chạy sẽ làm Evidence ID
 * nhảy và phá tính nhất quán đo ở Sprint 2.
 */
public class EvidenceEngine {

    /** Lệnh signaling đánh dấu mốc của cuộc gọi — chỉ lấy lần XUẤT HIỆN ĐẦU. */
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

        // Bản ghi chỉ số CUỐI cùng của mỗi file — mang giá trị kết thúc của cuộc gọi
        timeline.allEvents().stream()
                .filter(e -> e.type() == EventType.MEDIA_STATS)
                .collect(java.util.stream.Collectors.groupingBy(e -> e.sourceRef().fileName()))
                .forEach((file, events) -> selected.add(events.getLast()));

        // Dòng kết thúc cuộc gọi phía client: mang codeReason và failReason bằng tiếng Việt
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
            case EventTime.Relative r -> "+" + r.sinceLogStart().toMillis() + "ms (tương đối)";
        };
    }

    private static String describe(CanonicalEvent event) {
        String iceTo = event.attribute("iceStateTo");
        if (iceTo != null) {
            return "ICE chuyển " + event.attribute("iceStateFrom") + " => " + iceTo;
        }
        // Leg chưa nhận được gói audio nào: MOS/loss/RTT trong log là số 0 điền vào chỗ
        // trống, in ra "MOS=0" sẽ mâu thuẫn với bảng chỉ số (N/A, MVP mục 4.3).
        if ((event.type() == EventType.CALL_SUMMARY || event.type() == EventType.MEDIA_STATS)
                && "0".equals(event.attribute("audio.packetsReceived"))) {
            return (event.type() == EventType.CALL_SUMMARY ? "Call summary " : "Chỉ số media ")
                    + event.leg().name().toLowerCase()
                    + ": không nhận được gói audio nào (packetsReceived=0, bytesRecv="
                    + orDash(event.attribute("audio.bytesReceived"))
                    + "), mediaFail=" + orDash(event.attribute("transport.hasMediaFail"))
                    + " — MOS/loss/RTT không đo được";
        }
        if (event.type() == EventType.CALL_SUMMARY) {
            return "Call summary " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss=" + orDash(event.attribute("audio.packetLostPercent")) + "%"
                    + ", RTT=" + orDash(event.attribute("transport.currentRttMs")) + "ms"
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        if (event.type() == EventType.MEDIA_STATS) {
            return "Chỉ số media " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss=" + orDash(event.attribute("audio.packetLostPercent")) + "%"
                    + ", mediaFail=" + orDash(event.attribute("transport.hasMediaFail"))
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        String msg = event.attribute("msg");
        if (msg != null && msg.startsWith("_emitBye")) {
            return "Kết thúc phía client: " + msg;
        }
        if (event.source() == LogSource.SIGNALING) {
            return "Signaling " + event.name()
                    + (event.leg() != io.hason.callanalysis.domain.event.Leg.UNKNOWN
                    ? " từ " + event.leg().name().toLowerCase() : "");
        }
        return event.name();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    /** Sự kiện có giờ tuyệt đối trước, rồi đến sự kiện tương đối; tie-break đến số dòng. */
    private static final Comparator<CanonicalEvent> EVIDENCE_ORDER =
            Comparator.comparingInt((CanonicalEvent e) ->
                            e.time() instanceof EventTime.Absolute ? 0 : 1)
                    .thenComparingLong(e -> e.time().sortKeyNanos())
                    .thenComparing(e -> e.source().ordinal())
                    .thenComparing(e -> e.sourceRef().fileName())
                    .thenComparingInt(e -> e.sourceRef().lineNumber());
}
