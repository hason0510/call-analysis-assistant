package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chuyen noi dung mot file signaling.json thanh danh sach document.
 *
 * Lop nay KHONG cham dia va KHONG cham Elasticsearch — nho vay test duoc bang
 * chuoi JSON viet thang trong test, chay trong mili giay.
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
        // callId nam o cap NGOAI, khong nam trong tung event -> phai chen vao moi document
        String callId = text(root, "callId");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("thieu truong callId o cap ngoai");
        }
        JsonNode events = root.path("events");
        if (!events.isArray()) {
            throw new IllegalArgumentException("truong 'events' khong phai mang");
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

    /** Tra ve null thay vi chuoi "null" khi truong vang mat — 63/1059 event thieu isp/asn. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
