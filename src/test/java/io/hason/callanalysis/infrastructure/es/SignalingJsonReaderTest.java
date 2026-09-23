package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalingJsonReaderTest {

    private final SignalingJsonReader reader = new SignalingJsonReader(new ObjectMapper());

    /** Rút gọn từ ai20k_sample/fail/1B009D42.../signaling.json, giữ nguyên hình dạng thật. */
    private static final String SAMPLE = """
            {
              "callId": "1B009D42-49CD-479E-B26C-3A2994AEB720",
              "environment": "production",
              "total_matching": 3,
              "returned": 2,
              "truncated": true,
              "events": [
                {
                  "@timestamp": "2026-09-21T08:44:28.953756952Z",
                  "service": "SCYZPESAVDK",
                  "level": "WARN",
                  "cmd": "INIT_CALL",
                  "csid": "bNuT0d9v",
                  "requestId": "9c5ef435-0177-4d3a-bb0f-5447b319a873",
                  "appUserId": "UULM3IK5WPW",
                  "isp": "MOBIFONE",
                  "asn": "AS131429",
                  "countryCode": "VN"
                },
                {
                  "@timestamp": "2026-09-21T08:44:28.969039058Z",
                  "service": "S46TQW3OJDW",
                  "level": "INFO",
                  "cmd": "INIT_CALL",
                  "csid": "bNuT0d9v",
                  "requestId": "c3d457b3-efd1-40ad-965e-f140da0fc566",
                  "appUserId": "UULM3IK5WPW",
                  "countryCode": "VN",
                  "latencyMs": 5
                }
              ]
            }
            """;

    @Test
    @DisplayName("callId ở cấp ngoài được chèn vào từng document")
    void callIdFromEnvelopeIsCopiedIntoEveryDocument() throws Exception {
        List<SignalingDocument> docs = reader.read(SAMPLE);

        assertThat(docs).hasSize(2);
        assertThat(docs).allSatisfy(d ->
                assertThat(d.callId()).isEqualTo("1B009D42-49CD-479E-B26C-3A2994AEB720"));
    }

    @Test
    @DisplayName("timestamp giữ nguyên 9 chữ số nano, không làm tròn")
    void nanosecondPrecisionIsPreserved() throws Exception {
        assertThat(reader.read(SAMPLE).getFirst().timestamp())
                .isEqualTo("2026-09-21T08:44:28.953756952Z");
    }

    @Test
    @DisplayName("trường vắng mặt trả về null, KHÔNG mặc định về 0 hay chuỗi rỗng")
    void missingFieldsBecomeNullNotZero() throws Exception {
        List<SignalingDocument> docs = reader.read(SAMPLE);

        // event đầu: có isp/asn, không có latencyMs
        assertThat(docs.get(0).isp()).isEqualTo("MOBIFONE");
        assertThat(docs.get(0).latencyMs()).isNull();

        // event sau: không có isp/asn, có latencyMs
        assertThat(docs.get(1).isp()).isNull();
        assertThat(docs.get(1).asn()).isNull();
        assertThat(docs.get(1).latencyMs()).isEqualTo(5);

        // callSessionId vắng mặt ở cả hai
        assertThat(docs).allSatisfy(d -> assertThat(d.callSessionId()).isNull());
    }

    @Test
    @DisplayName("metadata truncated được mang theo từng document để báo vào Giới hạn dữ liệu")
    void truncationMetadataIsCarried() throws Exception {
        assertThat(reader.read(SAMPLE)).allSatisfy(d -> {
            assertThat(d.sourceTruncated()).isTrue();
            assertThat(d.sourceReturned()).isEqualTo(2);
            assertThat(d.sourceTotalMatching()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("_id tất định: đọc lại cùng nội dung cho ra cùng bộ id")
    void documentIdIsDeterministic() throws Exception {
        List<String> first = reader.read(SAMPLE).stream().map(SignalingDocument::documentId).toList();
        List<String> second = reader.read(SAMPLE).stream().map(SignalingDocument::documentId).toList();

        assertThat(first).isEqualTo(second);
        assertThat(first).containsExactly(
                "1B009D42-49CD-479E-B26C-3A2994AEB720:0",
                "1B009D42-49CD-479E-B26C-3A2994AEB720:1");
    }

    @Test
    @DisplayName("ordinal giữ thứ tự gốc trong file, dùng làm tie-break khi timestamp trùng")
    void ordinalPreservesOriginalOrder() throws Exception {
        assertThat(reader.read(SAMPLE)).extracting(SignalingDocument::ordinal).containsExactly(0, 1);
    }

    @Test
    @DisplayName("file hỏng báo lỗi rõ ràng thay vì sinh dữ liệu sai")
    void malformedEnvelopeIsRejectedClearly() {
        assertThatThrownBy(() -> reader.read("""
                {"environment":"production","events":[]}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callId");

        assertThatThrownBy(() -> reader.read("""
                {"callId":"CALL-1","events":"khong-phai-mang"}"""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("events");
    }

    @Test
    @DisplayName("danh sách events rỗng vẫn hợp lệ, trả về danh sách rỗng")
    void emptyEventListIsValid() throws Exception {
        assertThat(reader.read("""
                {"callId":"CALL-1","events":[]}""")).isEmpty();
    }
}
