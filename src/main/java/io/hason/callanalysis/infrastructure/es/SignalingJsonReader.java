package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.signaling.SignalingMessages;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chuyển nội dung một file signaling.json thành danh sách document.
 *
 * Đọc được HAI định dạng export của cùng một dữ liệu, chỉ khác tên trường:
 * <pre>
 *   gọn (ai20k_sample)        thô (ai20k_new_dataset, 3/7 file)
 *   callId                    call_id
 *   events[]                  logs[]
 *   events[].cmd              logs[].grpcMethod      (cùng bộ giá trị INIT_CALL, INVITE, BYE_ACK_BYE...)
 *   events[].service          logs[].service.name
 *   events[].level            logs[].log.level
 *   truncated                 (không có) -> returned &lt; total_matching
 * </pre>
 * Các trường chỉ bản thô mới có ({@code message}, {@code clientIp}, {@code callerUserId},
 * {@code kubernetes}...) bị BỎ có chủ đích: rule không dùng, và {@code message} là chữ tự do
 * còn {@code clientIp} là IP thật — đưa vào index thì phải qua Sanitizer (MVP mục 6.2).
 * Từ {@code message} chỉ giữ CỜ có kiểu ({@code noSessionsFound}, xem {@link SignalingMessages}), không giữ chữ.
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
        String callId = firstText(root, "callId", "call_id");
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("thiếu trường callId (hoặc call_id) ở cấp ngoài");
        }
        JsonNode events = root.has("events") ? root.path("events") : root.path("logs");
        if (!events.isArray()) {
            throw new IllegalArgumentException("trường 'events' (hoặc 'logs') không phải mảng");
        }

        int returned = root.path("returned").asInt(0);
        int totalMatching = root.path("total_matching").asInt(0);
        // Bản thô không có cờ truncated, nhưng có đủ hai con số để suy ra.
        boolean truncated = root.has("truncated")
                ? root.path("truncated").asBoolean(false)
                : root.has("returned") && root.has("total_matching") && returned < totalMatching;

        List<SignalingDocument> docs = new ArrayList<>(events.size());
        int ordinal = 0;
        for (JsonNode e : events) {
            docs.add(new SignalingDocument(
                    callId,
                    ordinal++,
                    text(e, "@timestamp"),
                    service(e),
                    level(e),
                    firstText(e, "cmd", "grpcMethod"),
                    text(e, "csid"),
                    text(e, "requestId"),
                    text(e, "appUserId"),
                    text(e, "callSessionId"),
                    text(e, "isp"),
                    text(e, "asn"),
                    text(e, "countryCode"),
                    e.hasNonNull("latencyMs") ? e.get("latencyMs").asInt() : null,
                    SignalingMessages.noSessionsFound(text(e, "message")),
                    truncated,
                    returned,
                    totalMatching));
        }
        return docs;
    }

    /** Bản gọn: {@code "service": "S46..."}; bản thô: {@code "service": {"name": "S46..."}}. */
    private static String service(JsonNode event) {
        JsonNode v = event.get("service");
        return v != null && v.isObject() ? text(v, "name") : text(event, "service");
    }

    /** Bản gọn: {@code "level": "WARN"}; bản thô: {@code "log": {"level": "WARN"}}. */
    private static String level(JsonNode event) {
        String v = text(event, "level");
        return v != null ? v : text(event.path("log"), "level");
    }

    /** Giá trị của trường đầu tiên có mặt — tên theo bản gọn trước, bản thô sau. */
    private static String firstText(JsonNode node, String field, String fallbackField) {
        String v = text(node, field);
        return v != null ? v : text(node, fallbackField);
    }

    /** Trả về null thay vì chuỗi "null" khi trường vắng mặt — 63/1059 event thiếu isp/asn. */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
