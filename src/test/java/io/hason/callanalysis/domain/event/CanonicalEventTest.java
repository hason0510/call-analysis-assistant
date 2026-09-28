package io.hason.callanalysis.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalEventTest {

    private static CanonicalEvent event(Map<String, String> attributes) {
        return new CanonicalEvent(
                "EV01", "CALL-1", Leg.CALLER, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse("2026-09-21T08:44:28.953756952Z"), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, "INVITE", attributes, Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, 12, "{}"));
    }

    @Test
    @DisplayName("attributes được sao chép phòng thủ, sửa map gốc không ảnh hưởng event")
    void attributesAreDefensivelyCopied() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("cmd", "INVITE");
        CanonicalEvent e = event(mutable);

        mutable.put("chen-them", "gia-tri-la");

        assertThat(e.attributes()).containsOnlyKeys("cmd");
        assertThatThrownBy(() -> e.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("attributes giữ nguyên thứ tự chèn — thứ tự đổi là mất tính tất định")
    void attributesPreserveInsertionOrder() {
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z", "1");
        ordered.put("a", "2");
        ordered.put("m", "3");

        assertThat(event(ordered).attributes().keySet()).containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("trường bắt buộc thiếu thì báo lỗi ngay, không âm thầm bỏ qua")
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> new CanonicalEvent(
                "EV01", "CALL-1", Leg.CALLER, LogSource.SIGNALING,
                EventTime.absolute(Instant.EPOCH, ClockDomain.SERVER),
                EventType.LOG_MESSAGE, "x", Map.of(), Severity.INFO,
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceRef");
    }

    @Test
    @DisplayName("giá trị tuỳ chọn vắng mặt thì dùng mặc định an toàn, attributes không bao giờ null")
    void optionalFieldsFallBackSafely() {
        CanonicalEvent e = new CanonicalEvent(
                "EV01", "CALL-1", null, LogSource.WEBRTC,
                EventTime.relative(Duration.ofMillis(15_652)),
                null, null, null, null,
                new SourceRef("caller_webrtc.log", 3, "..."));

        assertThat(e.leg()).isEqualTo(Leg.UNKNOWN);
        assertThat(e.type()).isEqualTo(EventType.LOG_MESSAGE);
        assertThat(e.severity()).isEqualTo(Severity.INFO);
        assertThat(e.attributes()).isEmpty();
        assertThat(e.attribute("khong-ton-tai")).isNull();
    }

    @Test
    @DisplayName("gán lại leg thì đồng hồ client đi theo máy thật; đồng hồ server và giờ tương đối giữ nguyên")
    void withLegMovesClientClockAlong() {
        Instant t = Instant.parse("2026-09-21T08:01:00Z");
        CanonicalEvent endcall = new CanonicalEvent(
                "E1", "CALL-1", Leg.CALLEE, LogSource.ENDCALL,
                EventTime.absolute(t, ClockDomain.CLIENT_CALLEE),
                EventType.LOG_MESSAGE, "x", Map.of(), Severity.INFO,
                new SourceRef("callee_endcall.log", 9, "..."));

        assertThat(endcall.withLeg(Leg.CALLER).time())
                .isEqualTo(EventTime.absolute(t, ClockDomain.CLIENT_CALLER));
        // File không có tiền tố caller_/callee_: lúc parse chỉ gán tạm LOG_RELATIVE dù giờ là tuyệt đối.
        CanonicalEvent unprefixed = new CanonicalEvent(
                "E2", "CALL-1", Leg.UNKNOWN, LogSource.ENDCALL,
                EventTime.absolute(t, ClockDomain.LOG_RELATIVE),
                EventType.LOG_MESSAGE, "x", Map.of(), Severity.INFO,
                new SourceRef("endcall.log", 9, "..."));
        assertThat(unprefixed.withLeg(Leg.CALLEE).time())
                .isEqualTo(EventTime.absolute(t, ClockDomain.CLIENT_CALLEE));
        assertThat(event(Map.of()).withLeg(Leg.CALLEE).time().sortKeyNanos())
                .isEqualTo(event(Map.of()).time().sortKeyNanos());
        assertThat(((EventTime.Absolute) event(Map.of()).withLeg(Leg.CALLEE).time()).clock())
                .isEqualTo(ClockDomain.SERVER);
    }

    @Test
    @DisplayName("khoá sắp xếp của event tương đối nhỏ hơn mọi event tuyệt đối — hai loại không xen kẽ")
    void relativeKeyIsBelowEveryAbsoluteKey() {
        EventTime absolute = EventTime.absolute(Instant.parse("1970-01-01T00:00:00Z"), ClockDomain.SERVER);
        EventTime relative = EventTime.relative(Duration.ofSeconds(6652));

        assertThat(List.of(absolute, relative).stream()
                .sorted(java.util.Comparator.comparingLong(EventTime::sortKeyNanos))
                .toList())
                .containsExactly(relative, absolute);
    }

    @Test
    @DisplayName("SourceRef đúng định dạng trích dẫn của mẫu report mục 4.5")
    void sourceRefCitation() {
        assertThat(new SourceRef("callee_endcall.log", 142, "...").citation())
                .isEqualTo("callee_endcall.log:142");
        assertThat(new SourceRef(SourceRef.SIGNALING, 0, "...").citation())
                .isEqualTo("signaling");
    }

    @Test
    @DisplayName("signaling trích dẫn bằng THỨ TỰ sự kiện (signaling#87), không giả làm số dòng của file")
    void signalingCitationIsEventOrderNotFileLine() {
        assertThat(new SourceRef(SourceRef.SIGNALING, 87, "...").citation()).isEqualTo("signaling#87");
        // file log đính kèm vẫn giữ dạng tên file : số dòng
        assertThat(new SourceRef("caller_webrtc.log", 208, "...").citation()).isEqualTo("caller_webrtc.log:208");
    }
}
