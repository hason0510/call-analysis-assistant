package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chuyển nội dung một file signaling.json thành danh sách document.
 *
 * Lớp này KHÔNG chạm đĩa và KHÔNG chạm Elasticsearch — nhờ vậy test được bằng
 * chuỗi JSON viết thẳng trong test, chạy trong mili giây.
 */
public class SignalingJsonReader {

    private final ObjectMapper objectMapper;

    public SignalingJsonReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<SignalingDocument> read(String json) throws IOException {
        return read(objectMapper.readTree(json));
    }

    public List<SignalingDocument> read(JsonNode root) {
        // callId nằm ở cấp NGOÀI, không nằm trong từng event -> phải chèn vào mọi document
        String callId = text(root, "callId");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("thiếu trường callId ở cấp ngoài");
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) {
            throw new IllegalArgumentException("trường 'events' không phải mảng");
        }

        boolean truncated = root.path("truncated").asBoolean(false);
        int returned = root.path("returned").asInt(0);
        int totalMatching = root.path("total_matching").asInt(0);

        List<SignalingDocument> docs = new ArrayList<>(events.size());
        int ordinal = 0;
        for (JsonNode e : events) {
            docs.add(new SignalingDocument(
                    callId,
                    ordinal++,
                    text(e, "@timestamp"),
                    text(e, "service"),
                    text(e, "level"),
                    text(e, "cmd"),
                    text(e, "csid"),
                    text(e, "requestId"),
                    text(e, "appUserId"),
                    text(e, "callSessionId"),
                    text(e, "isp"),
                    text(e, "asn"),
                    text(e, "countryCode"),
                    e.hasNonNull("latencyMs") ? e.get("latencyMs").asInt() : null,
                    truncated,
                    returned,
                    totalMatching));
        }
        return docs;
    }

    /** Trả về null thay vì chuỗi "null" khi trường vắng mặt — 63/1059 event thiếu isp/asn. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
