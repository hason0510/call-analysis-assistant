package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext.MetricItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Sprint 3 T1a: đếm AI nhắc chỉ số qua mã hay tự chép số, trên đầu ra thô. */
class MetricMentionsTest {

    private static final List<MetricItem> METRICS = List.of(
            new MetricItem("RINGING_TIME", "Thời gian đổ chuông", "10942 ms", "SIGNALING"),
            new MetricItem("PACKET_LOSS_CALLER", "Packet loss (caller)", "1.83 %", "ENDCALL"),
            new MetricItem("MOS_CALLEE", "MOS (callee)", "N/A (thiếu 2 file log)", "ENDCALL"));

    private static AiAnalysis ai(String summary, String analysis, String... suggestions) {
        return new AiAnalysis("SUCCESS", true, "NETWORK_PACKET_LOSS", "LOW", summary, List.of("EV01"), analysis,
                List.of(suggestions));
    }

    @Test
    @DisplayName("mã đếm ở mọi đoạn chữ, kể cả lặp; số tự chép chỉ tính khi trùng giá trị một chỉ số")
    void countsCodesAndHandWrittenMetricValues() {
        MetricMentions m = MetricMentions.of(ai("Mất gói {PACKET_LOSS_CALLER}.", "Đổ chuông {RINGING_TIME}, mất gói 1,83 %, mẫu stats"
                + " 11.3208 %, mã 428.", "Theo dõi {PACKET_LOSS_CALLER}"), METRICS);

        assertThat(m).isEqualTo(new MetricMentions(3, 1));       // 11.3208 và 428 không phải giá trị chỉ số
    }

    @Test
    @DisplayName("số nằm trong lý do N/A không phải giá trị đo — AI chép lại không tính là tự chép chỉ số")
    void numbersInNotAvailableReasonAreIgnored() {
        assertThat(MetricMentions.of(ai("Thiếu 2 file log.", "MOS {MOS_CALLEE}."), METRICS))
                .isEqualTo(new MetricMentions(1, 0));
    }
}
