package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClockOffsetEstimatorTest {

    private final ClockOffsetEstimator estimator = new ClockOffsetEstimator();
    private static final Instant BASE = Instant.parse("2026-09-21T08:00:00Z");

    private static CanonicalEvent serverEvent(long millisFromBase, String cmd) {
        return new CanonicalEvent("signaling.json#" + millisFromBase, "CALL-1", Leg.SERVER,
                LogSource.SIGNALING,
                EventTime.absolute(BASE.plusMillis(millisFromBase), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, cmd, Map.of(), Severity.INFO,
                new SourceRef("signaling.json", 1, cmd));
    }

    private static CanonicalEvent clientSend(long millisFromBase, String cmd, Leg leg) {
        return new CanonicalEvent("endcall#" + millisFromBase, "CALL-1", leg, LogSource.ENDCALL,
                EventTime.absolute(BASE.plusMillis(millisFromBase), ClockDomain.CLIENT_CALLER),
                EventType.SIGNALING_COMMAND, cmd, Map.of("#tag", "send_cmd"), Severity.INFO,
                new SourceRef("caller_endcall.log", 1, cmd));
    }

    @Test
    @DisplayName("do lech bang trung vi hieu so giua gio server va gio client")
    void measuresMedianOffset() {
        List<CanonicalEvent> events = new ArrayList<>();
        // client gui luc 1000/2000/3000; server ghi nhan cham hon 100ms
        for (int i = 1; i <= 3; i++) {
            events.add(clientSend(i * 1000L, "PAIR_PING", Leg.CALLER));
            events.add(serverEvent(i * 1000L + 100, "PAIR_PING"));
        }

        List<ClockOffset> offsets = estimator.estimate(events);

        assertThat(offsets).singleElement().satisfies(o -> {
            assertThat(o.leg()).isEqualTo(Leg.CALLER);
            assertThat(o.medianMillis()).isEqualTo(100);
            assertThat(o.sampleCount()).isEqualTo(3);
            assertThat(o.isNegligible()).isTrue();
        });
    }

    @Test
    @DisplayName("dung TRUNG VI nen mot cap ghep nham khong keo lech ket qua")
    void medianResistsMismatchedRetransmission() {
        // Lenh co gui lai (TRYING, INVITE) de bi ghep nham giua cac lan gui.
        // Tren data mau viec ghep nham day gia tri len hon 4 giay.
        List<CanonicalEvent> events = new ArrayList<>(List.of(
                clientSend(1000, "PAIR_PING", Leg.CALLER), serverEvent(1080, "PAIR_PING"),
                clientSend(2000, "PAIR_PING", Leg.CALLER), serverEvent(2070, "PAIR_PING"),
                clientSend(3000, "PAIR_PING", Leg.CALLER), serverEvent(3075, "PAIR_PING"),
                clientSend(4000, "PAIR_PING", Leg.CALLER), serverEvent(8000, "PAIR_PING")));

        assertThat(estimator.estimate(events).getFirst().medianMillis())
                .isBetween(70L, 200L);
    }

    @Test
    @DisplayName("do rieng cho tung leg")
    void measuresEachLegSeparately() {
        List<CanonicalEvent> events = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            events.add(clientSend(i * 1000L, "PAIR_PING", Leg.CALLER));
            events.add(serverEvent(i * 1000L + 50, "PAIR_PING"));
        }
        for (int i = 1; i <= 3; i++) {
            events.add(clientSend(i * 1000L + 10_000, "RINGING", Leg.CALLEE));
            events.add(serverEvent(i * 1000L + 10_200, "RINGING"));
        }

        assertThat(estimator.estimate(events))
                .extracting(ClockOffset::leg)
                .containsExactly(Leg.CALLER, Leg.CALLEE);
    }

    @Test
    @DisplayName("duoi 3 cap khop thi khong ket luan — mau qua nho")
    void tooFewSamplesProducesNoEstimate() {
        assertThat(estimator.estimate(List.of(
                clientSend(1000, "PAIR_PING", Leg.CALLER),
                serverEvent(1100, "PAIR_PING")))).isEmpty();
    }

    @Test
    @DisplayName("lech lon hon 1 giay bi danh dau la dang ke")
    void largeOffsetIsFlagged() {
        List<CanonicalEvent> events = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            events.add(clientSend(i * 1000L, "PAIR_PING", Leg.CALLER));
            events.add(serverEvent(i * 1000L + 5_000, "PAIR_PING"));
        }

        assertThat(estimator.estimate(events).getFirst().isNegligible()).isFalse();
    }

    @Test
    @DisplayName("chi co signaling, khong co end call log -> khong do duoc")
    void noClientLogMeansNoEstimate() {
        assertThat(estimator.estimate(List.of(
                serverEvent(1000, "INIT_CALL"), serverEvent(2000, "INVITE")))).isEmpty();
        assertThat(estimator.estimate(List.of())).isEmpty();
    }
}
