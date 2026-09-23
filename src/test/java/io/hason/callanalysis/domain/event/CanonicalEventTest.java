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
                new SourceRef("signaling.json", 12, "{}"));
    }

    @Test
    @DisplayName("attributes duoc sao chep phong thu, sua map goc khong anh huong event")
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
    @DisplayName("attributes giu nguyen thu tu chen — thu tu doi la mat tinh tat dinh")
    void attributesPreserveInsertionOrder() {
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put("z", "1");
        ordered.put("a", "2");
        ordered.put("m", "3");

        assertThat(event(ordered).attributes().keySet()).containsExactly("z", "a", "m");
    }

    @Test
    @DisplayName("truong bat buoc thieu thi bao loi ngay, khong am tham bo qua")
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
    @DisplayName("gia tri tuy chon vang mat thi dung mac dinh an toan, attributes khong bao gio null")
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
    @DisplayName("event tuyet doi luon sap truoc event tuong doi")
    void absoluteTimeSortsBeforeRelativeTime() {
        EventTime absolute = EventTime.absolute(Instant.parse("1970-01-01T00:00:00Z"), ClockDomain.SERVER);
        EventTime relative = EventTime.relative(Duration.ofSeconds(6652));

        assertThat(List.of(absolute, relative).stream()
                .sorted(java.util.Comparator.comparingLong(EventTime::sortKeyNanos))
                .toList())
                .containsExactly(relative, absolute);
    }

    @Test
    @DisplayName("SourceRef dung dinh dang trich dan cua mau report muc 4.5")
    void sourceRefCitation() {
        assertThat(new SourceRef("callee_endcall.log", 142, "...").citation())
                .isEqualTo("callee_endcall.log:142");
        assertThat(new SourceRef("signaling.json", 0, "...").citation())
                .isEqualTo("signaling.json");
    }
}
