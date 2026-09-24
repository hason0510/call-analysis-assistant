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
        return new CanonicalEvent("signaling#" + ordinal, "CALL-1", leg, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse(isoTime), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, ordinal + 1, isoTime + " " + cmd));
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
    @DisplayName("sự kiện có giờ tuyệt đối vào main track, WebRTC tách thành track riêng")
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
    @DisplayName("main track sắp xếp theo thời gian, trộn ngẫu nhiên đầu vào vẫn cho cùng kết quả")
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
    @DisplayName("dòng log lặp lại trong cùng file KHÔNG bị coi là trùng — libwebrtc ghi thật")
    void repeatedLinesInSameFileAreKept() {
        // "TLS server done" thật sự xuất hiện 3 lần cùng mili giây trong data mẫu.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 10, 6661648, "TLS server done", "android"),
                webRtc("caller_webrtc.log", 11, 6661648, "TLS server done", "android"),
                webRtc("caller_webrtc.log", 12, 6661648, "TLS server done", "android")),
                LegAssignment.unknown());

        assertThat(timeline.totalEvents()).isEqualTo(3);
        assertThat(timeline.notes()).noneMatch(n -> n.kind() == TimelineNote.Kind.DEDUPED);
    }

    @Test
    @DisplayName("cùng sự kiện ở signaling và end call log là HAI GÓC NHÌN, giữ cả hai")
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
    @DisplayName("cùng một file đính kèm hai lần dưới hai tên -> bỏ file thứ hai")
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
    @DisplayName("trùng nội dung thì giữ tên ĐÚNG QUY ƯỚC, không giữ tên đứng trước bảng chữ cái")
    void duplicateKeepsConventionalFileName() {
        // "ban_sao.log" đứng trước "callee_webrtc.log" theo thứ tự chữ cái, nhưng giữ nó
        // sẽ khiến evidence trích dẫn tên file vô nghĩa.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("ban_sao.log", 1, 100, "mot", "ios"),
                webRtc("ban_sao.log", 2, 200, "hai", "ios"),
                webRtc("callee_webrtc.log", 1, 100, "mot", "ios"),
                webRtc("callee_webrtc.log", 2, 200, "hai", "ios")),
                LegAssignment.unknown());

        assertThat(timeline.relativeTracks()).singleElement()
                .extracting(RelativeTrack::fileName).isEqualTo("callee_webrtc.log");
        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.DEDUPED)
                .singleElement()
                .satisfies(n -> assertThat(n.message()).contains("ban_sao.log"));
    }

    @Test
    @DisplayName("file WebRTC sai tên được gán lại leg theo platform, không theo tên")
    void misnamedWebRtcFileIsRetaggedByContent() {
        // Tái hiện `calleer_webrtc.log` trong data mẫu: tên gợi ý CALLEE, thực tế là CALLER.
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

    private static RelativeTrack trackOf(CallTimeline timeline, String fileName) {
        return timeline.relativeTracks().stream()
                .filter(t -> t.fileName().equals(fileName)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("C8CF631E: chỉ caller có end call log, cả hai bên iOS -> vai lấy từ SDP, không gán cả hai cho caller")
    void sdpRoleBeatsPlatformWhenOnlyOneEndCallLog() {
        // Luật so nền tảng từng gán cả hai file iOS cho leg duy nhất có end call log iOS.
        // callee_webrtc.log có "DoSetLocalDescription: answer" nên chắc chắn là callee.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                metadata("caller_endcall.log", "caller", "ios"),
                webRtc("caller_webrtc.log", 519, 100, "DoSetLocalDescription: offer", "ios"),
                webRtc("callee_webrtc.log", 366, 200, "DoSetLocalDescription: answer", "ios")),
                LegAssignment.unknown());

        assertThat(trackOf(timeline, "caller_webrtc.log").leg()).isEqualTo(Leg.CALLER);
        assertThat(trackOf(timeline, "callee_webrtc.log").leg()).isEqualTo(Leg.CALLEE);
        assertThat(trackOf(timeline, "callee_webrtc.log").legConfidence())
                .isEqualTo(RelativeTrack.LegConfidence.MATCHED_BY_SDP_ROLE);
    }

    @Test
    @DisplayName("0EC7B700: file có offer là caller; file không có SDP mới dùng luật nền tảng")
    void fileWithoutSdpFallsBackToPlatform() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                metadata("callee_endcall.log", "callee", "ios"),
                webRtc("caller_webrtc.log", 10, 100, "DoSetLocalDescription: offer", "ios"),
                webRtc("callee_webrtc.log", 10, 100, "Audio route changed", "ios")),
                LegAssignment.unknown());

        assertThat(trackOf(timeline, "caller_webrtc.log").leg()).isEqualTo(Leg.CALLER);
        assertThat(trackOf(timeline, "callee_webrtc.log").leg()).isEqualTo(Leg.CALLEE);
        assertThat(trackOf(timeline, "callee_webrtc.log").legConfidence())
                .isEqualTo(RelativeTrack.LegConfidence.MATCHED_BY_PLATFORM);
    }

    @Test
    @DisplayName("D114749E: file tên callee_webrtc.log không có SDP nhưng do máy caller ghi -> vẫn là CALLER")
    void misnamedFileWithoutSdpKeepsPlatformMatch() {
        // Chứng minh trên data: cùng deviceId với cuộc 1B009D42 của cùng người gọi, hai lần gọi
        // cách nhau 8,085 s ở đồng hồ tuyệt đối và 8,087 s ở đồng hồ tương đối của log.
        // Đổi sang "tin tên file" ở đây là làm sai một ca đang đúng.
        CallTimeline timeline = builder.build("CALL-1", List.of(
                metadata("caller_endcall.log", "caller", "ios"),
                webRtc("callee_webrtc.log", 1, 100, "Incrementing activation count.", "ios")),
                LegAssignment.unknown());

        assertThat(trackOf(timeline, "callee_webrtc.log").leg()).isEqualTo(Leg.CALLER);
    }

    @Test
    @DisplayName("đàm phán lại giữa cuộc gọi: vai lấy theo DoSetLocalDescription ĐẦU TIÊN")
    void firstLocalDescriptionDecidesRole() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("x_webrtc.log", 900, 9000, "DoSetLocalDescription: offer", "android"),
                webRtc("x_webrtc.log", 50, 500, "DoSetLocalDescription: answer", "android")),
                LegAssignment.unknown());

        assertThat(trackOf(timeline, "x_webrtc.log").leg()).isEqualTo(Leg.CALLEE);
    }

    @Test
    @DisplayName("leg xác định bằng SDP thì không bị ghi là 'chưa đối chiếu được chủ sở hữu'")
    void sdpResolvedTrackIsNotReportedAsUncertain() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 1, 100, "DoSetLocalDescription: offer", "ios")),
                LegAssignment.unknown());

        assertThat(timeline.notes())
                .noneMatch(n -> n.message().contains("chưa đối chiếu được chủ sở hữu"));
    }

    @Test
    @DisplayName("WebRTC log luôn sinh ghi chú về mốc thời gian tương đối")
    void relativeTrackAlwaysProducesLimitationNote() {
        CallTimeline timeline = builder.build("CALL-1", List.of(
                webRtc("caller_webrtc.log", 1, 100, "x", "android")), LegAssignment.unknown());

        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.RELATIVE_TRACK)
                .singleElement()
                .satisfies(n -> assertThat(n.message()).contains("chưa đồng bộ được"));
    }

    @Test
    @DisplayName("danh sách sự kiện rỗng vẫn trả về timeline hợp lệ kèm ghi chú")
    void emptyInputIsSafe() {
        CallTimeline timeline = builder.build("CALL-1", List.of(), LegAssignment.unknown());

        assertThat(timeline.totalEvents()).isZero();
        assertThat(timeline.notes()).anyMatch(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION);
        assertThat(builder.build("CALL-1", null, null).totalEvents()).isZero();
    }

    @Test
    @DisplayName("tiện ích tra cứu lệnh signaling dùng cho T6")
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
