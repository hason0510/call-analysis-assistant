package io.hason.callanalysis.domain.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Context semi-structured (T11 Sprint 2): cùng dữ kiện với bản JSON, dạng văn bản theo ví dụ mentor gửi. */
class AiContextTextTest {

    /** Đúng tình huống trong ví dụ của mentor: signaling hoàn hảo, ICE phía callee failed (như 2D9057AA). */
    private static final AiContext.Payload ICE_FAIL = new AiContext.Payload(
            new AiContext.RuleSummary("FAIL", false, "ICE_FAILURE", "MEDIUM",
                    "Signaling hoàn tất OK_ACK_OK nhưng ICE phía callee chuyển sang failed, không đạt connected.", null,
                    List.of("Kiểm tra mạng phía callee (NAT, firewall chặn UDP).")),
            List.of(new AiContext.EvidenceItem("EV01", "SIGNALING", "SERVER", "10:00:06.320",
                            "OK_ACK: LegA CONFIRMED, LegB CONFIRMED", "signaling#87", false),
                    new AiContext.EvidenceItem("EV02", "WEBRTC", "CALLEE", "+00:07.100",
                            "ICE connection state: checking -> failed", "callee_webrtc.log:812", true),
                    new AiContext.EvidenceItem("EV03", "ENDCALL", "CALLEE", "10:00:41.000",
                            "MOS = 0, audio bytes = 0", "callee_endcall.log:40", true)),
            List.of(new AiContext.MetricItem("SETUP_TIME", "Thời gian thiết lập", "6.2 s", "SIGNALING"),
                    new AiContext.MetricItem("CONNECTED_DURATION", "Thời lượng kết nối", "35.1 s", "SIGNALING"),
                    new AiContext.MetricItem("PACKET_LOSS_CALLEE", "Packet loss (callee nhận)",
                            "N/A (không có trong file log)", "ENDCALL")),
            List.of(new AiContext.LogFile("signaling", "SIGNALING", null, "ABSOLUTE"),
                    new AiContext.LogFile("callee_endcall.log", "ENDCALL", "CALLEE", "ABSOLUTE"),
                    new AiContext.LogFile("callee_webrtc.log", "WEBRTC", "CALLEE", "RELATIVE")),
            List.of("Thiếu caller_endcall.log", "Thiếu caller_webrtc.log"),
            List.of(new AiContext.CategoryItem("ICE_FAILURE", "ICE không đạt connected.",
                    List.of("ICE checking -> failed", "0 byte âm thanh"), true)));

    @Test
    @DisplayName("bản văn bản có đúng hình dạng mentor gửi: kết luận rule, evidence có nguồn + leg + giờ, chỉ số kèm mã")
    void rendersMentorShape() {
        String text = AiContextText.render(ICE_FAIL);

        assertThat(text).startsWith(
                "Kết luận của rule (ruleVerdict): FAIL, nhóm vấn đề ICE_FAILURE, cờ chất lượng không, độ tin cậy MEDIUM.");
        assertThat(text).contains(
                "[EV01][SIGNALING, SERVER, 10:00:06.320] OK_ACK: LegA CONFIRMED, LegB CONFIRMED — dòng signaling#87\n",
                "[EV02][WEBRTC, CALLEE, +00:07.100] ICE connection state: checking -> failed — dòng callee_webrtc.log:812"
                        + " — căn cứ của rule (ruleBasis)",
                "- SETUP_TIME — Thời gian thiết lập: 6.2 s (SIGNALING)",
                "- PACKET_LOSS_CALLEE — Packet loss (callee nhận): N/A (không có trong file log) (ENDCALL)",
                "File log có mặt (logs): signaling (SIGNALING, giờ tuyệt đối); callee_endcall.log (ENDCALL, CALLEE, giờ tuyệt đối); "
                        + "callee_webrtc.log (WEBRTC, CALLEE, giờ tương đối).",
                "Giới hạn dữ liệu (dataLimitations): Thiếu caller_endcall.log; Thiếu caller_webrtc.log.",
                "- ICE_FAILURE (đã kiểm chứng): ICE không đạt connected. Triệu chứng: ICE checking -> failed; 0 byte âm thanh.");
        assertThat(text).doesNotContain("{\"", "\":");
    }

    @Test
    @DisplayName("cùng dữ kiện với bản JSON: mọi văn bản G05 được phép nhắc lại, mọi ID evidence và mã chỉ số đều có mặt")
    void keepsEveryFactOfThePayload() {
        AiContext context = new AiContext("req-1", "Vì sao bên nhận không nghe được?", null, ICE_FAIL, Map.of());
        String text = AiContextText.render(ICE_FAIL);

        assertThat(context.groundingTexts()).allSatisfy(t -> assertThat(text).contains(t));
        assertThat(context.evidenceIds()).allSatisfy(id -> assertThat(text).contains("[" + id + "]"));
        assertThat(context.metricIds()).allSatisfy(id -> assertThat(text).contains("- " + id + " — "));
        assertThat(AiContextText.render(ICE_FAIL)).isEqualTo(text);
    }

    @Test
    @DisplayName("không có evidence / chỉ số / log -> ghi rõ 'không có', không bỏ trống phần đó")
    void emptySectionsAreExplicit() {
        String text = AiContextText.render(new AiContext.Payload(
                new AiContext.RuleSummary("UNKNOWN", false, "UNKNOWN", "LOW", "Không có log client.", null, List.of()),
                List.of(), List.of(), List.of(), List.of(), List.of()));

        assertThat(text).contains("Evidence (evidence):\n- (không có)", "Chỉ số (metrics):\n- (không có)",
                "File log có mặt (logs): không có.", "Giới hạn dữ liệu (dataLimitations): không có.");
    }
}
