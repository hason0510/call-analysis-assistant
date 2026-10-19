package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.batch.BatchResult;
import io.hason.callanalysis.domain.batch.SourceGroup;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
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

/** Tài liệu ghi vào index call-analysis-results (T10): khớp mapping strict, chỉ mã / số / cờ. */
class ElasticsearchBatchResultSinkTest {

    private static final Instant RAN_AT = Instant.parse("2026-10-19T13:00:00Z");
    private static final BatchResult FAIL = new BatchResult("CALL_a78e1d", SourceGroup.WITH_CLIENT_LOGS,
            Verdict.FAIL, IssueCategory.TURN_FAILURE, false, ConfidenceLevel.MEDIUM, 7);

    @Test
    @DisplayName("mỗi khoá của tài liệu có trong mapping strict, và mỗi trường của mapping đều được ghi")
    void documentMatchesMapping() throws IOException {
        try (InputStream in = new ClassPathResource("es/call-analysis-results-mapping.json").getInputStream()) {
            JsonNode mapping = new ObjectMapper().readTree(in).path("mappings");
            Set<String> fields = new HashSet<>();
            mapping.path("properties").fieldNames().forEachRemaining(fields::add);

            assertThat(mapping.path("dynamic").asText()).isEqualTo("strict");
            assertThat(ElasticsearchBatchResultSink.document(RAN_AT, FAIL).keySet()).isEqualTo(fields);
        }
    }

    @Test
    @DisplayName("SUCCESS không loại lỗi -> bỏ trường; giá trị chuỗi chỉ là mã ngắn, không có chữ tự do")
    void noNullsNoFreeText() {
        BatchResult success = new BatchResult("CALL_0f12ab", SourceGroup.SIGNALING_ONLY, Verdict.SUCCESS, null,
                false, ConfidenceLevel.HIGH, 20);
        Map<String, Object> doc = ElasticsearchBatchResultSink.document(RAN_AT, success);

        assertThat(doc).doesNotContainKey("issue_category").doesNotContainValue(null);
        assertThat(doc.values()).filteredOn(v -> v instanceof String)
                .allSatisfy(v -> assertThat((String) v).doesNotContain(" ").hasSizeLessThan(64));
        assertThat(doc).containsEntry("call_id", "CALL_0f12ab").containsEntry("source_group", "SIGNALING_ONLY")
                .containsEntry("evidence_count", 20).containsEntry("@timestamp", "2026-10-19T13:00:00Z");
    }
}
