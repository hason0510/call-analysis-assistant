package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import io.hason.callanalysis.service.port.CallCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter Elasticsearch cho {@link CallCatalog}: mọi giá trị {@code callId} khác nhau trong index signaling.
 *
 * Dùng composite aggregation có phân trang thay cho terms aggregation: terms trả "top N" (giới hạn số bucket), còn
 * composite duyệt hết, đã sắp xếp theo khoá — đúng thứ tự tất định mà báo cáo cần.
 */
@Component
public class ElasticsearchCallCatalog implements CallCatalog {

    static final int PAGE_SIZE = 1_000;
    private static final String AGG = "call_ids";
    private static final String KEY = "callId";

    private final ElasticsearchClient client;
    private final int pageSize;

    @Autowired
    public ElasticsearchCallCatalog(ElasticsearchClient client) {
        this(client, PAGE_SIZE);
    }

    /** Trang nhỏ để test đi đúng đường phân trang mà không phải nạp hơn 1 000 Call-ID. */
    ElasticsearchCallCatalog(ElasticsearchClient client, int pageSize) {
        this.client = client;
        this.pageSize = pageSize;
    }

    @Override
    public List<String> callIds() {
        List<String> ids = new ArrayList<>();
        Map<String, FieldValue> after = null;
        try {
            do {
                Map<String, FieldValue> from = after;
                SearchResponse<Void> response = client.search(s -> s
                                .index(SignalingIndex.NAME)
                                .size(0)
                                .aggregations(AGG, a -> a.composite(c -> {
                                    c.size(pageSize).sources(List.of(Map.of(KEY,
                                            CompositeAggregationSource.of(cs -> cs.terms(t -> t.field(KEY))))));
                                    return from == null ? c : c.after(from);
                                })),
                        Void.class);
                CompositeAggregate page = response.aggregations().get(AGG).composite();
                for (CompositeBucket bucket : page.buckets().array()) {
                    ids.add(bucket.key().get(KEY).stringValue());
                }
                after = page.buckets().array().size() < pageSize || page.afterKey().isEmpty() ? null : page.afterKey();
            } while (after != null);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được danh sách Call-ID từ index " + SignalingIndex.NAME, e);
        }
        return List.copyOf(ids);
    }
}
