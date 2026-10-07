package io.hason.callanalysis.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ca kiểm thử S01-S06 của MVP mục 6.4 (S07 ở AiVerdictServiceTest, S08 ở SanitizingConvertersTest).
 *
 * Chạy trên inventory THẬT (sensitive-data-inventory.yaml), không trên bản giả: kiểm cả quy tắc
 * lẫn dữ liệu cấu hình. Data mẫu không có JWT / số điện thoại / email / API key nào nên các giá
 * trị dưới đây là tự tạo; IP dùng dải tài liệu (RFC 5737 / 3849) trừ chỗ cần IP công cộng thật.
 */
class SensitiveDataSanitizerTest {

    private static final byte[] KEY = "khoa-test-co-dinh-de-ket-qua-on-dinh".getBytes(StandardCharsets.UTF_8);

    private final SensitiveDataSanitizer sanitizer =
            new SensitiveDataSanitizer(new SensitiveDataInventoryLoader().load(), new Pseudonymizer(KEY));

    private String clean(String text) {
        return sanitizer.sanitize(text).text();
    }

    // ---------------- S01-S03: bí mật bị DROP ----------------

    @Test
    @DisplayName("S01 — JWT trong log bị xoá, phần chữ xung quanh giữ nguyên")
    void s01JwtIsDropped() {
        String out = clean("refresh ok, token moi eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnLWFiYw het han 3600s");

        assertThat(out).doesNotContain("eyJ").contains("[REDACTED]").startsWith("refresh ok").endsWith("3600s");
    }

    @Test
    @DisplayName("S02 — Authorization header: Bearer và Basic đều bị xoá trọn giá trị (mẫu MVP mục 6.2)")
    void s02AuthorizationHeaderIsDropped() {
        assertThat(clean("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc-def"))
                .isEqualTo("Authorization: [REDACTED]");
        // Dừng ở khoảng trắng thì chỉ che chữ "Basic" và để lộ phần base64.
        assertThat(clean("Authorization: Basic dXNlcjpwYXNzd29yZA=="))
                .isEqualTo("Authorization: [REDACTED]");
    }

    @Test
    @DisplayName("S03 — API key trong exception: dạng tên=giá trị và key đứng một mình đều bị xoá")
    void s03ApiKeyInExceptionIsDropped() {
        String out = clean("java.lang.IllegalStateException: HTTP 401 calling https-proxy with api_key=Zx81Qp0LmN3 "
                + "(fallback key sk-proj-AbCdEf0123456789XyZ0123456789, aws AKIAIOSFODNN7EXAMPLE)");

        assertThat(out).doesNotContain("Zx81Qp0LmN3").doesNotContain("sk-proj").doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .startsWith("java.lang.IllegalStateException: HTTP 401");
    }

    @Test
    @DisplayName("S03 — password trong JSON escape của exception bị xoá, JSON không bị cắt cụt")
    void s03PasswordInEscapedJsonIsDropped() {
        assertThat(clean("body={\\\"user\\\":\\\"a\\\",\\\"password\\\":\\\"hunter2 extra\\\"}"))
                .doesNotContain("hunter2").contains("\\\"password\\\":\\\"[REDACTED]\\\"}");
    }

    // ---------------- S04: liên lạc bị MASK ----------------

    @Test
    @DisplayName("S04 — số điện thoại và email bị che (mẫu MVP: phone=[..._REDACTED])")
    void s04PhoneAndEmailAreMasked() {
        String out = clean("phone=0987654321, +84912345678, lien he: nguyen.a+call@example.com");

        assertThat(out).isEqualTo("phone=[CONTACT_REDACTED], [CONTACT_REDACTED], lien he: [CONTACT_REDACTED]");
    }

    @Test
    @DisplayName("S04 — KHÔNG che nhầm đuôi UUID hay timestamp mili giây (lỗi của pattern Sprint 1)")
    void s04NoFalsePositiveOnUuidOrTimestamp() {
        // Đây đúng là "số điện thoại" duy nhất pattern cũ tìm được trong success/ + fail/.
        String line = "\"requestId\": \"874984cb-ace2-4685-bca3-845071325831\", ts 1789700842905";

        assertThat(clean(line)).isEqualTo(line);
    }

