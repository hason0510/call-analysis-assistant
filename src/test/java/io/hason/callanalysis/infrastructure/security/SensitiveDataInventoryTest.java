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
    @DisplayName("giu du danh sach goc cua MVP muc 5.2 va co phan bo sung tu data mau")
    void inventoryCoversBothOrigins() {
        assertThat(inventory).isNotEmpty();
        assertThat(inventory).anyMatch(f -> f.origin() == SensitiveField.Origin.MVP_5_2);
        assertThat(inventory).anyMatch(f -> f.origin() == SensitiveField.Origin.SAMPLE_REVIEW);
        assertThat(inventory).allSatisfy(f -> assertThat(f.rationale()).isNotBlank());
    }

    @Test
    @DisplayName("bi mat xac thuc bi DROP va khong bao gio duoc gui sang AI")
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
    @DisplayName("JWT trong chuoi Authorization bi nhan dien (ca kiem thu S01/S02)")
    void jwtIsDetected() {
        SensitiveField credentials = byId("credentials");
        assertThat(credentials.matchesValue(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc-def")).isTrue();
        assertThat(credentials.matchesFieldName("apiKey")).isTrue();
    }

    @Test
    @DisplayName("IP cong cong trong chuoi ICE candidate bi nhan dien")
    void publicIpInIceCandidateIsDetected() {
        // Chuoi that lay tu ai20k_sample.
        String candidate = "candidate:2870078463 1 udp 58674431 14.238.62.98 21355 typ relay "
                + "raddr 42.115.218.73 rport 20425 generation 0 ufrag CS/8";

        assertThat(byId("ice-candidate-string").matchesValue(candidate)).isTrue();
        assertThat(byId("ice-candidate-string").policy()).isEqualTo(HandlingPolicy.MASK);
    }

    @Test
    @DisplayName("raddr — dia chi THAT sau relay — duoc liet ke rieng")
    void relayRealAddressIsCoveredSeparately() {
        // Che dia chi relay ma khong che raddr thi coi nhu khong che gi.
        SensitiveField raddr = byId("relay-real-address");
        assertThat(raddr.matchesValue("typ relay raddr 42.115.218.73 rport 20425")).isTrue();
        assertThat(raddr.classification()).isEqualTo(DataClassification.SENSITIVE);
    }

    @Test
    @DisplayName("credential cua ICE bi coi la SECRET va DROP")
    void iceCredentialsAreSecret() {
        SensitiveField ice = byId("ice-credentials");
        assertThat(ice.classification()).isEqualTo(DataClassification.SECRET);
        assertThat(ice.matchesValue("u/p=ajsF/0Hgrh26kJWmn0iKH6Fwbktin")).isTrue();
    }

    @Test
    @DisplayName("dinh danh nguoi dung duoc PSEUDONYMIZE chu khong MASK — con phai correlate")
    void userIdsArePseudonymisedNotMasked() {
        assertThat(byId("user-and-device-id").policy()).isEqualTo(HandlingPolicy.PSEUDONYMIZE);
        assertThat(byId("session-id").policy()).isEqualTo(HandlingPolicy.PSEUDONYMIZE);
        assertThat(byId("user-and-device-id").matchesFieldName("appUserId")).isTrue();
    }

    @Test
    @DisplayName("chi so chat luong duoc ALLOW — day la du lieu chinh de phan tich")
    void qualityMetricsAreAllowed() {
        SensitiveField metrics = byId("call-quality-metrics");
        assertThat(metrics.policy()).isEqualTo(HandlingPolicy.ALLOW);
        assertThat(metrics.mustNotReachAi()).isFalse();
        assertThat(metrics.matchesFieldName("audio.audioMos")).isTrue();
    }

    @Test
    @DisplayName("so dien thoai Viet Nam bi nhan dien (ca kiem thu S04)")
    void vietnamesePhoneNumberIsDetected() {
        assertThat(byId("phone-and-email").matchesValue("phone=0987654321")).isTrue();
        assertThat(byId("phone-and-email").matchesValue("lien he: user@example.com")).isTrue();
    }
}
