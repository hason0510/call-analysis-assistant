package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.observability.RequestRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Tài liệu ghi vào index analysis-requests (T9): khớp mapping strict, chỉ mã / số / cờ, trường null bỏ qua. */
class ElasticsearchRequestRecordSinkTest {

    private static final RequestRecord FULL = new RequestRecord(Instant.parse("2026-10-12T03:00:00Z"),
            "5a44c378-7e44-482a-94f7-a3c0e14322ed", "REPORT", "CALL_a78e1d", "ANALYZE_WITH_FOCUS", "AI", false, 2,
            "FAIL", "ICE_FAILURE", false, "MEDIUM", "AI", false, null, false, 3_790L, 740L, 1_200L, 1_800L, 50L,
            5_027, 155, null);

    private static Set<String> mappingFields() throws IOException {
        try (InputStream in = new ClassPathResource("es/analysis-requests-mapping.json").getInputStream()) {
            JsonNode mapping = new ObjectMapper().readTree(in).path("mappings");
            assertThat(mapping.path("dynamic").asText()).isEqualTo("strict");
            Set<String> fields = new HashSet<>();
            mapping.path("properties").fieldNames().forEachRemaining(fields::add);
            return fields;
        }
    }

    @Test
    @DisplayName("mỗi khoá của tài liệu có trong mapping strict, và mỗi trường của mapping đều được ghi khi có giá trị")
    void documentMatchesMapping() throws IOException {
        Map<String, Object> doc = ElasticsearchRequestRecordSink.document(FULL);
        RequestRecord error = new RequestRecord(Instant.now(), "r", "ERROR", null, null, null, false, 0, null, null,
                null, null, null, null, "TIMEOUT", null, 10L, null, null, null, null, null, null, "IllegalStateException");

        Set<String> written = new HashSet<>(doc.keySet());
        written.addAll(ElasticsearchRequestRecordSink.document(error).keySet());

        assertThat(mappingFields()).isEqualTo(written);
    }

    @Test
    @DisplayName("trường null không ghi; giá trị chuỗi chỉ là mã ngắn, không có chữ tự do")
    void noNullsNoFreeText() {
        Map<String, Object> doc = ElasticsearchRequestRecordSink.document(FULL);

        assertThat(doc).doesNotContainKeys("fallback_reason", "error").doesNotContainValue(null);
        assertThat(doc.values()).filteredOn(v -> v instanceof String)
                .allSatisfy(v -> assertThat((String) v).doesNotContain(" ").hasSizeLessThan(64));
        assertThat(doc).containsEntry("call_id", "CALL_a78e1d").containsEntry("latency_ms", 3_790L);
    }
}
