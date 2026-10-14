package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Log JSON (profile web, Sprint 3 T5): đúng một dòng JSON, mọi chuỗi đã qua sanitizer như log chữ. */
class SanitizedJsonLogFormatterTest {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnLWFiYw";

    private final Logger logger = new LoggerContext().getLogger("io.hason.callanalysis.Test");

    private static LoggingEvent event(Logger logger, String message, Map<String, String> mdc, Throwable error,
                                      KeyValuePair... pairs) {
        LoggingEvent e = new LoggingEvent("fqcn", logger, Level.WARN, message, error, null);
        e.setMDCPropertyMap(mdc);
        List<KeyValuePair> list = new ArrayList<>(List.of(pairs));
        list.forEach(e::addKeyValuePair);
        return e;
    }

    private static JsonNode parse(String line) throws Exception {
        assertThat(line).endsWith("\n").doesNotContain("\n\n");
        assertThat(line.strip().lines()).hasSize(1);
        return new ObjectMapper().readTree(line);
    }

    @Test
    @DisplayName("một dòng JSON: khoá cố định, MDC, trường key-value giữ kiểu số / boolean / map, trường null bỏ qua")
    void structure() throws Exception {
        String line = new SanitizedJsonLogFormatter().format(event(logger, "AI call", Map.of("request_id", "req-mdc"),
                null,
                new KeyValuePair("request_id", "req-1"),
                new KeyValuePair("latency_ms", 840L),
                new KeyValuePair("degraded", true),
                new KeyValuePair("token_usage", Map.of("prompt", 10)),
                new KeyValuePair("fallback_reason", null)));

        JsonNode json = parse(line);
        assertThat(json.fieldNames()).toIterable().startsWith("@timestamp", "level", "logger", "message");
        assertThat(json.path("level").asText()).isEqualTo("WARN");
        assertThat(json.path("message").asText()).isEqualTo("AI call");
        assertThat(json.path("request_id").asText()).isEqualTo("req-1");        // trường của sự kiện thắng MDC
        assertThat(json.path("latency_ms").isNumber()).isTrue();
        assertThat(json.path("degraded").isBoolean()).isTrue();
        assertThat(json.path("token_usage").path("prompt").asInt()).isEqualTo(10);
        assertThat(json.has("fallback_reason")).isFalse();
    }

    @Test
    @DisplayName("S08 — bí mật trong thông điệp, giá trị trường, MDC và stack trace đều bị che")
    void everythingIsSanitized() throws Exception {
        String line = new SanitizedJsonLogFormatter().format(event(logger, "token " + JWT,
                Map.of("trace", "Bearer " + JWT),
                new IllegalStateException("401 for Bearer " + JWT),
                new KeyValuePair("note", "Authorization: Bearer " + JWT),
                new KeyValuePair("list", List.of("x " + JWT))));

        assertThat(line).doesNotContain("eyJ").contains("[REDACTED]");
        assertThat(parse(line).path("error").asText()).contains("IllegalStateException");
    }
}
