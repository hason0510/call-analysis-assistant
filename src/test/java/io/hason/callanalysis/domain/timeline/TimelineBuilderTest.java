package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineBuilderTest {

    private final TimelineBuilder builder = new TimelineBuilder();

    private static CanonicalEvent signaling(int ordinal, String isoTime, String cmd, Leg leg) {
        return new CanonicalEvent("signaling.json#" + ordinal, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse(isoTime), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef("signaling.json", ordinal + 1, isoTime + " " + cmd));
    }

    private static CanonicalEvent endCall(String file, int line, long epochMillis,
                                          String name, Leg leg, Map<String, String> attrs) {
        return new CanonicalEvent(file + "#" + line, "CALL-1", leg, LogSource.ENDCALL,
                EventTime.absolute(Instant.ofEpochMilli(epochMillis), ClockDomain.CLIENT_CALLER),
                EventType.SIGNALING_COMMAND, name, attrs, Severity.INFO,
                new SourceRef(file, line, "raw-" + line));
    }

    private static CanonicalEvent webRtc(String file, int line, long millis,
                                         String message, String platform) {
        return new CanonicalEvent(file + "#" + line, "CALL-1", Leg.UNKNOWN, LogSource.WEBRTC,
                EventTime.relative(Duration.ofMillis(millis)),
                EventType.LOG_MESSAGE, "LOG", Map.of("platform", platform, "message", message),
                Severity.INFO, new SourceRef(file, line, message));
    }

    private static CanonicalEvent metadata(String file, String role, String platform) {
        return new CanonicalEvent(file + "#1", "CALL-1", Leg.UNKNOWN, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:00Z"), ClockDomain.CLIENT_CALLER),
                EventType.CALL_METADATA, "CALL_METADATA",
                Map.of("role", role, "platform", platform), Severity.INFO,
                new SourceRef(file, 1, "meta"));
    }

    @Test
    @DisplayName("su kien co gio tuyet doi vao main track, WebRTC tach thanh track rieng")
    void relativeEventsGoToSeparateTracks() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                signaling(0, "2026-09-21T08:00:01Z", "INIT_CALL", Leg.CALLER),
                endCall("caller_endcall.log", 5, 1789700842000L, "INVITE", Leg.CALLER, Map.of()),
                webRtc("caller_webrtc.log", 1, 100, "khoi dong", "android")),
                LegAssignment.unknown());

        assertThat(timeline.mainTrack()).hasSize(2);
        assertThat(timeline.relativeTracks()).hasSize(1);
        assertThat(timeline.relativeTracks().getFirst().fileName()).isEqualTo("caller_webrtc.log");
        assertThat(timeline.totalEvents()).isEqualTo(3);
    }

    @Test
    @DisplayName("main track sap xep theo thoi gian, tron ngau nhien dau vao van cho cung ket qua")
    void mainTrackOrderIsStableRegardlessOfInputOrder() {
        List<CanonicalEvent> events = new ArrayList<>(List.of(
                signaling(2, "2026-09-21T08:00:03Z", "RINGING", Leg.CALLEE),
                signaling(0, "2026-09-21T08:00:01Z", "INIT_CALL", Leg.CALLER),
                signaling(1, "2026-09-21T08:00:02Z", "INVITE", Leg.CALLER)));

        List<String> expected = List.of("INIT_CALL", "INVITE", "RINGING");
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(events, new java.util.Random(i));
            assertThat(builder.build("CALL-1", events, LegAssignment.unknown())
                    .mainTrack().stream().map(CanonicalEvent::name).toList())
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("dong log lap lai trong cung file KHONG bi coi la trung — libwebrtc ghi that")
    void repeatedLinesInSameFileAreKept() {
        // "TLS server done" that su xuat hien 3 lan cung mili giay trong data mau.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 10, 6661648, "TLS server done", "android"),
                webRtc("caller_webrtc.log", 11, 6661648, "TLS server done", "android"),
                webRtc("caller_webrtc.log", 12, 6661648, "TLS server done", "android")),
                LegAssignment.unknown());

        assertThat(timeline.totalEvents()).isEqualTo(3);
        assertThat(timeline.notes()).noneMatch(n -> n.kind() == TimelineNote.Kind.DEDUPED);
    }

    @Test
    @DisplayName("cung su kien o signaling va end call log la HAI GOC NHIN, giu ca hai")
    void sameEventFromTwoSourcesIsKept() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                signaling(0, "2026-09-21T08:00:01Z", "INVITE", Leg.CALLER),
                endCall("caller_endcall.log", 5, Instant.parse("2026-09-21T08:00:01Z").toEpochMilli(),
                        "INVITE", Leg.CALLER, Map.of())),
                LegAssignment.unknown());

        assertThat(timeline.mainTrack()).hasSize(2);
        assertThat(timeline.mainTrack()).extracting(CanonicalEvent::source)
                .containsExactlyInAnyOrder(LogSource.SIGNALING, LogSource.ENDCALL);
    }

    @Test
    @DisplayName("cung mot file dinh kem hai lan duoi hai ten -> bo file thu hai")
    void duplicateAttachmentIsDropped() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 1, 100, "mot", "android"),
                webRtc("caller_webrtc.log", 2, 200, "hai", "android"),
                webRtc("ban_sao.log", 1, 100, "mot", "android"),
                webRtc("ban_sao.log", 2, 200, "hai", "android")),
                LegAssignment.unknown());

        assertThat(timeline.totalEvents()).isEqualTo(2);
        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.DEDUPED)
                .singleElement()
                .satisfies(n -> assertThat(n.message()).contains("ban_sao.log"));
    }

    @Test
    @DisplayName("file WebRTC sai ten duoc gan lai leg theo platform, khong theo ten")
    void misnamedWebRtcFileIsRetaggedByContent() {
        // Tai hien `calleer_webrtc.log` trong data mau: ten goi y CALLEE, thuc te la CALLER.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                metadata("caller_endcall.log", "caller", "android"),
                metadata("callee_endcall.log", "callee", "ios"),
                webRtc("calleer_webrtc.log", 1, 100, "log cua may Android", "android")),
                LegAssignment.unknown());

        RelativeTrack track = timeline.relativeTracks().getFirst();
        assertThat(track.fileName()).isEqualTo("calleer_webrtc.log");
        assertThat(track.leg()).isEqualTo(Leg.CALLER);
        assertThat(track.legConfidence()).isEqualTo(RelativeTrack.LegConfidence.MATCHED_BY_PLATFORM);
        assertThat(track.events().getFirst().leg()).isEqualTo(Leg.CALLER);
    }

    @Test
    @DisplayName("WebRTC log luon sinh ghi chu ve moc thoi gian tuong doi")
    void relativeTrackAlwaysProducesLimitationNote() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 1, 100, "x", "android")), LegAssignment.unknown());

        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.RELATIVE_TRACK)
                .singleElement()
                .satisfies(n -> assertThat(n.message()).contains("chua dong bo duoc"));
    }

    @Test
    @DisplayName("danh sach su kien rong van tra ve timeline hop le kem ghi chu")
    void emptyInputIsSafe() {
        CallTimeline timeline = builder.build("CALL-1", List.of(), LegAssignment.unknown());

        assertThat(timeline.totalEvents()).isZero();
        assertThat(timeline.notes()).anyMatch(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION);
        assertThat(builder.build("CALL-1", null, null).totalEvents()).isZero();
    }

    @Test
    @DisplayName("tien ich tra cuu lenh signaling dung cho T6")
    void signalingLookupHelpers() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                signaling(0, "2026-09-21T08:00:01Z", "INVITE", Leg.CALLER),
                signaling(1, "2026-09-21T08:00:02Z", "INVITE", Leg.CALLER),
                signaling(2, "2026-09-21T08:00:05Z", "OK_ACK_OK", Leg.CALLEE)),
                LegAssignment.unknown());

        assertThat(timeline.countSignaling("INVITE")).isEqualTo(2);
        assertThat(timeline.firstSignaling("INVITE")).get()
                .extracting(e -> e.sourceRef().lineNumber()).isEqualTo(1);
        assertThat(timeline.lastSignaling("INVITE")).get()
                .extracting(e -> e.sourceRef().lineNumber()).isEqualTo(2);
        assertThat(timeline.firstSignaling("BYE")).isEmpty();
    }
}
