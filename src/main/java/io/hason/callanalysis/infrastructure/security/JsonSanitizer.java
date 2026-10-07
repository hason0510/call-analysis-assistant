package io.hason.callanalysis.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.hason.callanalysis.domain.security.HandlingPolicy;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.security.SensitiveField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Làm sạch JSON lồng nhau (MVP mục 6.4, ca S06) theo CẤU TRÚC chứ không theo văn bản: khoá khớp
 * mục DROP thì bỏ hẳn cả trường, khoá định danh thì thay giá trị, chuỗi còn lại đi qua bộ dò văn
 * bản tự do. Làm theo văn bản thì {@code "password": 123456} (giá trị số) thành JSON hỏng.
 *
 * Hai lượt: lượt đầu nhớ mọi định danh ở mọi độ sâu, lượt sau mới thay — nên appUserId gặp ở
 * nhánh sau vẫn được thay ở chuỗi tự do của nhánh trước.
 */
public class JsonSanitizer {

    private final SensitiveDataSanitizer sanitizer;

    public JsonSanitizer(SensitiveDataSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public JsonNode sanitize(JsonNode node, SensitiveDataSanitizer.Session session) {
        JsonNode copy = node.deepCopy();
        collect(copy, session);
        return clean(copy, session);
    }

    private void collect(JsonNode node, SensitiveDataSanitizer.Session session) {
        if (node instanceof ObjectNode obj) {
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if (e.getValue().isValueNode()) {
                    session.remember(e.getKey(), e.getValue().asText());
                } else {
                    collect(e.getValue(), session);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(child -> collect(child, session));
        } else if (node.isTextual()) {
            session.collect(node.asText());
        }
    }

    private JsonNode clean(JsonNode node, SensitiveDataSanitizer.Session session) {
        if (node instanceof ObjectNode obj) {
            List<String> drop = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                Optional<SensitiveField> policy = sanitizer.policyForKey(e.getKey());
                JsonNode value = e.getValue();
                if (policy.isPresent() && policy.get().policy() == HandlingPolicy.DROP) {
                    drop.add(e.getKey());
                } else if (policy.isPresent() && policy.get().policy() == HandlingPolicy.ALLOW) {
                    // giữ nguyên — chỉ số chất lượng, ISP… là dữ liệu chính để phân tích
                } else if (policy.isPresent() && value.isValueNode() && !value.isNull()) {
                    e.setValue(TextNode.valueOf(session.sanitizeValue(policy.get(), value.asText())));
                } else {
                    e.setValue(clean(value, session));
                }
            }
            drop.forEach(obj::remove);
            return obj;
        }
        if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, clean(arr.get(i), session));
            }
            return arr;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(session.sanitize(node.asText()).text());
        }
        return node;
    }
}
