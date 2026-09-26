package io.hason.callanalysis.infrastructure.security;

import io.hason.callanalysis.domain.security.DataClassification;
import io.hason.callanalysis.domain.security.HandlingPolicy;
import io.hason.callanalysis.domain.security.SensitiveField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataInventoryTest {

    private final List<SensitiveField> inventory = new SensitiveDataInventoryLoader().load();

    private SensitiveField byId(String id) {
        return inventory.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("giữ đủ danh sách gốc của MVP mục 5.2 và có phần bổ sung từ data mẫu")
    void inventoryCoversBothOrigins() {
        assertThat(inventory).isNotEmpty();
        assertThat(inventory).anyMatch(f -> f.origin() == SensitiveField.Origin.MVP_5_2);
        assertThat(inventory).anyMatch(f -> f.origin() == SensitiveField.Origin.SAMPLE_REVIEW);
        assertThat(inventory).allSatisfy(f -> assertThat(f.rationale()).isNotBlank());
    }

    @Test
    @DisplayName("bí mật xác thực bị DROP và không bao giờ được gửi sang AI")
    void secretsAreDropped() {
        assertThat(inventory)
                .filteredOn(f -> f.classification() == DataClassification.SECRET)
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.policy()).isEqualTo(HandlingPolicy.DROP);
                    assertThat(f.mustNotReachAi()).isTrue();
                });
    }

    @Test
    @DisplayName("JWT trong chuỗi Authorization bị nhận diện (ca kiểm thử S01/S02)")
    void jwtIsDetected() {
        SensitiveField credentials = byId("credentials");
        assertThat(credentials.matchesValue(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc-def")).isTrue();
        assertThat(credentials.matchesFieldName("apiKey")).isTrue();
    }

    @Test
    @DisplayName("IP công cộng trong chuỗi ICE candidate bị nhận diện")
    void publicIpInIceCandidateIsDetected() {
        // Chuỗi thật lấy từ ai20k_sample.
        String candidate = "candidate:2870078463 1 udp 58674431 14.238.62.98 21355 typ relay "
                + "raddr 42.115.218.73 rport 20425 generation 0 ufrag CS/8";

        assertThat(byId("ice-candidate-string").matchesValue(candidate)).isTrue();
        assertThat(byId("ice-candidate-string").policy()).isEqualTo(HandlingPolicy.MASK);
    }

    @Test
    @DisplayName("raddr — địa chỉ THẬT sau relay — được liệt kê riêng")
    void relayRealAddressIsCoveredSeparately() {
        // Che địa chỉ relay mà không che raddr thì coi như không che gì.
        SensitiveField raddr = byId("relay-real-address");
        assertThat(raddr.matchesValue("typ relay raddr 42.115.218.73 rport 20425")).isTrue();
        assertThat(raddr.classification()).isEqualTo(DataClassification.SENSITIVE);
    }

    @Test
    @DisplayName("credential của ICE bị coi là SECRET và DROP")
    void iceCredentialsAreSecret() {
        SensitiveField ice = byId("ice-credentials");
        assertThat(ice.classification()).isEqualTo(DataClassification.SECRET);
        assertThat(ice.matchesValue("u/p=ajsF/0Hgrh26kJWmn0iKH6Fwbktin")).isTrue();
    }

    @Test
    @DisplayName("định danh người dùng được PSEUDONYMIZE chứ không MASK — còn phải correlate")
    void userIdsArePseudonymisedNotMasked() {
        assertThat(byId("user-and-device-id").policy()).isEqualTo(HandlingPolicy.PSEUDONYMIZE);
        assertThat(byId("session-id").policy()).isEqualTo(HandlingPolicy.PSEUDONYMIZE);
        assertThat(byId("user-and-device-id").matchesFieldName("appUserId")).isTrue();
    }

    @Test
    @DisplayName("chỉ số chất lượng được ALLOW — đây là dữ liệu chính để phân tích")
    void qualityMetricsAreAllowed() {
        SensitiveField metrics = byId("call-quality-metrics");
        assertThat(metrics.policy()).isEqualTo(HandlingPolicy.ALLOW);
        assertThat(metrics.mustNotReachAi()).isFalse();
        assertThat(metrics.matchesFieldName("audio.audioMos")).isTrue();
    }

    @Test
    @DisplayName("số điện thoại Việt Nam bị nhận diện (ca kiểm thử S04)")
    void vietnamesePhoneNumberIsDetected() {
        assertThat(byId("phone-and-email").matchesValue("phone=0987654321")).isTrue();
        assertThat(byId("phone-and-email").matchesValue("lien he: user@example.com")).isTrue();
    }

    @Test
    @DisplayName("IPv6 trong dòng Cand[...] của libwebrtc bị nhận diện — văn bản tự do, không có tên trường")
    void ipv6InLibwebrtcCandidateIsDetected() {
        // Đúng định dạng dòng thật trong data mẫu; địa chỉ thay bằng dải tài liệu 2001:db8::/32
        SensitiveField ip = byId("client-ip");
        assertThat(ip.matchesValue("Cand[:9268178:1:udp:2122262784:[2001:db8:456:a046:1:2:3:4]:61421:host::0:EF1K::2:10:0]"))
                .isTrue();
        assertThat(ip.matchesValue("addr 2001:db8::1 port 3478")).isTrue();
        // giờ dạng hh:mm:ss không bị nhầm là IPv6
        assertThat(ip.matchesValue("[signaling 10:00:06.320] OK_ACK")).isFalse();
    }

    @Test
    @DisplayName("TURN credential (MVP mục 5.2) là SECRET và bị DROP")
    void turnCredentialIsDropped() {
        SensitiveField turn = byId("turn-credential");
        assertThat(turn.classification()).isEqualTo(DataClassification.SECRET);
        assertThat(turn.policy()).isEqualTo(HandlingPolicy.DROP);
        assertThat(turn.matchesFieldName("credential")).isTrue();
    }
}