    // ---------------- S05: IP / device ID ----------------

    @Test
    @DisplayName("S05 — IP client (v4, v6, đã che dở) bị che; TURN server chỉ đổi địa chỉ, giữ cổng 3478")
    void s05IpAddressesAreMasked() {
        String out = clean("Net[wlan0:192.168.26.x/24] 203.0.113.7 2001:db8:456:a046:1:2:3:4 2402:800:75d9:x:x:x:x:x "
                + "22.202.73.x -Remote[198.51.100.10:3478/udp]");

        assertThat(out).doesNotContain("203.0.113.7").doesNotContain("2001:db8").doesNotContain("2402:800")
                .doesNotContain("192.168").doesNotContain("22.202.73").doesNotContain("198.51.100.10")
                .containsPattern("-Remote\\[TURN_[0-9a-f]{4}:3478/udp]");
    }

    @Test
    @DisplayName("S05 — chuỗi candidate: che địa chỉ và ufrag, GIỮ loại candidate và cổng để phân tích")
    void s05CandidateKeepsAnalyticalFields() {
        String out = clean("candidate:2870078463 1 udp 58674431 203.0.113.98 21355 typ relay "
                + "raddr 198.51.100.73 rport 20425 generation 0 ufrag CS/8");

        assertThat(out).isEqualTo("candidate:2870078463 1 udp 58674431 [IP_REDACTED] 21355 typ relay "
                + "raddr [IP_REDACTED] rport 20425 generation 0 ufrag [REDACTED]");
    }

    @Test
    @DisplayName("S05 — Cand[] của libwebrtc mang cả ufrag lẫn mật khẩu ICE: che cả hai")
    void s05LibwebrtcCandidateCredentialsAreDropped() {
        // Dạng thật của WebRTC log (690 dòng ở success/ + fail/); mật khẩu ở đây là tự tạo.
        String out = clean("Cand[:3246943986:0:udp:2122260224:203.0.113.5:53693:host::0:ZkP7:umZcOP7T9KMfso4umKMgJ6zA:3:900:0]");

        assertThat(out).doesNotContain("ZkP7").doesNotContain("umZcOP7T9KMfso4umKMgJ6zA")
                .endsWith(":host::0:[REDACTED]:[REDACTED]:3:900:0]");
    }

    @Test
    @DisplayName("S05 — mật khẩu ICE trong SDP và DTLS fingerprint bị che; fingerprint không bị băm thành 'IPv6'")
    void s05SdpSecretsAreRemoved() {
        String sdp = "a=ice-ufrag:bVsH\\r\\na=ice-pwd:v9Dio7i1qBfuKPSV18GQzCr8\\r\\n"
                + "a=fingerprint:sha-256 8C:43:62:CE:2C:B2:5C:17:BC:E4:35:51:10:47:62:B4:B1:7D:2B:68:D0:7C:AE:F5:01:02:03:04:05:06:07:08";

        assertThat(clean(sdp)).isEqualTo("a=ice-ufrag:[REDACTED]\\r\\na=ice-pwd:[REDACTED]\\r\\n"
                + "a=fingerprint:sha-256 [FINGERPRINT_REDACTED]");
    }

    @Test
    @DisplayName("S05 — device ID trong văn bản tự do bị pseudonymize, không mask — vẫn phân biệt được thiết bị")
    void s05DeviceIdIsPseudonymised() {
        String out = clean("platform=ios, csid=OqvPStbZ, deviceId=DHV3MGQG53I, logConfig=…");

        assertThat(out).doesNotContain("DHV3MGQG53I").doesNotContain("OqvPStbZ")
                .containsPattern("deviceId=DEVICE_[0-9a-f]{6}").containsPattern("csid=SESSION_[0-9a-f]{6}");
    }

    // ---------------- Định danh: nhất quán và lan sang văn bản tự do ----------------

