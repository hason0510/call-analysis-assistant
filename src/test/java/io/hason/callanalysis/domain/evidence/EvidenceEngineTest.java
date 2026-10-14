package io.hason.callanalysis.domain.evidence;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEngineTest {

    private final EvidenceEngine engine = new EvidenceEngine();

    private static CallTimeline timelineWithSummary(Map<String, String> fields) {
        CanonicalEvent summary = new CanonicalEvent("callee_endcall.log#185", "CALL-1", Leg.CALLEE,
                LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:01:00Z"), ClockDomain.CLIENT_CALLEE),
                EventType.CALL_SUMMARY, "CALL_SUMMARY", fields, Severity.INFO,
                new SourceRef("callee_endcall.log", 185, "raw"));
        return new TimelineBuilder().build("CALL-1", List.of(summary), LegAssignment.unknown());
    }

    @Test
    @DisplayName("summary của leg chưa nhận gói nào (2D9057AA) không in MOS=0 — khớp với bảng chỉ số N/A")
    void summaryWithoutPacketsDoesNotPrintZeroMos() {
        List<Evidence> evidence = engine.collect(timelineWithSummary(Map.of(
                "audio.audioMos", "0", "audio.packetLostPercent", "0", "transport.currentRttMs", "0",
                "audio.packetsReceived", "0", "audio.bytesReceived", "0", "transport.hasMediaFail", "1")));

        assertThat(evidence).singleElement().satisfies(e -> assertThat(e.description())
                .contains("không nhận được gói audio nào")
                .contains("mediaFail=1")
                .doesNotContain("MOS=0"));
    }

    @Test
    @DisplayName("summary của leg có media (DE7DD314) vẫn in đủ MOS, loss, RTT")
    void summaryWithPacketsPrintsMeasuredValues() {
        List<Evidence> evidence = engine.collect(timelineWithSummary(Map.of(
                "audio.audioMos", "4.42201", "audio.packetLostPercent", "0", "transport.currentRttMs", "63",
                "audio.packetsReceived", "17974", "audio.bytesReceived", "1357527")));

        assertThat(evidence).singleElement().satisfies(e -> assertThat(e.description())
                .contains("MOS=4.42201").contains("RTT=63ms"));
    }

    @Test
    @DisplayName("callee hai thiết bị: evidence ghi rõ máy nào có âm thanh, máy nào không")
    void multiDeviceEvidenceIsTaggedPerDevice() {
        CanonicalEvent silent = new CanonicalEvent("callee_b_endcall.log#26", "CALL-1", Leg.CALLEE, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:10Z"), ClockDomain.CLIENT_CALLEE),
                EventType.CALL_SUMMARY, "CALL_SUMMARY",
                Map.of("audio.packetsReceived", "0", "audio.bytesReceived", "0", "transport.hasMediaFail", "0"),
                Severity.INFO, new SourceRef("callee_b_endcall.log", 26, "raw"));
        CanonicalEvent answered = new CanonicalEvent("callee_a_endcall.log#111", "CALL-1", Leg.CALLEE, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:30Z"), ClockDomain.CLIENT_CALLEE),
                EventType.CALL_SUMMARY, "CALL_SUMMARY",
                Map.of("audio.audioMos", "3.921", "audio.packetsReceived", "2000", "audio.bytesReceived", "47747",
                        "transport.currentRttMs", "551"),
                Severity.INFO, new SourceRef("callee_a_endcall.log", 111, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(silent, answered), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).extracting(Evidence::description).containsExactly(
                "[thiết bị callee_b_endcall.log, không có âm thanh] Call summary callee: không nhận được gói audio"
                        + " nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được",
                "[thiết bị callee_a_endcall.log, có âm thanh, dùng tính chỉ số callee] Call summary callee:"
                        + " MOS=3.921, loss cả cuộc=-, RTT=551ms, bytesRecv=47747");
    }

    @Test
    @DisplayName("tín hiệu mạng app ghi (network_lost / network_poor) vào evidence, ghi rõ bên nào; tín hiệu khác thì không")
    void networkSignalsAreEvidence() {
        CanonicalEvent lost = new CanonicalEvent("caller_endcall.log#140", "CALL-1", Leg.CALLER, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:10Z"), ClockDomain.CLIENT_CALLER),
                EventType.USER_ACTION, "network_lost", Map.of("originator", "remote", "signal", "network_lost"),
                Severity.INFO, new SourceRef("caller_endcall.log", 140, "raw"));
        CanonicalEvent poor = new CanonicalEvent("callee_endcall.log#90", "CALL-1", Leg.CALLEE, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:05Z"), ClockDomain.CLIENT_CALLEE),
                EventType.USER_ACTION, "network_poor", Map.of("originator", "local", "signal", "network_poor"),
                Severity.INFO, new SourceRef("callee_endcall.log", 90, "raw"));
        CanonicalEvent unmuted = new CanonicalEvent("caller_endcall.log#60", "CALL-1", Leg.CALLER, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:01Z"), ClockDomain.CLIENT_CALLER),
                EventType.USER_ACTION, "unmuted", Map.of("originator", "remote", "signal", "unmuted"),
                Severity.INFO, new SourceRef("caller_endcall.log", 60, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(lost, poor, unmuted), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).extracting(Evidence::description).containsExactly(
                "Tín hiệu app: network_poor, originator=local (máy này)",
                "Tín hiệu app: network_lost, originator=remote (bên kia)");
    }

    @Test
    @DisplayName("một thiết bị mỗi bên: evidence không gắn nhãn thiết bị")
    void singleDeviceEvidenceIsNotTagged() {
        assertThat(engine.collect(timelineWithSummary(Map.of("audio.bytesReceived", "100", "audio.packetsReceived", "5"))))
                .singleElement().satisfies(e -> assertThat(e.description()).doesNotContain("thiết bị"));
    }

    @Test
    @DisplayName("dòng căn cứ TURN vào evidence nhưng bỏ địa chỉ TURN server và IP mạng nội bộ")
    void turnBasisIsCitedWithoutAddresses() {
        CanonicalEvent turn = new CanonicalEvent("caller_webrtc.log#208", "CALL-1", Leg.CALLER,
                LogSource.WEBRTC, EventTime.relative(java.time.Duration.ofMillis(209)),
                EventType.TURN_EVENT, "TurnPort",
                Map.of("message", "TurnPort(Port[3b034600:0:1:0:relay:Net[wlan0:192.168.26.x/24:Wifi:id=3]]"
                        + "-Remote[203.0.113.227:3478/udp]: Failed to create TURN client socket"),
                Severity.INFO, new SourceRef("caller_webrtc.log", 208, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(turn), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).isEmpty();   // không phải căn cứ thì không tự vào
        assertThat(engine.collect(timeline, List.of(turn))).singleElement().satisfies(e -> {
            assertThat(e.citation()).isEqualTo("[EV01][caller_webrtc.log:208]");
            assertThat(e.description()).isEqualTo("TURN: Failed to create TURN client socket");
        });
    }

    @Test
    @DisplayName("loss trong dòng call summary là tỉ lệ CẢ CUỘC từ bộ đếm, khớp bảng chỉ số — không phải 0% của khoảng cuối")
    void summaryLossIsWholeCall() {
        // 271D1FAF caller: packetLostPercent = 0 nhưng bộ đếm cho 74 / 4 044 gói = 1,83 %
        List<Evidence> evidence = engine.collect(timelineWithSummary(Map.of(
                "audio.audioMos", "4.15346", "audio.packetLostPercent", "0", "transport.currentRttMs", "16",
                "audio.packetsLost", "74", "audio.packetsReceived", "3970", "audio.bytesReceived", "177699")));

        assertThat(evidence).singleElement().satisfies(e -> assertThat(e.description())
                .contains("loss cả cuộc=1.83%").doesNotContain("loss=0%"));
    }

    @Test
    @DisplayName("dòng mẫu stats ghi rõ 'loss trong khoảng' để không lẫn với loss cả cuộc của summary")
    void statsSampleLossIsLabelledAsInterval() {
        // 271D1FAF caller dòng 108: mất 6 / (6 + 47) gói trong khoảng đó = 11,3208 %
        CanonicalEvent sample = new CanonicalEvent("caller_endcall.log#108", "CALL-1", Leg.CALLER,
                LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:30Z"), ClockDomain.CLIENT_CALLER),
                EventType.MEDIA_STATS, "STATS",
                Map.of("audio.audioMos", "4.19547", "audio.packetLostPercent", "11.3208", "audio.bytesReceived", "59244"),
                Severity.INFO, new SourceRef("caller_endcall.log", 108, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(sample), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).singleElement().satisfies(e -> assertThat(e.description())
                .contains("loss trong khoảng=11.3208%"));
    }

    @Test
    @DisplayName("dòng căn cứ ghi nhận VPN chỉ nêu tên giao diện, không in địa chỉ")
    void vpnBasisIsCitedWithoutAddresses() {
        CanonicalEvent vpn = new CanonicalEvent("caller_webrtc.log#164", "CALL-1", Leg.CALLER,
                LogSource.WEBRTC, EventTime.relative(java.time.Duration.ofMillis(163)),
                EventType.LOG_MESSAGE, "LOG",
                Map.of("message", "Net[tun0:172.16.0.x/32:VPN/Wifi:id=4]"),
                Severity.INFO, new SourceRef("caller_webrtc.log", 164, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(vpn), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).isEmpty();   // không phải căn cứ thì không tự vào
        assertThat(engine.collect(timeline, List.of(vpn))).singleElement().satisfies(e -> {
            assertThat(e.citation()).isEqualTo("[EV01][caller_webrtc.log:164]");
            assertThat(e.description()).isEqualTo("Mạng: libwebrtc ghi nhận giao diện VPN tun0");
        });
    }

    @Test
    @DisplayName("dòng _emitFailed (mã và lý do thất bại do app ghi) luôn vào evidence như _emitBye")
    void emitFailedLineIsEvidence() {
        CanonicalEvent failed = new CanonicalEvent("caller_endcall.log#28", "CALL-1", Leg.CALLER,
                LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:01:00Z"), ClockDomain.CLIENT_CALLER),
                EventType.LOG_MESSAGE, "_emitFailed",
                Map.of("msg", "_emitFailed with originator: 0 reason: call.outgoing.error.network_check"
                        + " endReason: 0 code: 421 open:true"),
                Severity.INFO, new SourceRef("caller_endcall.log", 28, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(failed), LegAssignment.unknown());

        assertThat(engine.collect(timeline)).singleElement().satisfies(e -> assertThat(e.description())
                .startsWith("Thất bại phía client:").contains("code: 421"));
    }

    @Test
    @DisplayName("T1b — evidence đánh dấu ruleBasis khi là dòng căn cứ của rule, kể cả khi trùng một mốc chọn cố định")
    void ruleBasisIsMarked() {
        CanonicalEvent failed = new CanonicalEvent("caller_endcall.log#28", "CALL-1", Leg.CALLER,
                LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:01:00Z"), ClockDomain.CLIENT_CALLER),
                EventType.LOG_MESSAGE, "_emitFailed", Map.of("msg", "_emitFailed code: 421"),
                Severity.INFO, new SourceRef("caller_endcall.log", 28, "raw"));
        CanonicalEvent timer = new CanonicalEvent("caller_endcall.log#27", "CALL-1", Leg.CALLER,
                LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:59Z"), ClockDomain.CLIENT_CALLER),
                EventType.LOG_MESSAGE, "log_detail", Map.of("msg", "_waitingCandidateTimer with error"),
                Severity.INFO, new SourceRef("caller_endcall.log", 27, "raw"));
        CallTimeline timeline = new TimelineBuilder().build("CALL-1", List.of(timer, failed), LegAssignment.unknown());

        assertThat(engine.collect(timeline, List.of(timer, failed)))
                .extracting(e -> e.event().sourceRef().lineNumber() + ":" + e.ruleBasis())
                .containsExactly("27:true", "28:true");
        assertThat(engine.collect(timeline)).extracting(Evidence::ruleBasis).containsExactly(false);
    }
}
