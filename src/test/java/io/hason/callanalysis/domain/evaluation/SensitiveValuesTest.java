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
                "[001:002][10] connect deviceId=DEVICE0099 to 203.0.113.7:3478",
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
        assertThat(v.foundIn("203.0.113.7 và 10.1.2.3")).isEqualTo(Map.of("ip", 2));
        // 0.0.0.0, 127.0.0.1 không định danh ai: không gom
        assertThat(v.foundIn("0.0.0.0 127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("so theo ranh giới chữ / số: chuỗi con của chuỗi dài hơn không tính là lộ")
    void tokenBoundary() {
        SensitiveValues v = SensitiveValues.collect(List.of(AttachedFile.of("x.log", List.of(
                "peer 192.0.2.4 csid=AB12CD34"))), List.of());

        assertThat(v.foundIn("192.0.2.45 và XAB12CD34")).isEmpty();
        assertThat(v.foundIn("(192.0.2.4) [AB12CD34]")).isEqualTo(Map.of("ip", 1, "session", 1));
    }

    @Test
    @DisplayName("IPv6 unicast toàn cầu ở ba dạng có trong data: cột TSV, SDP, Cand[…[addr]:port…] — kể cả rút gọn ::")
    void collectsIpv6InRealShapes() {
        // 2001:db8::/32 là dải dành cho tài liệu (RFC 3849) — cùng hình dạng với địa chỉ thật trong data mẫu
        SensitiveValues v = SensitiveValues.collect(List.of(AttachedFile.of("callee_webrtc.log", List.of(
                "6\t1789978902987\tremote_candidate\t\thost\t1540719183\tIbw4MX2rd\t2001:db8:6f0:d3b8:5a20:6a57:d877:931b\tcellular",
                "candidate:1540719183 1 udp 2122262784 2001:db8:44a:e88b:1d59:cf68:6eee:a1b6 51625 typ host",
                "UseCandidate: Cand[:1430443554:1:udp:2122262784:[2001:db8:c31:ed45::aadc:a181]:43957:host::0:VPqG::6:900:0]"))),
                List.of());

        assertThat(v.foundIn("2001:db8:6f0:d3b8:5a20:6a57:d877:931b · 2001:db8:44a:e88b:1d59:cf68:6eee:a1b6 · "
                + "[2001:db8:c31:ed45::aadc:a181]")).isEqualTo(Map.of("ip", 3));
        assertThat(v.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("chuỗi chỉ TRÔNG như IPv6 trong log WebRTC không bị gom: Port[…:host, WRVMC::, fingerprint, giờ, địa chỉ đã che")
    void ignoresIpv6LookAlikes() {
        SensitiveValues v = SensitiveValues.collect(List.of(AttachedFile.of("caller_webrtc.log", List.of(
                "OnPortReady: UDPPort(Port[6449f700:0:1:0:host:Net[rmnet0:2001:db8:c31:x:x:x:x:x/64:Cellular:id=6]])",
                "WRVMC::SetOutputVolume({ssrc=553571615}, {volume=1.00})",
                "AudioOptions {aec: 1, agc: 1, ns: 1, hf: 1, swap: 0, }",
                "Port[0a00::0:0:relay] fingerprint:sha-256 AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89",
                "[10:00:06.320] link-local fe80::1c2b:3aff:fe4d:5e6f, loopback ::1"))), List.of());

        assertThat(v.foundIn("6449f700:0:1:0 0a00::0:0 fe80::1c2b:3aff:fe4d:5e6f ::1 10:00:06")).isEmpty();
        assertThat(v.count()).isEqualTo(1);                              // chỉ còn fingerprint
    }

    @Test
    @DisplayName("parser IPv6: đúng cú pháp RFC 4291, chỉ 2000::/3")
    void ipv6Parser() {
        assertThat(SensitiveValues.isGlobalIpv6("2001:db8:0:0:0:0:0:1")).isTrue();
        assertThat(SensitiveValues.isGlobalIpv6("2001:db8::1")).isTrue();
        assertThat(SensitiveValues.isGlobalIpv6("3fff::")).isTrue();
        assertThat(SensitiveValues.isGlobalIpv6("2001:db8:0:0:0:0:1")).isFalse();      // 7 nhóm, không "::"
        assertThat(SensitiveValues.isGlobalIpv6("2001::db8::1")).isFalse();            // hai "::"
        assertThat(SensitiveValues.isGlobalIpv6("2001:::1")).isFalse();
        assertThat(SensitiveValues.isGlobalIpv6("2001:db8:12345::1")).isFalse();       // nhóm 5 chữ số
        assertThat(SensitiveValues.isGlobalIpv6("2001:db8:0:0:0:0:0:1:")).isFalse();
        assertThat(SensitiveValues.isGlobalIpv6("fe80::1")).isFalse();                 // link-local
        assertThat(SensitiveValues.isGlobalIpv6("::ffff:1")).isFalse();
    }

    @Test
    @DisplayName("bí mật và liên hệ trong log: JWT, Bearer, API key, token=…, số điện thoại, email")
    void collectsSecretsAndContacts() {
        SensitiveValues v = SensitiveValues.collect(List.of(AttachedFile.of("caller_endcall.log", List.of(
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl",
                "provider rejected key sk-FAKEfake1234567890 for api_key=Zx81Qp0LmN3",
                "{\"accessToken\":\"tok_9f8e7d6c5b4a\",\"phone\":\"0912345678\",\"email\":\"test.user@example.com\"}",
                "callback +84987654321 · request 1789978896018 · id 0899238150123"))), List.of());

        assertThat(v.foundIn("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl sk-FAKEfake1234567890 Zx81Qp0LmN3"))
                .isEqualTo(Map.of("secret", 3));
        assertThat(v.foundIn("0912345678 test.user@example.com +84987654321")).isEqualTo(Map.of("contact", 3));
        assertThat(v.foundIn("1789978896018 0899238150123")).isEmpty();               // số dài hơn: không phải SĐT
    }

    @Test
    @DisplayName("giá trị nhạy cảm trong CÂU HỎI được tính, và không làm mất giá trị của log")
    void questionValues() {
        SensitiveValues fromLog = SensitiveValues.collect(List.of(AttachedFile.of("x.log", List.of(
                "peer 203.0.113.7"))), List.of());

        SensitiveValues v = fromLog.withQuestion("Máy 0912345678 gọi lỗi, token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl, IP 198.51.100.23");

        assertThat(v.foundIn("0912345678 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl 198.51.100.23 203.0.113.7"))
                .isEqualTo(Map.of("contact", 1, "secret", 1, "ip", 2));
        assertThat(fromLog.count()).isEqualTo(1);                                       // bản gốc không đổi
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
