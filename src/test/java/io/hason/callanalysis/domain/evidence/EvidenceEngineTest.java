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
}
