package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import io.hason.callanalysis.domain.batch.BatchResult;
import io.hason.callanalysis.service.port.BatchResultSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ghi kết quả phân tích hàng loạt (T10) vào index {@value #INDEX} — dashboard T11 đọc để có phân bố trên toàn kho.
 *
 * Index riêng, không ghi vào {@code analysis-requests} của T9: bản ghi ở đó là request của người dùng (có latency,
 * fallback); trộn vào thì tỉ lệ fallback và độ trễ trên dashboard sai.
 *
 * Mỗi lượt XOÁ index rồi tạo lại: index là ảnh chụp lượt mới nhất. Không dùng mã giả làm id để ghi đè, vì khoá mã giả
 * có thể ngẫu nhiên giữa các lần chạy ({@code CALL_ANALYSIS_PSEUDONYM_KEY} chưa đặt) — khi đó ghi đè vẫn sinh bản trùng.
 *
 * Tài liệu chỉ gồm mã, số, cờ; Call-ID đã là mã giả (MVP 6.2).
 */
@Component
public class ElasticsearchBatchResultSink implements BatchResultSink {

    public static final String INDEX = "call-analysis-results";
    private static final String MAPPING = "es/call-analysis-results-mapping.json";
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchBatchResultSink.class);

    private final ElasticsearchClient client;

    public ElasticsearchBatchResultSink(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void replaceAll(Instant ranAt, List<BatchResult> results) {
        try {
            if (client.indices().exists(e -> e.index(INDEX)).value()) {
                client.indices().delete(d -> d.index(INDEX));
            }
            try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
                client.indices().create(c -> c.index(INDEX).withJson(mapping));
            }
            if (results.isEmpty()) {
                return;
            }
            List<BulkOperation> operations = results.stream()
                    .map(r -> BulkOperation.of(b -> b.index(i -> i.index(INDEX).document(document(ranAt, r)))))
                    .toList();
            // refresh=wait_for: mở dashboard ngay sau lệnh là thấy đủ bản ghi
            BulkResponse response = client.bulk(b -> b.operations(operations).refresh(Refresh.WaitFor));
            if (response.errors()) {
                long failed = response.items().stream().filter(i -> i.error() != null).count();
                throw new IllegalStateException(failed + "/" + results.size() + " bản ghi bị Elasticsearch từ chối");
            }
            log.atInfo().addKeyValue("documents", results.size()).log("Batch results written to " + INDEX);
        } catch (IOException e) {
            throw new UncheckedIOException("Không ghi được index " + INDEX, e);
        }
    }

    /** Khoá theo mapping; trường null bỏ qua. Thứ tự cố định để tài liệu đọc dễ trong Kibana. */
    static Map<String, Object> document(Instant ranAt, BatchResult r) {
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "@timestamp", ranAt == null ? null : ranAt.toString());
        put(d, "call_id", r.callId());
        put(d, "source_group", r.sourceGroup() == null ? null : r.sourceGroup().name());
        put(d, "verdict", r.verdict() == null ? null : r.verdict().name());
        put(d, "issue_category", r.issueCategory() == null ? null : r.issueCategory().name());
        put(d, "quality_flag", r.qualityFlag());
        put(d, "confidence", r.confidence() == null ? null : r.confidence().name());
        put(d, "evidence_count", r.evidenceCount());
        return d;
    }

    private static void put(Map<String, Object> d, String key, Object value) {
        if (value != null) {
            d.put(key, value);
        }
    }
}
