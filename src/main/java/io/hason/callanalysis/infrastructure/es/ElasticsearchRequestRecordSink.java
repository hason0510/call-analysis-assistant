package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.hason.callanalysis.domain.observability.RequestRecord;
import io.hason.callanalysis.service.port.RequestRecordSink;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Ghi {@link RequestRecord} vào index {@value #INDEX} để xem trên Kibana (MVP 7.1 T9). Chỉ bật khi
 * {@code call-analysis.request-log.enabled=true} (application-web.yml): lượt benchmark và lệnh CLI không ghi.
 *
 * Không được làm chậm hay làm hỏng request của người dùng:
 * - ghi trên MỘT luồng nền, hàng đợi có giới hạn ({@value #QUEUE_CAPACITY}); đầy thì bỏ bản ghi và đếm, không chặn;
 * - Elasticsearch lỗi thì cảnh báo một lần cho mỗi đợt lỗi liên tiếp, không ném ra ngoài;
 * - index tạo lần đầu ghi (mapping {@code dynamic: strict}, như index signaling).
 *
 * Tài liệu chỉ gồm mã, số, cờ của bản ghi — không chữ tự do — nên không cần qua Sanitizer (MVP 6.2).
 */
@Component
@ConditionalOnProperty(name = "call-analysis.request-log.enabled", havingValue = "true")
public class ElasticsearchRequestRecordSink implements RequestRecordSink {

    public static final String INDEX = "analysis-requests";
    static final int QUEUE_CAPACITY = 1_000;
    private static final String MAPPING = "es/analysis-requests-mapping.json";
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchRequestRecordSink.class);

    private final ElasticsearchClient client;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), r -> {
        Thread t = new Thread(r, "request-record-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean indexReady;
    private volatile boolean failing;
    private long dropped;

    public ElasticsearchRequestRecordSink(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public void record(RequestRecord record) {
        try {
            writer.execute(() -> write(record));
        } catch (RejectedExecutionException full) {
            synchronized (this) {
                dropped++;
                if (dropped == 1 || dropped % 100 == 0) {
                    log.atWarn().addKeyValue("dropped", dropped).log("Request record queue full");
                }
            }
        }
    }

    private void write(RequestRecord record) {
        try {
            ensureIndex();
            client.index(i -> i.index(INDEX).id(record.requestId()).document(document(record)));
            if (failing) {
                failing = false;
                log.info("Request record: ghi lại vào Elasticsearch được");
            }
        } catch (IOException | RuntimeException e) {
            if (!failing) {
                failing = true;
                log.atWarn().addKeyValue("error", e.getClass().getSimpleName())
                        .log("Request record: không ghi được vào Elasticsearch, bỏ qua cho tới khi ghi lại được");
            }
        }
    }

    private void ensureIndex() throws IOException {
        if (indexReady) {
            return;
        }
        if (!client.indices().exists(e -> e.index(INDEX)).value()) {
            try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
                client.indices().create(c -> c.index(INDEX).withJson(mapping));
            }
            log.info("Đã tạo index '{}' từ {}.", INDEX, MAPPING);
        }
        indexReady = true;
    }

    /** Khoá theo mapping; trường null bỏ qua. Thứ tự cố định để tài liệu đọc dễ trong Kibana. */
    static Map<String, Object> document(RequestRecord r) {
        Map<String, Object> d = new LinkedHashMap<>();
        put(d, "@timestamp", r.timestamp() == null ? null : r.timestamp().toString());
        put(d, "request_id", r.requestId());
        put(d, "result_status", r.resultStatus());
        put(d, "call_id", r.callId());
        put(d, "intent", r.intent());
        put(d, "intent_source", r.intentSource());
        put(d, "garbled_question", r.garbledQuestion());
        put(d, "files_attached", r.filesAttached());
        put(d, "verdict", r.verdict());
        put(d, "issue_category", r.issueCategory());
        put(d, "quality_flag", r.qualityFlag());
        put(d, "confidence", r.confidence());
        put(d, "analysis_source", r.analysisSource());
        put(d, "degraded", r.degraded());
        put(d, "fallback_reason", r.fallbackReason());
        put(d, "needs_review", r.needsReview());
        put(d, "latency_ms", r.latencyMs());
        put(d, "intent_ms", r.intentMs());
        put(d, "analysis_ms", r.analysisMs());
        put(d, "ai_ms", r.aiMs());
        put(d, "report_ms", r.reportMs());
        put(d, "prompt_tokens", r.promptTokens());
        put(d, "completion_tokens", r.completionTokens());
        put(d, "error", r.error());
        return d;
    }

    private static void put(Map<String, Object> d, String key, Object value) {
        if (value != null) {
            d.put(key, value);
        }
    }

    /** Ghi nốt bản ghi đang chờ khi tắt ứng dụng, tối đa 5 giây. */
    @PreDestroy
    void shutdown() throws InterruptedException {
        writer.shutdown();
        writer.awaitTermination(5, TimeUnit.SECONDS);
    }
}
