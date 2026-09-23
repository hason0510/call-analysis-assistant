package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.hason.callanalysis.service.port.SignalingSource;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Adapter Elasticsearch cho {@link SignalingSource}.
 *
 * Lop nay chi lam viec co hoc: truy van va anh xa document sang record tho.
 * Moi viec chuan hoa (parse timestamp, suy ra leg, gan EventType) thuoc tang domain,
 * nho vay chung test duoc ma khong can Elasticsearch.
 */
@Component
public class ElasticsearchSignalingSource implements SignalingSource {

    /** Cuoc goi nhieu event nhat trong data mau co 200 event; 10 000 la du rong. */
    private static final int MAX_EVENTS = 10_000;

    private final ElasticsearchClient client;

    public ElasticsearchSignalingSource(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public SignalingFetch fetchByCallId(String callId) {
        try {
            SearchResponse<SignalingDocument> response = client.search(s -> s
                            .index(SignalingIndex.NAME)
                            .size(MAX_EVENTS)
                            .query(q -> q.term(t -> t.field("callId").value(callId)))
                            // sap xep tat dinh: thoi gian truoc, roi thu tu goc trong file
                            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Asc)))
                            .sort(so -> so.field(f -> f.field("ordinal").order(SortOrder.Asc))),
                    SignalingDocument.class);

            List<SignalingDocument> docs = response.hits().hits().stream()
                    .map(Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            if (docs.isEmpty()) {
                return new SignalingFetch(callId, List.of(), false, 0, 0);
            }

            SignalingDocument first = docs.getFirst();
            return new SignalingFetch(
                    callId,
                    docs.stream().map(ElasticsearchSignalingSource::toRecord).toList(),
                    first.sourceTruncated(),
                    first.sourceReturned(),
                    first.sourceTotalMatching());

        } catch (IOException e) {
            throw new UncheckedIOException("Khong truy van duoc signaling cho callId=" + callId, e);
        }
    }

    private static RawSignalingRecord toRecord(SignalingDocument d) {
        return new RawSignalingRecord(
                d.callId(), d.ordinal(), d.timestamp(), d.service(), d.level(), d.cmd(),
                d.csid(), d.requestId(), d.appUserId(), d.callSessionId(),
                d.isp(), d.asn(), d.countryCode(), d.latencyMs());
    }
}
