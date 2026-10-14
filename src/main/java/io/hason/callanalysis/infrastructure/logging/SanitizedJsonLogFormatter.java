package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.infrastructure.security.SanitizerHolder;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Log JSON một dòng mỗi sự kiện (MVP 7.1 T5: "structured log, đã sanitize"). Bật ở profile {@code web} qua
 * {@code logging.structured.format.console} (application-web.yml) — server là nơi log được máy đọc. CLI giữ log chữ
 * vì report của CLI in qua log.
 *
 * Không dùng sẵn định dạng ECS / Logstash của Spring Boot: chúng ghi thông điệp và trường NGUYÊN VĂN, bỏ qua lớp
 * làm sạch mà pattern log chữ có ({@code %smsg}, {@code %sex}). Ở đây mọi chuỗi — thông điệp, giá trị trường, MDC,
 * stack trace — đi qua cùng sanitizer trước khi ghi.
 *
 * Khoá: {@code @timestamp}, {@code level}, {@code logger}, {@code message}, rồi MDC ({@code request_id}), rồi các
 * trường key-value của sự kiện (trùng khoá MDC thì trường của sự kiện thắng), cuối cùng {@code error} nếu có
 * exception. Trường giá trị null bỏ qua.
 */
public class SanitizedJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String format(ILoggingEvent event) {
        SensitiveDataSanitizer sanitizer = SanitizerHolder.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("@timestamp", event.getInstant().toString());
        out.put("level", event.getLevel().toString());
        out.put("logger", event.getLoggerName());
        out.put("message", sanitizer.sanitize(event.getFormattedMessage()).text());
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            mdc.forEach((k, v) -> put(out, k, v, sanitizer));
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            pairs.forEach(p -> put(out, p.key, p.value, sanitizer));
        }
        if (event.getThrowableProxy() != null) {
            out.put("error", sanitizer.sanitize(ThrowableProxyUtil.asString(event.getThrowableProxy())).text());
        }
        try {
            return JSON.writeValueAsString(out) + "\n";
        } catch (JsonProcessingException e) {
            // Không để một dòng log hỏng làm mất sự kiện: ghi tối thiểu, vẫn là JSON hợp lệ
            return "{\"level\":\"" + event.getLevel() + "\",\"message\":\"log không mã hoá được thành JSON\"}\n";
        }
    }

    private static void put(Map<String, Object> out, String key, Object value, SensitiveDataSanitizer sanitizer) {
        if (key == null || value == null) {
            return;
        }
        out.put(key, clean(value, sanitizer));
    }

    /** Số và boolean giữ kiểu; chuỗi (kể cả trong Map / danh sách) đi qua sanitizer. */
    private static Object clean(Object value, SensitiveDataSanitizer sanitizer) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> cleaned = new LinkedHashMap<>();
            map.forEach((k, v) -> cleaned.put(String.valueOf(k), v == null ? null : clean(v, sanitizer)));
            return cleaned;
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(v -> v == null ? null : clean(v, sanitizer)).toList();
        }
        return sanitizer.sanitize(String.valueOf(value)).text();
    }
}
