package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCalculatorTest {

    private final MetricsCalculator calculator = new MetricsCalculator();
    private final TimelineBuilder timelineBuilder = new TimelineBuilder();

    private static final Instant T0 = Instant.parse("2026-09-21T08:00:00.000000000Z");
    private int ordinal = 0;

    private CanonicalEvent sig(String isoTime, String cmd, Leg leg) {
        int n = ordinal++;
        return new CanonicalEvent("signaling#" + n, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse(isoTime), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, n + 1, cmd));
    }

    private CanonicalEvent sigAt(long millisFromT0, String cmd, Leg leg) {
        int n = ordinal++;
        return new CanonicalEvent("signaling#" + n, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(T0.plusMillis(millisFromT0), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, n + 1, cmd));
    }

    private CanonicalEvent sigWarn(long millis, String cmd, String service) {
        int n = ordinal++;
        return new CanonicalEvent("signaling#" + n, "CALL-1", Leg.CALLER, LogSource.SIGNALING,
                EventTime.absolute(T0.plusMillis(millis), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of("service", service),
                io.hason.callanalysis.domain.event.Severity.WARN,
                new SourceRef(SourceRef.SIGNALING, n + 1, cmd));
    }

    private CanonicalEvent sigCtx(long millis, String cmd, Leg leg,
                                  String isp, String asn, String country) {
        int n = ordinal++;
        return new CanonicalEvent("signaling#" + n, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(T0.plusMillis(millis), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd,
                Map.of("isp", isp, "asn", asn, "countryCode", country), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, n + 1, cmd));
    }

    private CanonicalEvent sigLatency(long millis, int latencyMs) {
        int n = ordinal++;
        return new CanonicalEvent("signaling#" + n, "CALL-1", Leg.CALLER, LogSource.SIGNALING,
                EventTime.absolute(T0.plusMillis(millis), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, "INIT_CALL",
                Map.of("latencyMs", String.valueOf(latencyMs)), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, n + 1, "INIT_CALL"));
    }

    private static CanonicalEvent summary(Leg leg, Map<String, String> fields) {
        return new CanonicalEvent("endcall#1", "CALL-1", leg, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:01:00Z"), ClockDomain.CLIENT_CALLEE),
                EventType.CALL_SUMMARY, "CALL_SUMMARY", fields, Severity.INFO,
                new SourceRef(leg.name().toLowerCase() + "_endcall.log", 50, "raw"));
    }

    private CallTimeline timelineOf(List<CanonicalEvent> events) {
        return timelineBuilder.build("CALL-1", events, LegAssignment.unknown());
    }

    @Test
    @DisplayName("khoảng thời gian tính ở độ chính xác NANO rồi mới cắt về mili")
    void durationsUseNanosecondPrecision() {
        // Cắt từng Instant về mili trước khi trừ sẽ ra 8582 thay vì 8581.
        // Lỗi này từng xuất hiện trên 5/20 cuộc gọi thật.
        CallTimeline timeline = timelineOf(List.of(
                sig("2026-09-21T08:00:00.999999000Z", "INIT_CALL", Leg.CALLER),
                sig("2026-09-21T08:00:09.581000000Z", "OK_ACK_OK", Leg.CALLEE)));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.SETUP_TIME).display())
                .isEqualTo("8581 ms");
    }

    @Test
    @DisplayName("đếm số LẦN GỬI, không đếm số dòng log — gom cụm theo khoảng 250 ms")
    void retransmissionsCountTransmissionsNotLogLines() {
        // Tái hiện EE129C8F: 10 sự kiện INVITE nhưng chỉ 4 lần gửi thật sự,
        // cách nhau 0,5s / 1,0s / 2,0s theo exponential backoff.
        List<CanonicalEvent> events = new ArrayList<>();
        long[] clusters = {0, 500, 1500, 3500};
        for (long base : clusters) {
            events.add(sigAt(base, "INVITE", Leg.CALLER));
            events.add(sigAt(base + 6, "INVITE", Leg.CALLER));
            events.add(sigAt(base + 20, "INVITE", Leg.CALLER));
        }

        assertThat(calculator.calculate(timelineOf(events))
                .valueOf(MetricKey.INVITE_RETRANSMISSIONS).display())
                .isEqualTo("3 lần");
    }

    @Test
    @DisplayName("một lần gửi duy nhất với nhiều dòng log -> 0 lần gửi lại")
    void singleTransmissionMeansZeroRetransmissions() {
        // Tái hiện 1B009D42: 10 sự kiện INIT_CALL trong 16 ms — một lần gửi.
        List<CanonicalEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(sigAt(i * 2L, "INIT_CALL", Leg.CALLER));
        }

        assertThat(calculator.calculate(timelineOf(events))
                .valueOf(MetricKey.INVITE_RETRANSMISSIONS))
                .isInstanceOf(MetricValue.NotAvailable.class);
    }

    @Test
    @DisplayName("bên kết thúc lấy từ leg của BYE đầu tiên")
    void terminatedByComesFromFirstBye() {
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                sigAt(5000, "OK_ACK_OK", Leg.CALLEE),
                sigAt(40000, "BYE", Leg.CALLEE)));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.TERMINATED_BY).display())
                .isEqualTo("CALLEE");
    }

    @Test
    @DisplayName("chỉ số chất lượng đọc từ bản ghi call summary, jitter đổi giây sang mili")
    void qualityMetricsReadFromCallSummary() {
        // Giá trị thật của EE129C8F callee.
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                summary(Leg.CALLEE, Map.of(
                        "audio.audioMos", "4.41127",
                        "audio.packetLostPercent", "0",
                        "transport.currentRttMs", "69",
                        "audio.jitter", "0.018",
                        "transport.rttMs", "976"))));

        CallMetrics metrics = calculator.calculate(timeline);
        assertThat(metrics.find(MetricKey.MOS, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("4.41127");
        assertThat(metrics.find(MetricKey.RTT, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("69 ms");
        assertThat(metrics.find(MetricKey.JITTER, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("18 ms");
    }

    @Test
    @DisplayName("leg chưa từng có media (2D9057AA): số 0 trong summary là giá trị trống -> N/A kèm lý do")
    void zerosOfNeverConnectedLegAreNotAvailable() {
        // Giá trị thật của 2D9057AA callee: app ghi 0 cho mọi chỉ số vì chưa đo được gì.
        CallMetrics metrics = calculator.calculate(timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                summary(Leg.CALLEE, Map.of(
                        "audio.audioMos", "0",
                        "audio.packetLostPercent", "0",
                        "transport.currentRttMs", "0",
                        "audio.jitter", "0",
                        "audio.packetsReceived", "0",
                        "transport.localStunResponse", "0")))));

        for (MetricKey key : List.of(MetricKey.MOS, MetricKey.PACKET_LOSS, MetricKey.JITTER)) {
            assertThat(metrics.find(key, Leg.CALLEE)).get()
                    .extracting(m -> m.value().display()).asString()
                    .startsWith("N/A").contains("packetsReceived = 0");
        }
        assertThat(metrics.find(MetricKey.RTT, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).asString()
                .startsWith("N/A").contains("localStunResponse = 0");
    }

    @Test
    @DisplayName("leg có media mà mất 0% gói thật (DE7DD314) -> vẫn hiển thị 0 %, không bị đổi thành N/A")
    void genuineZeroLossIsKept() {
        CallMetrics metrics = calculator.calculate(timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                summary(Leg.CALLEE, Map.of(
                        "audio.audioMos", "4.42201",
                        "audio.packetLostPercent", "0",
                        "transport.currentRttMs", "63",
                        "audio.packetsReceived", "17974",
                        "transport.localStunResponse", "145")))));

        assertThat(metrics.find(MetricKey.PACKET_LOSS, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("0 %");
        assertThat(metrics.find(MetricKey.MOS, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("4.42201");
    }

    @Test
    @DisplayName("RTT lấy currentRttMs, KHÔNG lấy rttMs vì đó là giá trị tích luỹ")
    void rttUsesCurrentNotCumulative() {
        // DE7DD314: rttMs = 8385 trong khi RTT thật là 63.
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                summary(Leg.CALLEE, Map.of("transport.rttMs", "8385", "transport.currentRttMs", "63"))));

        assertThat(calculator.calculate(timeline).find(MetricKey.RTT, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("63 ms");
    }

    @Test
    @DisplayName("thiếu dữ liệu trả N/A kèm lý do CỤ THỂ, không bao giờ về 0")
    void missingDataNeverBecomesZero() {
        CallMetrics metrics = calculator.calculate(timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER))));

        // Không có end call log
        assertThat(metrics.find(MetricKey.MOS, Leg.CALLEE)).get()
                .extracting(m -> m.value().display())
                .asString().contains("không có end call log");

        // Cuộc gọi chết sớm trước khi đạt OK_ACK_OK
        assertThat(metrics.valueOf(MetricKey.SETUP_TIME).display())
                .contains("không đạt tới OK_ACK_OK");

        // Chỉ số MVP yêu cầu nhưng data không hỗ trợ
        assertThat(metrics.valueOf(MetricKey.NO_SESSIONS_FOUND).display())
                .contains("không có trường text");

        // Chỉ số ĐẾM được phép bằng 0 — "đếm được và bằng 0" khác "không đo được".
        // Các chỉ số ĐO LƯỜNG thì không bao giờ được mặc định về 0.
        List<MetricKey> countingKeys = List.of(
                MetricKey.INVITE_RETRANSMISSIONS, MetricKey.BYE_RETRANSMISSIONS,
                MetricKey.WARN_COUNT_BY_SERVICE, MetricKey.ERROR_COUNT_BY_SERVICE);

        assertThat(metrics.metrics()).noneMatch(m ->
                m.value() instanceof MetricValue.Present p
                        && p.value().signum() == 0
                        && !countingKeys.contains(m.key()));
    }

    @Test
    @DisplayName("ICE báo trạng thái ĐẠT ĐƯỢC — cuộc gọi tốt kết thúc ở disconnected vẫn là connected")
    void iceReportsStateReachedNotFinalState() {
        CanonicalEvent connected = new CanonicalEvent("w#1", "CALL-1", Leg.CALLEE, LogSource.WEBRTC,
                EventTime.relative(java.time.Duration.ofMillis(1000)), EventType.ICE_EVENT,
                "IceConnectionState", Map.of("iceStateTo", "connected", "platform", "ios"),
                Severity.INFO, new SourceRef("callee_webrtc.log", 1, "x"));
        CanonicalEvent disconnected = new CanonicalEvent("w#2", "CALL-1", Leg.CALLEE, LogSource.WEBRTC,
                EventTime.relative(java.time.Duration.ofMillis(40000)), EventType.ICE_EVENT,
                "IceConnectionState", Map.of("iceStateTo", "disconnected", "platform", "ios"),
                Severity.INFO, new SourceRef("callee_webrtc.log", 2, "x"));

        assertThat(calculator.calculate(timelineOf(List.of(connected, disconnected)))
                .find(MetricKey.ICE_FINAL_STATE, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("connected");
    }

    @Test
    @DisplayName("ICE thất bại thì báo failed")
    void iceFailureIsReported() {
        CanonicalEvent failed = new CanonicalEvent("w#1", "CALL-1", Leg.CALLEE, LogSource.WEBRTC,
                EventTime.relative(java.time.Duration.ofMillis(20195)), EventType.ICE_EVENT,
                "IceConnectionState", Map.of("iceStateTo", "failed", "platform", "android"),
                Severity.ERROR, new SourceRef("callee_webrtc.log", 1, "x"));

        assertThat(calculator.calculate(timelineOf(List.of(failed)))
                .find(MetricKey.ICE_FINAL_STATE, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("failed");
    }

    @Test
    @DisplayName("T11: khoảng trống PAIR_PING lớn nhất, đánh dấu là chỉ số PROXY")
    void maxPairPingGapIsMarkedAsProxy() {
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "PAIR_PING", Leg.CALLER),
                sigAt(1000, "PAIR_PING", Leg.CALLER),
                sigAt(6000, "PAIR_PING", Leg.CALLER),   // khoang trong 5000 ms
                sigAt(7000, "PAIR_PING", Leg.CALLER)));

        assertThat(calculator.calculate(timeline).find(MetricKey.MAX_PAIR_PING_GAP, Leg.CALLER))
                .get().extracting(m -> m.value().display()).isEqualTo("5000 ms");
        assertThat(MetricKey.MAX_PAIR_PING_GAP.isProxy()).isTrue();
    }

    @Test
    @DisplayName("T11: dưới 2 PAIR_PING thì không đo được khoảng trống")
    void singlePairPingCannotMeasureGap() {
        CallTimeline timeline = timelineOf(List.of(sigAt(0, "PAIR_PING", Leg.CALLER)));

        assertThat(calculator.calculate(timeline)
                .find(MetricKey.MAX_PAIR_PING_GAP, Leg.CALLER).orElseThrow()
                .value().display()).contains("chỉ có 1 PAIR_PING");
    }

    @Test
    @DisplayName("T11: WARN đếm theo service, KHÔNG được coi là tín hiệu lỗi")
    void warnIsCountedButNotTreatedAsError() {
        // 223/1059 sự kiện trong data mẫu là WARN, có cả ở cuộc gọi thành công.
        CallTimeline timeline = timelineOf(List.of(
                sigWarn(0, "INIT_CALL", "SVC-A"),
                sigWarn(10, "INIT_CALL", "SVC-A"),
                sigWarn(20, "INIT_CALL", "SVC-B")));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.WARN_COUNT_BY_SERVICE).display())
                .isEqualTo("3 (SVC-A=2, SVC-B=1)");
        assertThat(calculator.calculate(timeline).valueOf(MetricKey.ERROR_COUNT_BY_SERVICE).display())
                .isEqualTo("0 lần");
    }

    @Test
    @DisplayName("T11: ISP/ASN/quốc gia lấy theo từng leg")
    void networkContextIsPerLeg() {
        CallTimeline timeline = timelineOf(List.of(
                sigCtx(0, "INIT_CALL", Leg.CALLER, "MOBIFONE", "AS131429", "VN"),
                sigCtx(100, "RINGING", Leg.CALLEE, "FPT", "AS18403", "VN")));

        CallMetrics m = calculator.calculate(timeline);
        assertThat(m.find(MetricKey.NETWORK_CONTEXT, Leg.CALLER)).get()
                .extracting(x -> x.value().display()).isEqualTo("MOBIFONE / AS131429 / VN");
        assertThat(m.find(MetricKey.NETWORK_CONTEXT, Leg.CALLEE)).get()
                .extracting(x -> x.value().display()).isEqualTo("FPT / AS18403 / VN");
    }

    @Test
    @DisplayName("T11: latency API nội bộ lấy từ trường latencyMs của INIT_CALL")
    void internalApiLatencyFromInitCall() {
        CallTimeline timeline = timelineOf(List.of(
                sigLatency(0, 12), sigLatency(10, 16), sigLatency(20, 17)));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.INTERNAL_API_LATENCY).display())
                .isEqualTo("max 17 ms, trung vị 16 ms (3 mẫu)");
    }

    @Test
    @DisplayName("timeline rỗng không làm crash, mọi chỉ số đều N/A")
    void emptyTimelineIsSafe() {
        CallMetrics metrics = calculator.calculate(timelineOf(List.of()));

        assertThat(metrics.availableCount()).isZero();
        assertThat(metrics.unavailableCount()).isEqualTo(metrics.metrics().size());
    }
}
