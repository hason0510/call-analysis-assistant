package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.timeline.CallTimeline;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tính bộ chỉ số Core theo MVP mục 4.3.
 *
 * Toàn bộ chỉ số do CODE tính, không giao cho AI (MVP mục 3.2) — số liệu phải đúng
 * 100% và giống nhau mỗi lần chạy.
 *
 * Ba chỉ số MVP yêu cầu nhưng data KHÔNG có, đều trả N/A kèm lý do:
 *   - "Số lần No sessions found": signaling không có trường text nào.
 *   - Lệnh mẫu 4.3 ghi OK_ACK, cmd thật trong data là OK_ACK_OK.
 *   - Chỉ số chất lượng: chỉ có khi cuộc gọi từng có media và leg đó có end call log.
 */
public class MetricsCalculator {

    private static final String INIT_CALL = "INIT_CALL";
    private static final String INVITE = "INVITE";
    private static final String TRYING = "TRYING";
    private static final String RINGING = "RINGING";
    private static final String OK = "OK";
    private static final String OK_ACK = "OK_ACK_OK";
    private static final String BYE = "BYE";

    private static final String PACKETS_RECEIVED = "audio.packetsReceived";
    private static final String STUN_RESPONSES = "transport.localStunResponse";
    /**
     * Lý do N/A chỉ nêu đúng trường và giá trị trong log, để người đọc tra thẳng được.
     * Lời giải thích "số 0 là giá trị trống, không phải kết quả đo" nằm MỘT lần ở mục
     * Giới hạn dữ liệu (ReportBuilder) — viết vào từng ô thì một report lặp câu đó 6 lần.
     */
    public static final String NO_AUDIO_PACKETS = PACKETS_RECEIVED + " = 0";
    public static final String NO_STUN_RESPONSE = STUN_RESPONSES + " = 0";

    public CallMetrics calculate(CallTimeline timeline) {
        List<CallMetric> metrics = new ArrayList<>();

        metrics.add(CallMetric.of(MetricKey.SETUP_TIME,
                durationBetween(timeline, INIT_CALL, OK_ACK)));
        metrics.add(CallMetric.of(MetricKey.TIME_TO_CALLEE,
                durationBetween(timeline, INVITE, TRYING)));
        metrics.add(CallMetric.of(MetricKey.INVITE_RETRANSMISSIONS,
                retransmissions(timeline, INVITE)));
        metrics.add(CallMetric.of(MetricKey.NO_SESSIONS_FOUND, MetricValue.unavailable(
                "signaling không có trường text để đếm thông báo này")));
        metrics.add(CallMetric.of(MetricKey.RINGING_TIME,
                durationBetween(timeline, RINGING, OK)));
        metrics.add(CallMetric.of(MetricKey.CONNECTED_DURATION,
                durationBetween(timeline, OK_ACK, BYE)));
        metrics.add(CallMetric.of(MetricKey.TERMINATED_BY, terminatedBy(timeline)));
        metrics.add(CallMetric.of(MetricKey.BYE_RETRANSMISSIONS,
                retransmissions(timeline, BYE)));

        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            Optional<CanonicalEvent> quality = qualityRecord(timeline, leg);
            metrics.add(CallMetric.of(MetricKey.MOS, leg, measuredOnly(quality, PACKETS_RECEIVED,
                    NO_AUDIO_PACKETS, () -> decimalFrom(quality, "audio.audioMos", "", timeline, leg))));
            metrics.add(CallMetric.of(MetricKey.PACKET_LOSS, leg, measuredOnly(quality, PACKETS_RECEIVED,
                    NO_AUDIO_PACKETS,
                    () -> decimalFrom(quality, "audio.packetLostPercent", "%", timeline, leg))));
            metrics.add(CallMetric.of(MetricKey.RTT, leg, measuredOnly(quality, STUN_RESPONSES,
                    NO_STUN_RESPONSE,
                    // transport.rttMs là giá trị TÍCH LUỸ (8385 khi RTT thật là 63),
                    // chỉ số đúng phải là currentRttMs.
                    () -> decimalFrom(quality, "transport.currentRttMs", "ms", timeline, leg))));
            metrics.add(CallMetric.of(MetricKey.JITTER, leg, measuredOnly(quality, PACKETS_RECEIVED,
                    NO_AUDIO_PACKETS, () -> jitter(quality, timeline, leg))));
            metrics.add(CallMetric.of(MetricKey.ICE_FINAL_STATE, leg, iceFinalState(timeline, leg)));
            metrics.add(CallMetric.of(MetricKey.MAX_PAIR_PING_GAP, leg, maxPairPingGap(timeline, leg)));
            metrics.add(CallMetric.of(MetricKey.NETWORK_CONTEXT, leg, networkContext(timeline, leg)));
        }

