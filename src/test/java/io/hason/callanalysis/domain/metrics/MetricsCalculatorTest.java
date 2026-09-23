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
        return new CanonicalEvent("signaling.json#" + n, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse(isoTime), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef("signaling.json", n + 1, cmd));
    }

    private CanonicalEvent sigAt(long millisFromT0, String cmd, Leg leg) {
        int n = ordinal++;
        return new CanonicalEvent("signaling.json#" + n, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(T0.plusMillis(millisFromT0), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef("signaling.json", n + 1, cmd));
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
    @DisplayName("khoang thoi gian tinh o do chinh xac NANO roi moi cat ve mili")
    void durationsUseNanosecondPrecision() {
        // Cat tung Instant ve mili truoc khi tru se ra 8582 thay vi 8581.
        // Loi nay tung xuat hien tren 5/20 cuoc goi that.
        CallTimeline timeline = timelineOf(List.of(
                sig("2026-09-21T08:00:00.999999000Z", "INIT_CALL", Leg.CALLER),
                sig("2026-09-21T08:00:09.581000000Z", "OK_ACK_OK", Leg.CALLEE)));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.SETUP_TIME).display())
                .isEqualTo("8581 ms");
    }

    @Test
    @DisplayName("dem so LAN GUI, khong dem so dong log — gom cum theo khoang 250 ms")
    void retransmissionsCountTransmissionsNotLogLines() {
        // Tai hien EE129C8F: 10 su kien INVITE nhung chi 4 lan gui that su,
        // cach nhau 0,5s / 1,0s / 2,0s theo exponential backoff.
        List<CanonicalEvent> events = new ArrayList<>();
        long[] clusters = {0, 500, 1500, 3500};
        for (long base : clusters) {
            events.add(sigAt(base, "INVITE", Leg.CALLER));
            events.add(sigAt(base + 6, "INVITE", Leg.CALLER));
            events.add(sigAt(base + 20, "INVITE", Leg.CALLER));
        }

        assertThat(calculator.calculate(timelineOf(events))
                .valueOf(MetricKey.INVITE_RETRANSMISSIONS).display())
                .isEqualTo("3 lan");
    }

    @Test
    @DisplayName("mot lan gui duy nhat voi nhieu dong log -> 0 lan gui lai")
    void singleTransmissionMeansZeroRetransmissions() {
        // Tai hien 1B009D42: 10 su kien INIT_CALL trong 16 ms — mot lan gui.
        List<CanonicalEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(sigAt(i * 2L, "INIT_CALL", Leg.CALLER));
        }

        assertThat(calculator.calculate(timelineOf(events))
                .valueOf(MetricKey.INVITE_RETRANSMISSIONS))
                .isInstanceOf(MetricValue.NotAvailable.class);
    }

    @Test
    @DisplayName("ben ket thuc lay tu leg cua BYE dau tien")
    void terminatedByComesFromFirstBye() {
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                sigAt(5000, "OK_ACK_OK", Leg.CALLEE),
                sigAt(40000, "BYE", Leg.CALLEE)));

        assertThat(calculator.calculate(timeline).valueOf(MetricKey.TERMINATED_BY).display())
                .isEqualTo("CALLEE");
    }

    @Test
    @DisplayName("chi so chat luong doc tu ban ghi call summary, jitter doi giay sang mili")
    void qualityMetricsReadFromCallSummary() {
        // Gia tri that cua EE129C8F callee.
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
    @DisplayName("RTT lay currentRttMs, KHONG lay rttMs vi do la gia tri tich luy")
    void rttUsesCurrentNotCumulative() {
        // DE7DD314: rttMs = 8385 trong khi RTT that la 63.
        CallTimeline timeline = timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER),
                summary(Leg.CALLEE, Map.of("transport.rttMs", "8385", "transport.currentRttMs", "63"))));

        assertThat(calculator.calculate(timeline).find(MetricKey.RTT, Leg.CALLEE)).get()
                .extracting(m -> m.value().display()).isEqualTo("63 ms");
    }

    @Test
    @DisplayName("thieu du lieu tra N/A kem ly do CU THE, khong bao gio ve 0")
    void missingDataNeverBecomesZero() {
        CallMetrics metrics = calculator.calculate(timelineOf(List.of(
                sigAt(0, "INIT_CALL", Leg.CALLER))));

        // Khong co end call log
        assertThat(metrics.find(MetricKey.MOS, Leg.CALLEE)).get()
                .extracting(m -> m.value().display())
                .asString().contains("khong co end call log");

        // Cuoc goi chet som truoc khi dat OK_ACK_OK
        assertThat(metrics.valueOf(MetricKey.SETUP_TIME).display())
                .contains("khong dat toi OK_ACK_OK");

        // Chi so MVP yeu cau nhung data khong ho tro
        assertThat(metrics.valueOf(MetricKey.NO_SESSIONS_FOUND).display())
                .contains("khong co truong text");

        assertThat(metrics.metrics()).noneMatch(m ->
                m.value() instanceof MetricValue.Present p
                        && p.value().signum() == 0
                        && m.key() != MetricKey.INVITE_RETRANSMISSIONS
                        && m.key() != MetricKey.BYE_RETRANSMISSIONS);
    }

    @Test
    @DisplayName("ICE bao trang thai DAT DUOC — cuoc goi tot ket thuc o disconnected van la connected")
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
    @DisplayName("ICE that bai thi bao failed")
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
    @DisplayName("timeline rong khong lam crash, moi chi so deu N/A")
    void emptyTimelineIsSafe() {
        CallMetrics metrics = calculator.calculate(timelineOf(List.of()));

        assertThat(metrics.availableCount()).isZero();
        assertThat(metrics.unavailableCount()).isEqualTo(metrics.metrics().size());
    }
}
