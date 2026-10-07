package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.validation.AttachedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveValuesTest {

    private static final RawSignalingRecord SIGNALING = new RawSignalingRecord("CALL", 0, "2026-09-21T08:00:00Z",
            "media-svc-7f9c", "INFO", "INIT_CALL", "csid-4b1a9e", "req-1", "UAPPUSER123", "sess-77aa01",
            "MOBIFONE", "AS131429", "VN", null);

    @Test
    @DisplayName("lấy định danh từ trường signaling, cột end call log, tên=giá trị, ICE / fingerprint, IPv4")
    void collectsFromEverySource() {
        AttachedFile endcall = AttachedFile.of("caller_endcall.log", List.of(
                "#H1\t#ts\t#tag\tappUserId\tdeviceId",
                "1\t1789700842905\tinfo\tUENDCALLUSER9\tDEV-ABCDEF12"));
        AttachedFile webrtc = AttachedFile.of("caller_webrtc.log", List.of(
                "[001:002][10] connect deviceId=DEVICE0099 to 113.161.42.7:3478",
                "a=ice-pwd:Zx9Pq2Lm7Vb4Nc1Rt8Yw3Ke",
                "a=fingerprint:sha-256 AB:CD:EF:01:23:45:67:89",
                "bind 0.0.0.0 and 127.0.0.1 and 10.1.2.3"));

        SensitiveValues v = SensitiveValues.collect(List.of(endcall, webrtc), List.of(SIGNALING));

        assertThat(v.foundIn("UAPPUSER123 sess-77aa01 csid-4b1a9e media-svc-7f9c"))
                .isEqualTo(Map.of("userId", 1, "session", 2, "service", 1));
        assertThat(v.foundIn("UENDCALLUSER9 DEV-ABCDEF12 DEVICE0099"))
                .isEqualTo(Map.of("userId", 1, "deviceId", 2));
        assertThat(v.foundIn("Zx9Pq2Lm7Vb4Nc1Rt8Yw3Ke AB:CD:EF:01:23:45:67:89"))
                .isEqualTo(Map.of("ice-pwd", 1, "fingerprint", 1));
        assertThat(v.foundIn("113.161.42.7 và 10.1.2.3")).isEqualTo(Map.of("ip", 2));
        // 0.0.0.0, 127.0.0.1 không định danh ai: không gom
        assertThat(v.foundIn("0.0.0.0 127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("so theo ranh giới chữ / số: chuỗi con của chuỗi dài hơn không tính là lộ")
    void tokenBoundary() {
        SensitiveValues v = SensitiveValues.collect(List.of(AttachedFile.of("x.log", List.of(
                "peer 1.2.3.4 csid=AB12CD34"))), List.of());

        assertThat(v.foundIn("1.2.3.45 và XAB12CD34")).isEmpty();
        assertThat(v.foundIn("(1.2.3.4) [AB12CD34]")).isEqualTo(Map.of("ip", 1, "session", 1));
    }

    @Test
    @DisplayName("giá trị đã che / quá ngắn không gom; file không nạp nội dung bỏ qua")
    void skipsMaskedShortAndUnloaded() {
        SensitiveValues v = SensitiveValues.collect(List.of(
                AttachedFile.of("x.log", List.of("csid=[REDACTED] deviceId=AB1")),
                AttachedFile.notLoaded("big.log", 99_000_000)), List.of());

        assertThat(v.count()).isZero();
        assertThat(v.foundIn("bất kỳ")).isEmpty();
    }
}
