package io.hason.callanalysis.domain.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cổng fail-closed trước khi gửi AI (góp ý mentor Sprint 2): chặn dữ liệu nhạy cảm còn sót, không chặn nhầm bản đã làm sạch. */
class OutboundGateTest {

    @Test
    @DisplayName("IP, bí mật, liên hệ còn nguyên trong chuỗi gửi đi -> chặn, chỉ trả tên loại")
    void rawSensitiveValuesAreBlocked() {
        assertThat(OutboundGate.kindsFound("candidate relay 203.0.113.98 port 3478")).containsExactly("ip");
        assertThat(OutboundGate.kindsFound("addr 2402:9d80:456:a046:1c2b:3d4e:5f60:7a8b")).containsExactly("ip");
        assertThat(OutboundGate.kindsFound("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"))
                .containsExactly("secret");
        assertThat(OutboundGate.kindsFound("key sk-proj1234567890abcdef bị lộ")).containsExactly("secret");
        assertThat(OutboundGate.kindsFound("Máy 0912345678, email an.nguyen@example.com")).containsExactly("contact");
    }

    @Test
    @DisplayName("giá trị đã được Sanitizer thay, mã giả, mốc giờ, phiên bản, Call-ID, mã chỉ số -> không chặn")
    void sanitizedContextPasses() {
        String context = """
                {"question":"Máy [PHONE_REDACTED] gọi lỗi, token=[REDACTED], IP [IP]",
                 "evidence":"[EV02][caller_endcall.log:11] ACK mã 428; deviceId=DEVICE_a81f2c; TURN TURN_9b1e:3478",
                 "time":"10:00:06.320","version":"0.3.24","callId":"2D9057AA-C496-48B2-946A-98FA2896D086",
                 "metric":"{PACKET_LOSS_CALLER} 1.83 %, bytes 1789700842905, local 192.168.26.x"}""";

        assertThat(OutboundGate.kindsFound(context)).isEmpty();
        assertThat(OutboundGate.kindsFound(null)).isEmpty();
    }
}
