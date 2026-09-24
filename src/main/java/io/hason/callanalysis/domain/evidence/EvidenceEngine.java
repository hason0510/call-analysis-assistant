package io.hason.callanalysis.domain.evidence;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.rule.SignalExtractor;
import io.hason.callanalysis.domain.timeline.CallTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chọn ra các sự kiện đáng làm bằng chứng và gán ID ổn định.
 *
 * Phải LỌC MẠNH: một cuộc gọi sinh tới gần 7 000 sự kiện (DE7DD314: 6 680; cả 20 cuộc
 * gọi mẫu là 28 522), trong khi report chỉ giữ vài chục dòng (nhiều nhất 21). MVP mục 3.3 cũng yêu cầu LLM chỉ nhận timeline, evidence và
 * chỉ số đã chuẩn hoá, không nhận raw log.
 *
 * ID được gán sau khi sắp xếp tất định; thứ tự đổi giữa các lần chạy sẽ làm Evidence ID
 * nhảy và phá tính nhất quán đo ở Sprint 2.
 *
 * Ngoài năm loại sự kiện chọn cố định, report còn nhận các dòng CĂN CỨ của tín hiệu lỗi
 * (SignalExtractor.basis): ACK INIT_CALL mang mã lỗi, `_waitingCandidateTimer`, dòng TURN
 * của file không cấp phát được relay, bản ghi stats vượt ngưỡng. Thiếu chúng thì kết luận
 * như "TURN_FAILURE" hay "server từ chối (428)" không trỏ được về dòng log nào, trái
 * MVP mục 3.3: "Mọi kết luận phải trace được về evidence".
 */
public class EvidenceEngine {

    /** Lệnh signaling đánh dấu mốc của cuộc gọi — chỉ lấy lần XUẤT HIỆN ĐẦU. */
    private static final List<String> MILESTONE_COMMANDS = List.of(
            "INIT_CALL", "INVITE", "TRYING", "RINGING", "OK", "OK_ACK_OK",
            "BYE", "CANCEL", "FAIL_HARD");

    private static final int MAX_EVIDENCE = 40;

    public List<Evidence> collect(CallTimeline timeline) {
        return collect(timeline, List.of());
    }

    /** @param basis dòng căn cứ của các tín hiệu lỗi, từ SignalExtractor.basis */
    public List<Evidence> collect(CallTimeline timeline, List<CanonicalEvent> basis) {
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

        // Dòng kết thúc cuộc gọi phía client: `_emitBye` mang codeReason / failReason,
        // `_emitFailed` mang reason / code khi cuộc gọi thất bại trước khi thiết lập xong.
        timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> {
                    String msg = e.attribute("msg");
                    return msg != null && (msg.startsWith("_emitBye") || msg.startsWith("_emitFailed"));
                })
                .forEach(selected::add);

        selected.addAll(basis);

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
        if (msg != null && msg.startsWith("_emitFailed")) {
            return "Thất bại phía client: " + msg;
        }
        if (event.type() == EventType.SIGNALING_COMMAND && event.source() == LogSource.ENDCALL
                && "INIT_CALL".equals(event.attribute("ackCmd"))) {
            return SignalExtractor.describeCallError(event.attribute("payload"))
                    .map(code -> "Server trả ACK cho INIT_CALL kèm callErrorCode: " + code)
                    .orElse("Server trả ACK cho INIT_CALL");
        }
        if (event.type() == EventType.TURN_EVENT) {
            return "TURN: " + turnMessage(event.attribute("message"));
        }
        if (msg != null && event.source() == LogSource.ENDCALL) {
            return "Log phía client: " + msg;
        }
        if (event.source() == LogSource.SIGNALING) {
            return "Signaling " + event.name()
                    + (event.leg() != io.hason.callanalysis.domain.event.Leg.UNKNOWN
                    ? " từ " + event.leg().name().toLowerCase() : "");
        }
        return event.name();
    }

    /**
     * Bỏ phần `TurnPort(Port[...Net[...]]-Remote[IP:3478/udp]: ` ở đầu và che IPv4 còn sót:
     * địa chỉ TURN server là INTERNAL, địa chỉ mạng nội bộ là SENSITIVE
     * (resources/sensitive-data-inventory.yaml). Dòng gốc vẫn tra được qua [file:dòng].
     */
    private static String turnMessage(String message) {
        if (message == null) {
            return "-";
        }
        String text = message.lines().findFirst().orElse("");
        int remote = text.indexOf("-Remote[");
        int cut = remote < 0 ? -1 : text.indexOf("]: ", remote);
        if (cut >= 0) {
            text = text.substring(cut + 3);
        }
        return IPV4.matcher(text).replaceAll("<ip>").strip();
    }

    private static final java.util.regex.Pattern IPV4 =
            java.util.regex.Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.(?:\\d{1,3}|x)\\b");

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