        metrics.add(CallMetric.of(MetricKey.INTERNAL_API_LATENCY, internalApiLatency(timeline)));
        metrics.add(CallMetric.of(MetricKey.WARN_COUNT_BY_SERVICE,
                countByService(timeline, Severity.WARN)));
        metrics.add(CallMetric.of(MetricKey.ERROR_COUNT_BY_SERVICE,
                countByService(timeline, Severity.ERROR)));

        return new CallMetrics(timeline.callId(), List.copyOf(metrics));
    }

    // ================= Chỉ số "Nếu kịp" (T11), MVP mục 4.3 =================

    /**
     * Khoảng trống lớn nhất giữa hai PAIR_PING liên tiếp của cùng một leg.
     *
     * Đây là chỉ số PROXY, không phải đo trực tiếp: PAIR_PING là nhịp tim của tầng
     * SIGNALING, nên khoảng trống lớn GỢI Ý đường signaling có vấn đề — nhưng KHÔNG
     * chứng minh được media có vấn đề. Cuộc gọi 2D9057AA là ví dụ: PAIR_PING phía callee
     * vẫn đều tới giây ~39,5 (sát lúc BYE) trong khi ICE phía callee đã failed.
     *
     * Hạn chế: chỉ đo khoảng trống GIỮA hai lần ping, không đo từ lần ping cuối tới lúc
     * kết thúc. Cũng ở 2D9057AA, PAIR_PING phía caller ngừng hẳn từ giây ~24,8 tới lúc
     * BYE (~40 s), nhưng chỉ số vẫn ra 1019 ms.
     */
    private MetricValue maxPairPingGap(CallTimeline timeline, Leg leg) {
        List<Instant> pings = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> "PAIR_PING".equals(e.name()))
                .filter(e -> e.leg() == leg)
                .flatMap(e -> instantOf(e).stream())
                .sorted()
                .toList();

        if (pings.size() < 2) {
            return MetricValue.unavailable(pings.isEmpty()
                    ? "cuộc gọi không có PAIR_PING nào của " + leg.name().toLowerCase()
                    : "chỉ có 1 PAIR_PING, không đo được khoảng trống");
        }
        long max = 0;
        for (int i = 1; i < pings.size(); i++) {
            max = Math.max(max, java.time.Duration.between(pings.get(i - 1), pings.get(i)).toMillis());
        }
        return MetricValue.millis(max);
    }

    /** Latency của các API nội bộ, lấy từ trường latencyMs của sự kiện INIT_CALL. */
    private MetricValue internalApiLatency(CallTimeline timeline) {
        List<Long> values = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> INIT_CALL.equals(e.name()))
                .map(e -> e.attribute("latencyMs"))
                .filter(java.util.Objects::nonNull)
                .map(Long::parseLong)
                .sorted()
                .toList();

        if (values.isEmpty()) {
            return MetricValue.unavailable("không sự kiện INIT_CALL nào ghi latencyMs");
        }
        long max = values.getLast();
        long median = values.get(values.size() / 2);
        return MetricValue.text(String.format("max %d ms, trung vị %d ms (%d mẫu)",
                max, median, values.size()));
    }

    /**
     * Đếm sự kiện theo mức độ, nhóm theo service.
     *
     * MVP mục 4.3 ghi rõ: "WARN không đồng nghĩa với lỗi". Trên data mẫu có 223/1059
     * sự kiện WARN và chúng xuất hiện ở CẢ cuộc gọi thành công, nên đây chỉ là thông tin
     * tham khảo, không được dùng làm tín hiệu verdict.
     */
    private MetricValue countByService(CallTimeline timeline, Severity severity) {
        // Không có signaling thì không thể nói "0 sự kiện" — đó là KHÔNG ĐO ĐƯỢC,
        // khác hẳn với "đo được và bằng 0".
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("không có dữ liệu signaling");
        }
        Map<String, Long> byService = new java.util.TreeMap<>();
        timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> e.severity() == severity)
                .forEach(e -> byService.merge(
                        e.attribute("service") == null ? "(không rõ)" : e.attribute("service"),
                        1L, Long::sum));

        if (byService.isEmpty()) {
            return MetricValue.count(0);
        }
        String detail = byService.entrySet().stream()
                .map(en -> en.getKey() + "=" + en.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        long total = byService.values().stream().mapToLong(Long::longValue).sum();
        return MetricValue.text(total + " (" + detail + ")");
    }

    /** ISP / ASN / quốc gia của từng leg. Thiếu ở 63/1059 sự kiện nên phải chịu N/A. */
    private MetricValue networkContext(CallTimeline timeline, Leg leg) {
        List<CanonicalEvent> events = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> e.leg() == leg)
                .toList();

        if (events.isEmpty()) {
            return MetricValue.unavailable("không có sự kiện signaling nào của "
                    + leg.name().toLowerCase());
        }
        String isp = firstAttribute(events, "isp");
        String asn = firstAttribute(events, "asn");
        String country = firstAttribute(events, "countryCode");

        if (isp == null && asn == null && country == null) {
            return MetricValue.unavailable("sự kiện signaling không ghi isp/asn/countryCode");
        }
        // Thiếu một phần thì ghi N/A và nêu tên trường thiếu (MVP mục 4.3), không tự điền "?"
        List<String> missing = new ArrayList<>();
        if (isp == null) {
            missing.add("isp");
        }
        if (asn == null) {
            missing.add("asn");
        }
        if (country == null) {
            missing.add("countryCode");
        }
        String value = String.format("%s / %s / %s", orNa(isp), orNa(asn), orNa(country));
        return MetricValue.text(missing.isEmpty() ? value
                : value + " (signaling không ghi " + String.join(", ", missing) + ")");
    }

    private static String orNa(String value) {
        return value == null ? "N/A" : value;
    }

    private static String firstAttribute(List<CanonicalEvent> events, String key) {
        return events.stream()
                .map(e -> e.attribute(key))
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(null);
    }

    private MetricValue durationBetween(CallTimeline timeline, String fromCmd, String toCmd) {
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("không có dữ liệu signaling");
        }
        Optional<Instant> from = timeline.firstSignaling(fromCmd).flatMap(MetricsCalculator::instantOf);
        if (from.isEmpty()) {
            return MetricValue.unavailable("cuộc gọi không đạt tới " + fromCmd);
        }
        Optional<Instant> to = timeline.firstSignaling(toCmd).flatMap(MetricsCalculator::instantOf);
        if (to.isEmpty()) {
            return MetricValue.unavailable("cuộc gọi không đạt tới " + toCmd);
        }
        // Phải trừ ở độ chính xác NANO rồi mới cắt về mili.
        // Cắt từng Instant về mili trước khi trừ làm sai lệch 1 ms trên 5/20 cuộc gọi,
        // trong khi Metric Correctness yêu cầu đúng 100%.
        long millis = java.time.Duration.between(from.get(), to.get()).toMillis();
        if (millis < 0) {
            return MetricValue.unavailable(toCmd + " xảy ra trước " + fromCmd + ", dữ liệu mâu thuẫn");
        }
        return MetricValue.millis(millis);
    }

    /**
     * Khoảng cách tối đa giữa hai dòng log của CÙNG MỘT lần gửi.
     *
     * Không phải con số đoán: đo trên toàn bộ 451 khoảng cách giữa các sự kiện signaling
     * liên tiếp cùng lệnh, phân bố lưỡng cực rất sạch —
     *   363 khoảng cách dưới 100 ms  (nhiều dòng log của cùng một lần gửi)
     *   0   khoảng cách trong dải 100-500 ms
     *   88  khoảng cách từ 500 ms trở lên  (lần gửi lại, theo exponential backoff)
     * Dải trống 87 ms -> 506 ms cho phép đặt ngưỡng ở giữa mà không có ca nào nhập nhằng.
     */
    private static final long SAME_TRANSMISSION_WINDOW_MS = 250;

    /**
     * Đếm số LẦN GỬI, không phải số dòng log.
     *
     * Một lần gửi sinh nhiều dòng log: cuộc gọi EE129C8F có 10 sự kiện cmd=INVITE nhưng
     * chỉ 1 requestId, và timestamp gom thành 4 cụm cách nhau 0,5s / 1,0s / 2,0s —
     * đúng dấu hiệu gửi lại theo exponential backoff. Đếm thẳng số sự kiện sẽ ra 9 lần
     * gửi lại thay vì 3.
     *
     * Lần gửi đầu không tính là gửi lại.
     */
    private MetricValue retransmissions(CallTimeline timeline, String command) {
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("không có dữ liệu signaling");
        }
        List<Instant> times = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> command.equals(e.name()))
                .flatMap(e -> instantOf(e).stream())
                .sorted()
                .toList();
        if (times.isEmpty()) {
            return MetricValue.unavailable("cuộc gọi không đạt tới " + command);
        }

        long transmissions = 1;
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i).toEpochMilli() - times.get(i - 1).toEpochMilli() > SAME_TRANSMISSION_WINDOW_MS) {
                transmissions++;
            }
        }
        return MetricValue.count(transmissions - 1);
    }

    private MetricValue terminatedBy(CallTimeline timeline) {
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("không có dữ liệu signaling");
        }
        return timeline.firstSignaling(BYE)
                .map(e -> e.leg() == Leg.UNKNOWN
                        ? MetricValue.unavailable("không suy ra được bên gửi BYE")
                        : MetricValue.text(e.leg().name()))
                .orElseGet(() -> MetricValue.unavailable("cuộc gọi không đạt tới " + BYE));
    }

    /**
     * Ưu tiên bản ghi call summary (tag `endcall`); không có thì lấy bản ghi periodic stats
     * (tag `stats`) CUỐI CÙNG. Đọc summary đơn thuần sẽ bỏ sót diễn biến trong cuộc gọi,
     * nhưng ở đây mục tiêu là giá trị kết thúc nên lấy bản ghi sau cùng là đúng.
     */
    private Optional<CanonicalEvent> qualityRecord(CallTimeline timeline, Leg leg) {
        Optional<CanonicalEvent> summary = timeline.callSummary(leg);
        if (summary.isPresent()) {
            return summary;
        }
        return timeline.allEvents().stream()
                .filter(e -> e.type() == EventType.MEDIA_STATS && e.leg() == leg)
                .reduce((first, second) -> second);
    }

    /**
     * Chặn giá trị 0 mà app ghi vào chỗ CHƯA ĐO ĐƯỢC (MVP mục 4.3: không mặc định về 0).
     *
     * Leg chưa từng có media vẫn có bản ghi summary, nhưng MOS/loss/RTT/jitter đều là 0:
     * MOS theo định nghĩa nằm trong 1-5 nên 0 không phải kết quả đo; loss% là 0/0 khi không
     * có gói nào; RTT 0 ms chỉ có nghĩa là chưa có phản hồi STUN; jitter cần ít nhất 2 gói.
     * Trên data mẫu, 33/33 dòng có MOS = 0 đều có packetsReceived = 0, và 7 leg có media thật
     * đều có packetsReceived > 0 và localStunResponse > 0.
     */
    private static MetricValue measuredOnly(Optional<CanonicalEvent> record, String counterField,
                                            String reason, Supplier<MetricValue> measured) {
        boolean nothingToMeasure = record
                .map(e -> e.attribute(counterField))
                .flatMap(MetricsCalculator::parseDecimal)
                .map(v -> v.signum() == 0)
                .orElse(false);
        return nothingToMeasure ? MetricValue.unavailable(reason) : measured.get();
    }

    private MetricValue decimalFrom(Optional<CanonicalEvent> record, String field,
                                    String unit, CallTimeline timeline, Leg leg) {
        return readDecimal(record, field, timeline, leg)
                .map(v -> MetricValue.of(v, unit))
                .orElseGet(() -> unavailableReason(record, field, timeline, leg));
    }

    /** audio.jitter tính bằng GIÂY trong log; đổi sang mili giây cho dễ đọc. */
    private MetricValue jitter(Optional<CanonicalEvent> record, CallTimeline timeline, Leg leg) {
        return readDecimal(record, "audio.jitter", timeline, leg)
                .map(seconds -> MetricValue.of(
                        seconds.multiply(BigDecimal.valueOf(1000)).setScale(3, RoundingMode.HALF_UP), "ms"))
                .orElseGet(() -> unavailableReason(record, "audio.jitter", timeline, leg));
    }

    private Optional<BigDecimal> readDecimal(Optional<CanonicalEvent> record, String field,
                                             CallTimeline timeline, Leg leg) {
        return record.map(e -> e.attribute(field)).flatMap(MetricsCalculator::parseDecimal);
    }

    /** Lý do N/A phải cụ thể theo từng nguyên nhân, đừng một câu chung chung. */
    private MetricValue unavailableReason(Optional<CanonicalEvent> record, String field,
                                          CallTimeline timeline, Leg leg) {
        if (!hasEndCallLog(timeline, leg)) {
            return MetricValue.unavailable("không có end call log của " + leg.name().toLowerCase());
        }
        if (record.isEmpty()) {
            return MetricValue.unavailable("end call log không có bản ghi chỉ số chất lượng");
        }
        return MetricValue.unavailable("trường " + field + " rỗng trong bản ghi chỉ số");
    }

    private boolean hasEndCallLog(CallTimeline timeline, Leg leg) {
        return timeline.allEvents().stream()
                .anyMatch(e -> e.source() == LogSource.ENDCALL && e.leg() == leg);
    }

    /**
     * Trạng thái ICE ĐẠT ĐƯỢC, không phải trạng thái cuối cùng.
     *
     * Cuộc gọi thành công vẫn kết thúc ở `disconnected` khi người dùng cúp máy — báo cáo
     * giá trị cuối cùng sẽ gây hiểu nhầm. Cái cần biết là ICE có từng kết nối được không.
     */
    private MetricValue iceFinalState(CallTimeline timeline, Leg leg) {
        List<String> states = timeline.allEvents().stream()
                .filter(e -> e.leg() == leg)
                .map(e -> e.attribute("iceStateTo"))
                .filter(java.util.Objects::nonNull)
                .toList();

        if (!states.isEmpty()) {
            boolean everConnected = states.stream()
                    .anyMatch(s -> s.equalsIgnoreCase("connected") || s.equalsIgnoreCase("completed"));
            return MetricValue.text(everConnected ? "connected" : states.getLast());
        }
        return timeline.allEvents().stream().anyMatch(e -> e.source() == LogSource.WEBRTC && e.leg() == leg)
                ? MetricValue.unavailable("WebRTC log không ghi chuyển trạng thái ICE nào")
                : MetricValue.unavailable("không có WebRTC log của " + leg.name().toLowerCase());
    }

    private static Optional<BigDecimal> parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Instant> instantOf(CanonicalEvent event) {
        return event.time() instanceof EventTime.Absolute absolute
                ? Optional.of(absolute.instant())
                : Optional.empty();
    }
}
