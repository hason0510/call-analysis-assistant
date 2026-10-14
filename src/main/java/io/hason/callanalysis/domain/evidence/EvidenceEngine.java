package io.hason.callanalysis.domain.evidence;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
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
 * gọi mẫu là 28 522), trong khi report chỉ giữ vài chục dòng (trên data mẫu nhiều nhất 22, ở 271D1FAF; trần cứng MAX_EVIDENCE). MVP mục 3.3 cũng yêu cầu LLM chỉ nhận timeline, evidence và
 * chỉ số đã chuẩn hoá, không nhận raw log.
 *
 * ID được gán sau khi sắp xếp tất định; thứ tự đổi giữa các lần chạy sẽ làm Evidence ID
 * nhảy và phá tính nhất quán đo ở Sprint 2.
 *
 * Ngoài năm loại sự kiện chọn cố định, report còn nhận các dòng CĂN CỨ của tín hiệu lỗi
 * (SignalExtractor.basis): ACK INIT_CALL mang mã lỗi, `_waitingCandidateTimer`, dòng TURN
 * của file không cấp phát được relay (kèm dòng ghi nhận VPN nếu có), bản ghi stats vượt ngưỡng. Thiếu chúng thì kết luận
 * như "TURN_FAILURE" hay "server từ chối (428)" không trỏ được về dòng log nào, trái
 * MVP mục 3.3: "Mọi kết luận phải trace được về evidence".
 */
public class EvidenceEngine {

    /** Lệnh signaling đánh dấu mốc của cuộc gọi — chỉ lấy lần XUẤT HIỆN ĐẦU. */
    private static final List<String> MILESTONE_COMMANDS = List.of(
            "INIT_CALL", "INVITE", "TRYING", "RINGING", "OK", "OK_ACK_OK",
            "BYE", "CANCEL", "FAIL_HARD");

    private static final int MAX_EVIDENCE = 40;

    private static final Set<String> NETWORK_SIGNALS = Set.of("network_lost", "network_poor", "network_stable");

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

        // Tín hiệu mạng app tự ghi (bản ghi `signal`): network_lost về phía bên kia, network_poor / network_stable
        // của chính máy đó. Rule không kết luận bằng chúng, nhưng thiếu chúng thì report không kể được lúc nào hết
        // dữ liệu từ bên kia (FE4EF5E7, 70C388AD). Mô tả giữ nguyên văn tên tín hiệu, không diễn giải thành "mất mạng".
        timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL && e.type() == EventType.USER_ACTION)
                .filter(e -> NETWORK_SIGNALS.contains(e.name()))
                .forEach(selected::add);

        selected.addAll(basis);

        List<CanonicalEvent> ordered = new ArrayList<>(selected);
        ordered.sort(EVIDENCE_ORDER);

        java.util.Map<String, String> deviceTags = deviceTags(timeline);
        List<Evidence> evidence = new ArrayList<>();
        int index = 1;
        for (CanonicalEvent event : ordered) {
            if (index > MAX_EVIDENCE) {
                break;
            }
            String tag = deviceTags.get(event.sourceRef().fileName());
            evidence.add(new Evidence(
                    String.format("EV%02d", index++),
                    event.source(),
                    timeLabel(event),
                    tag == null ? describe(event) : tag + " " + describe(event),
                    event,
                    basis.contains(event)));
        }
        return List.copyOf(evidence);
    }

    /**
     * Nhãn thiết bị cho end call log của leg có nhiều thiết bị. Không có nhãn thì hai dòng "Call summary callee"
     * của hai máy trông như một bên, và AI trích máy KHÔNG bắt máy (0 byte) làm căn cứ cho "callee không nhận
     * được âm thanh" ở fc3187b4. Máy có âm thanh là máy {@link CallTimeline#audioFile} chọn để tính chỉ số.
     */
    private static java.util.Map<String, String> deviceTags(CallTimeline timeline) {
        java.util.Map<String, String> tags = new java.util.LinkedHashMap<>();
        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            List<String> files = timeline.endCallFiles(leg);
            if (files.size() < 2) {
                continue;
            }
            java.util.Optional<String> chosen = timeline.audioFile(leg);
            for (String file : files) {
                boolean withAudio = chosen.isPresent() && chosen.get().equals(file);
                tags.put(file, "[thiết bị " + file + ", " + (withAudio
                        ? "có âm thanh, dùng tính chỉ số " + leg.name().toLowerCase() + "]"
                        : "không có âm thanh]"));
            }
        }
        return tags;
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
            // loss của summary tính từ bộ đếm cả cuộc, khớp bảng chỉ số; packetLostPercent
            // trong summary chỉ là tỉ lệ của khoảng cuối nên gần như luôn 0
            return "Call summary " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss cả cuộc=" + MetricsCalculator.cumulativeLossPercent(event)
                            .map(v -> v.stripTrailingZeros().toPlainString() + "%").orElse("-")
                    + ", RTT=" + orDash(event.attribute("transport.currentRttMs")) + "ms"
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        if (event.type() == EventType.MEDIA_STATS) {
            // Một mẫu stats: packetLostPercent là tỉ lệ TRONG KHOẢNG của mẫu đó, ghi rõ để
            // không lẫn với "loss cả cuộc" của dòng call summary
            // RTT và jitter có mặt để dòng căn cứ của cờ độ trễ (NETWORK_DELAY_JITTER) tự giải thích được
            return "Chỉ số media " + event.leg().name().toLowerCase()
                    + ": MOS=" + orDash(event.attribute("audio.audioMos"))
                    + ", loss trong khoảng=" + orDash(event.attribute("audio.packetLostPercent")) + "%"
                    + ", RTT=" + orDash(event.attribute("transport.currentRttMs")) + "ms"
                    + ", jitter=" + jitterMs(event.attribute("audio.jitter"))
                    + ", mediaFail=" + orDash(event.attribute("transport.hasMediaFail"))
                    + ", bytesRecv=" + orDash(event.attribute("audio.bytesReceived"));
        }
        if (event.type() == EventType.USER_ACTION && NETWORK_SIGNALS.contains(event.name())) {
            String originator = event.attribute("originator");
            return "Tín hiệu app: " + event.name() + ", originator=" + orDash(originator)
                    + ("remote".equalsIgnoreCase(originator) ? " (bên kia)" : "local".equalsIgnoreCase(originator) ? " (máy này)" : "");
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
        // Chỉ nêu tên giao diện; địa chỉ trên dòng gốc là của mạng VPN phía người dùng
        java.util.Optional<String> vpn = SignalExtractor.vpnInterfaceOf(event.attribute("message"));
        if (event.source() == LogSource.WEBRTC && vpn.isPresent()) {
            return "Mạng: libwebrtc ghi nhận giao diện VPN " + vpn.get();
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

    /** audio.jitter ghi bằng GIÂY trong log; in mili giây như bảng chỉ số. */
    private static String jitterMs(String seconds) {
        if (seconds == null || seconds.isBlank()) {
            return "-";
        }
        try {
            return new java.math.BigDecimal(seconds.strip()).multiply(java.math.BigDecimal.valueOf(1000))
                    .setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "ms";
        } catch (NumberFormatException e) {
            return "-";
        }
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