    @Test
    @DisplayName("cùng giá trị + cùng khoá -> cùng mã (correlate được); khác nhãn -> khác mã")
    void pseudonymsAreStable() {
        String a = clean("appUserId=UKVIYF7A4DW");
        String b = clean("callUserId: UKVIYF7A4DW");

        assertThat(a.substring(a.indexOf("USER_"))).isEqualTo(b.substring(b.indexOf("USER_")));
        assertThat(clean("csid=UKVIYF7A4DW")).doesNotContain(a.substring(a.indexOf("USER_")));
    }

    @Test
    @DisplayName("appUserId biết từ signaling được thay cả trong stream_ids của WebRTC log (196 lần trong data mẫu)")
    void knownIdentifierIsReplacedInFreeText() {
        SensitiveDataSanitizer.Session session = sanitizer.newSession();
        session.remember("appUserId", "UZFSCHDZTWR");          // thuộc tính có cấu trúc của event signaling

        String out = session.sanitize("ssrcs:[3239868285];cname:jQUj;stream_ids:stream_UZFSCHDZTWR;}").text();

        assertThat(out).doesNotContain("UZFSCHDZTWR").containsPattern("stream_USER_[0-9a-f]{6};");
    }

    @Test
    @DisplayName("chạy sanitizer hai lần cho cùng kết quả — không băm lại mã đã băm")
    void sanitizingIsIdempotent() {
        String once = clean("deviceId=DHV3MGQG53I ip 203.0.113.7 Authorization: Bearer abc.def");

        assertThat(clean(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("chỉ số chất lượng, ISP, giờ dạng hh:mm:ss và URL tài liệu webrtc.org được giữ nguyên")
    void analyticalDataIsKept() {
        String line = "10:00:06.320 isp=VNPT asn=AS45899 audio.audioMos=4.335 rtt 63 ms https://www.webrtc.org/x";

        assertThat(clean(line)).isEqualTo(line);
    }

    // ---------------- S06: JSON lồng nhau ----------------

    @Test
    @DisplayName("S06 — JSON lồng nhau: bí mật bị BỎ cả trường, định danh pseudonymize, chuỗi tự do được dò, số giữ kiểu")
    void s06NestedJsonIsSanitizedStructurally() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode in = mapper.readTree("""
                {"call":{"appUserId":"UKVIYF7A4DW",
                         "profile":{"phone":"0987654321","password":123456,
                                    "note":"goi tu 203.0.113.7, user UKVIYF7A4DW"},
                         "iceServers":[{"urls":"turn:198.51.100.10:3478","credential":"s3cr3t"}]},
                 "metrics":{"audio.audioMos":4.3,"isp":"VNPT"}}""");

        JsonNode out = new JsonSanitizer(sanitizer).sanitize(in, sanitizer.newSession());
        String json = mapper.writeValueAsString(out);

        assertThat(out.at("/call/profile").has("password")).isFalse();             // DROP: bỏ cả trường
        assertThat(out.at("/call/iceServers/0").has("credential")).isFalse();
        assertThat(out.at("/call/appUserId").asText()).matches("USER_[0-9a-f]{6}");
        assertThat(out.at("/call/profile/phone").asText()).isEqualTo("[CONTACT_REDACTED]");
        // định danh gặp ở nhánh khác vẫn bị thay trong chuỗi tự do
        assertThat(out.at("/call/profile/note").asText()).isEqualTo(
                "goi tu [IP_REDACTED], user " + out.at("/call/appUserId").asText());
        assertThat(out.at("/metrics/audio.audioMos").isNumber()).isTrue();         // ALLOW: giữ kiểu số
        assertThat(json).doesNotContain("UKVIYF7A4DW").doesNotContain("198.51.100.10")
                .doesNotContain("s3cr3t").doesNotContain("123456").doesNotContain("0987654321");
        assertThat(in.at("/call/appUserId").asText()).isEqualTo("UKVIYF7A4DW");     // không sửa bản gốc
    }
}
