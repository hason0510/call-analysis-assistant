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

/**
 * Tinh bo chi so Core theo MVP muc 4.3.
 *
 * Toan bo chi so do CODE tinh, khong giao cho AI (MVP muc 3.2) — so lieu phai dung
 * 100% va giong nhau moi lan chay.
 *
 * Ba chi so MVP yeu cau nhung data KHONG co, deu tra N/A kem ly do:
 *   - "So lan No sessions found": signaling khong co truong text nao.
 *   - Lenh mau 4.3 ghi OK_ACK, cmd that trong data la OK_ACK_OK.
 *   - Chi so chat luong: chi 2/20 cuoc goi co ban ghi call summary (#H9).
 */
public class MetricsCalculator {

    private static final String INIT_CALL = "INIT_CALL";
    private static final String INVITE = "INVITE";
    private static final String TRYING = "TRYING";
    private static final String RINGING = "RINGING";
    private static final String OK = "OK";
    private static final String OK_ACK = "OK_ACK_OK";
    private static final String BYE = "BYE";

    public CallMetrics calculate(CallTimeline timeline) {
        List<CallMetric> metrics = new ArrayList<>();

        metrics.add(CallMetric.of(MetricKey.SETUP_TIME,
                durationBetween(timeline, INIT_CALL, OK_ACK)));
        metrics.add(CallMetric.of(MetricKey.TIME_TO_CALLEE,
                durationBetween(timeline, INVITE, TRYING)));
        metrics.add(CallMetric.of(MetricKey.INVITE_RETRANSMISSIONS,
                retransmissions(timeline, INVITE)));
        metrics.add(CallMetric.of(MetricKey.NO_SESSIONS_FOUND, MetricValue.unavailable(
                "signaling khong co truong text de dem thong bao nay")));
        metrics.add(CallMetric.of(MetricKey.RINGING_TIME,
                durationBetween(timeline, RINGING, OK)));
        metrics.add(CallMetric.of(MetricKey.CONNECTED_DURATION,
                durationBetween(timeline, OK_ACK, BYE)));
        metrics.add(CallMetric.of(MetricKey.TERMINATED_BY, terminatedBy(timeline)));
        metrics.add(CallMetric.of(MetricKey.BYE_RETRANSMISSIONS,
                retransmissions(timeline, BYE)));

        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            Optional<CanonicalEvent> quality = qualityRecord(timeline, leg);
            metrics.add(CallMetric.of(MetricKey.MOS, leg,
                    decimalFrom(quality, "audio.audioMos", "", timeline, leg)));
            metrics.add(CallMetric.of(MetricKey.PACKET_LOSS, leg,
                    decimalFrom(quality, "audio.packetLostPercent", "%", timeline, leg)));
            metrics.add(CallMetric.of(MetricKey.RTT, leg,
                    // transport.rttMs la gia tri TICH LUY (8385 khi RTT that la 63),
                    // chi so dung phai la currentRttMs.
                    decimalFrom(quality, "transport.currentRttMs", "ms", timeline, leg)));
            metrics.add(CallMetric.of(MetricKey.JITTER, leg, jitter(quality, timeline, leg)));
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

    // ================= Chi so "Neu kip" (T11), MVP muc 4.3 =================

    /**
     * Khoang trong lon nhat giua hai PAIR_PING lien tiep cua cung mot leg.
     *
     * Day la chi so PROXY, khong phai do truc tiep: PAIR_PING la nhip tim cua tang
     * SIGNALING, nen khoang trong lon GOI Y duong signaling co van de — nhung KHONG
     * chung minh duoc media co van de. Cuoc goi 2D9057AA la vi du: PAIR_PING chay deu
     * suot 33 giay trong khi media da chet han.
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
                    ? "cuoc goi khong co PAIR_PING nao cua " + leg.name().toLowerCase()
                    : "chi co 1 PAIR_PING, khong do duoc khoang trong");
        }
        long max = 0;
        for (int i = 1; i < pings.size(); i++) {
            max = Math.max(max, java.time.Duration.between(pings.get(i - 1), pings.get(i)).toMillis());
        }
        return MetricValue.millis(max);
    }

    /** Latency cua cac API noi bo, lay tu truong latencyMs cua su kien INIT_CALL. */
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
            return MetricValue.unavailable("khong su kien INIT_CALL nao ghi latencyMs");
        }
        long max = values.getLast();
        long median = values.get(values.size() / 2);
        return MetricValue.text(String.format("max %d ms, trung vi %d ms (%d mau)",
                max, median, values.size()));
    }

    /**
     * Dem su kien theo muc do, nhom theo service.
     *
     * MVP muc 4.3 ghi ro: "WARN khong dong nghia voi loi". Tren data mau co 223/1059
     * su kien WARN va chung xuat hien o CA cuoc goi thanh cong, nen day chi la thong tin
     * tham khao, khong duoc dung lam tin hieu verdict.
     */
    private MetricValue countByService(CallTimeline timeline, Severity severity) {
        // Khong co signaling thi khong the noi "0 su kien" — do la KHONG DO DUOC,
        // khac han voi "do duoc va bang 0".
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("khong co du lieu signaling");
        }
        Map<String, Long> byService = new java.util.TreeMap<>();
        timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> e.severity() == severity)
                .forEach(e -> byService.merge(
                        e.attribute("service") == null ? "(khong ro)" : e.attribute("service"),
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

    /** ISP / ASN / quoc gia cua tung leg. Thieu o 63/1059 su kien nen phai chiu N/A. */
    private MetricValue networkContext(CallTimeline timeline, Leg leg) {
        List<CanonicalEvent> events = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> e.leg() == leg)
                .toList();

        if (events.isEmpty()) {
            return MetricValue.unavailable("khong co su kien signaling nao cua "
                    + leg.name().toLowerCase());
        }
        String isp = firstAttribute(events, "isp");
        String asn = firstAttribute(events, "asn");
        String country = firstAttribute(events, "countryCode");

        if (isp == null && asn == null && country == null) {
            return MetricValue.unavailable("su kien signaling khong ghi isp/asn/countryCode");
        }
        return MetricValue.text(String.format("%s / %s / %s",
                isp == null ? "?" : isp, asn == null ? "?" : asn, country == null ? "?" : country));
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
            return MetricValue.unavailable("khong co du lieu signaling");
        }
        Optional<Instant> from = timeline.firstSignaling(fromCmd).flatMap(MetricsCalculator::instantOf);
        if (from.isEmpty()) {
            return MetricValue.unavailable("cuoc goi khong dat toi " + fromCmd);
        }
        Optional<Instant> to = timeline.firstSignaling(toCmd).flatMap(MetricsCalculator::instantOf);
        if (to.isEmpty()) {
            return MetricValue.unavailable("cuoc goi khong dat toi " + toCmd);
        }
        // Phai tru o do chinh xac NANO roi moi cat ve mili.
        // Cat tung Instant ve mili truoc khi tru lam sai lech 1 ms tren 5/20 cuoc goi,
        // trong khi Metric Correctness yeu cau dung 100%.
        long millis = java.time.Duration.between(from.get(), to.get()).toMillis();
        if (millis < 0) {
            return MetricValue.unavailable(toCmd + " xay ra truoc " + fromCmd + ", du lieu mau thuan");
        }
        return MetricValue.millis(millis);
    }

    /**
     * Khoang cach toi da giua hai dong log cua CUNG MOT lan gui.
     *
     * Khong phai con so doan: do tren toan bo 451 khoang cach giua cac su kien signaling
     * lien tiep cung lenh, phan bo luong cuc rat sach —
     *   363 khoang cach duoi 100 ms  (nhieu dong log cua cung mot lan gui)
     *   0   khoang cach trong dai 100-500 ms
     *   88  khoang cach tu 500 ms tro len  (lan gui lai, theo exponential backoff)
     * Dai trong 87 ms -> 506 ms cho phep dat nguong o giua ma khong co ca nao nhap nhang.
     */
    private static final long SAME_TRANSMISSION_WINDOW_MS = 250;

    /**
     * Dem so LAN GUI, khong phai so dong log.
     *
     * Mot lan gui sinh nhieu dong log: cuoc goi EE129C8F co 10 su kien cmd=INVITE nhung
     * chi 1 requestId, va timestamp gom thanh 4 cum cach nhau 0,5s / 1,0s / 2,0s —
     * dung dau hieu gui lai theo exponential backoff. Dem thang so su kien se ra 9 lan
     * gui lai thay vi 3.
     *
     * Lan gui dau khong tinh la gui lai.
     */
    private MetricValue retransmissions(CallTimeline timeline, String command) {
        if (!timeline.hasSource(LogSource.SIGNALING)) {
            return MetricValue.unavailable("khong co du lieu signaling");
        }
        List<Instant> times = timeline.mainTrack().stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> command.equals(e.name()))
                .flatMap(e -> instantOf(e).stream())
                .sorted()
                .toList();
        if (times.isEmpty()) {
            return MetricValue.unavailable("cuoc goi khong co lenh " + command);
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
            return MetricValue.unavailable("khong co du lieu signaling");
        }
        return timeline.firstSignaling(BYE)
                .map(e -> e.leg() == Leg.UNKNOWN
                        ? MetricValue.unavailable("khong suy ra duoc ben gui BYE")
                        : MetricValue.text(e.leg().name()))
                .orElseGet(() -> MetricValue.unavailable("cuoc goi khong co BYE"));
    }

    /**
     * Uu tien ban ghi call summary (#H9); khong co thi lay ban ghi periodic stats (#H7)
     * CUOI CUNG. Doc #H9 don thuan se bo sot dien bien trong cuoc goi, nhung o day muc tieu
     * la gia tri ket thuc nen lay ban ghi sau cung la dung.
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

    private MetricValue decimalFrom(Optional<CanonicalEvent> record, String field,
                                    String unit, CallTimeline timeline, Leg leg) {
        return readDecimal(record, field, timeline, leg)
                .map(v -> MetricValue.of(v, unit))
                .orElseGet(() -> unavailableReason(record, field, timeline, leg));
    }

    /** audio.jitter tinh bang GIAY trong log; doi sang mili giay cho de doc. */
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

    /** Ly do N/A phai cu the theo tung nguyen nhan, dung mot cau chung chung. */
    private MetricValue unavailableReason(Optional<CanonicalEvent> record, String field,
                                          CallTimeline timeline, Leg leg) {
        if (!hasEndCallLog(timeline, leg)) {
            return MetricValue.unavailable("khong co end call log cua " + leg.name().toLowerCase());
        }
        if (record.isEmpty()) {
            return MetricValue.unavailable("end call log khong co ban ghi chi so chat luong");
        }
        return MetricValue.unavailable("truong " + field + " rong trong ban ghi chi so");
    }

    private boolean hasEndCallLog(CallTimeline timeline, Leg leg) {
        return timeline.allEvents().stream()
                .anyMatch(e -> e.source() == LogSource.ENDCALL && e.leg() == leg);
    }

    /**
     * Trang thai ICE DAT DUOC, khong phai trang thai cuoi cung.
     *
     * Cuoc goi thanh cong van ket thuc o `disconnected` khi nguoi dung cup may — bao cao
     * gia tri cuoi cung se gay hieu nham. Cai can biet la ICE co tung ket noi duoc khong.
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
                ? MetricValue.unavailable("WebRTC log khong ghi chuyen trang thai ICE nao")
                : MetricValue.unavailable("khong co WebRTC log cua " + leg.name().toLowerCase());
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
