package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricKey;
import io.hason.callanalysis.domain.metrics.MetricValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Sprint 3 T1a: AI nhắc chỉ số bằng mã, code điền tên và giá trị. */
class MetricCodesTest {

    private static final CallMetrics METRICS = new CallMetrics("CALL-1", List.of(
            CallMetric.of(MetricKey.RINGING_TIME, MetricValue.millis(10942)),
            CallMetric.of(MetricKey.PACKET_LOSS, Leg.CALLER, MetricValue.of(new BigDecimal("1.83"), "%")),
            CallMetric.of(MetricKey.MOS, Leg.CALLEE, MetricValue.unavailable("leg chưa có media"))));

    private final MetricCodes codes = MetricCodes.of(METRICS);

    @Test
    @DisplayName("mã là tên chỉ số kèm leg (không đánh số — tránh AI lẫn với evidence ID EVxx)")
    void codesAreMetricNames() {
        assertThat(codes.ids()).containsExactly("RINGING_TIME", "PACKET_LOSS_CALLER", "MOS_CALLEE");
    }

    @Test
    @DisplayName("mã thay bằng giá trị kèm tên chỉ số, kể cả N/A kèm lý do; chữ quanh mã giữ nguyên")
    void codesExpandToValueAndName() {
        assertThat(codes.expand("Đổ chuông là {RINGING_TIME}; mất gói {PACKET_LOSS_CALLER}, MOS bên nhận {MOS_CALLEE}."))
                .isEqualTo("Đổ chuông là 10942 ms [Thời gian đổ chuông]; mất gói 1.83 % [Packet loss (caller)],"
                        + " MOS bên nhận N/A (leg chưa có media) [MOS (callee)].");
    }

    @Test
    @DisplayName("chỉ dạng {MÃ} mới thay — mã trần, EV02 và mã lạ để nguyên")
    void onlyBracedKnownCodesExpand() {
        assertThat(codes.expand("MOS_CALLEE và EV02, {MOS_CALLER}")).isEqualTo("MOS_CALLEE và EV02, {MOS_CALLER}");
        assertThat(MetricCodes.codesIn("{MOS_CALLEE} rồi {X1}, lại {MOS_CALLEE}"))
                .containsExactly("MOS_CALLEE", "X1", "MOS_CALLEE");
    }

    @Test
    @DisplayName("thay ở cả tóm tắt, phân tích và đề xuất; verdict, evidence, token giữ nguyên")
    void analysisIsExpandedEverywhere() {
        AiAnalysis raw = new AiAnalysis("SUCCESS", true, "NETWORK_PACKET_LOSS", "LOW", "Mất gói {PACKET_LOSS_CALLER}.",
                List.of("EV03"), "Bên gọi: {PACKET_LOSS_CALLER}.", List.of("Theo dõi {PACKET_LOSS_CALLER}"),
                new TokenUsage(3000, 150));

        AiAnalysis shown = codes.expand(raw);

        assertThat(shown.freeText()).allSatisfy(t -> assertThat(t).contains("1.83 % [Packet loss (caller)]")
                .doesNotContain("{PACKET_LOSS_CALLER}"));
        assertThat(shown.evidenceIds()).isEqualTo(raw.evidenceIds());
        assertThat(shown.usage()).isEqualTo(raw.usage());
    }
}
