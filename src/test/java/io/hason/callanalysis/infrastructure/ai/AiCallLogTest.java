package io.hason.callanalysis.infrastructure.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiContextFixtures;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MVP mục 6.2: log của AI call CHỈ chứa bảy trường cho phép, không nội dung request / response. */
class AiCallLogTest {

    private static final String CONTENT = "{\"verdict\":\"FAIL\",\"qualityFlag\":false,\"issueCategory\":\"ICE_FAILURE\","
            + "\"confidenceLevel\":\"HIGH\",\"summary\":\"Bí mật trong câu trả lời\",\"evidenceIds\":[\"EV01\"],"
            + "\"analysis\":\"a\",\"suggestions\":[]}";
    private static final AiContext CONTEXT = AiContextFixtures.withLogCallId("req-1", "CALL_a81f2c");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OpenAiAnalyzer.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private OpenAiAnalyzer analyzer(OpenAiChatClient.Completion completion, AiUnavailableException failure) {
        return new OpenAiAnalyzer(mapper, new OpenAiChatClient(mapper, "http://127.0.0.1:1", "gpt-4o-mini", "sk-test",
                Duration.ofSeconds(1)) {
            @Override
            public Completion complete(Prompt prompt) {
                if (failure != null) {
                    throw failure;
                }
                return completion;
            }
        });
    }

    private Map<String, Object> onlyEvent() {
        assertThat(appender.list).singleElement();
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo(AiCallLog.MESSAGE);
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(p -> p.key, p -> p.value));
    }

    @Test
    @DisplayName("thành công -> một dòng, chỉ trường cho phép, call_id là mã giả, có token_usage, không có nội dung AI")
    void successLine() {
        analyzer(new OpenAiChatClient.Completion(CONTENT, 2962, 175, 840), null).analyze(CONTEXT);

        Map<String, Object> fields = onlyEvent();
        assertThat(fields.keySet()).isSubsetOf(AiCallLog.FIELDS)
                .contains("request_id", "call_id", "model", "latency_ms", "result_status", "token_usage");
        assertThat(fields).containsEntry("call_id", "CALL_a81f2c").containsEntry("result_status", "OK")
                .containsEntry("latency_ms", 840L).doesNotContainKey("fallback_reason");
        assertThat(fields.get("token_usage")).isEqualTo(Map.of("prompt", 2962, "completion", 175));
        assertThat(appender.list.getFirst().getFormattedMessage() + fields).doesNotContain("Bí mật");
    }

    @Test
    @DisplayName("provider lỗi -> vẫn đúng một dòng, FALLBACK + fallback_reason, không token")
    void failureLine() {
        assertThatThrownBy(() -> analyzer(null, new AiUnavailableException(Reason.RATE_LIMITED, "429")).analyze(CONTEXT))
                .isInstanceOf(AiUnavailableException.class);

        Map<String, Object> fields = onlyEvent();
        assertThat(fields.keySet()).isSubsetOf(AiCallLog.FIELDS);
        assertThat(fields).containsEntry("result_status", "FALLBACK").containsEntry("fallback_reason", "RATE_LIMITED")
                .doesNotContainKey("token_usage");
    }

    @Test
    @DisplayName("trả lời không đọc được -> FALLBACK INVALID_RESPONSE, vẫn ghi token đã tốn")
    void unreadableAnswerKeepsTokens() {
        assertThatThrownBy(() -> analyzer(new OpenAiChatClient.Completion("không phải JSON", 100, 5, 10), null)
                .analyze(CONTEXT)).isInstanceOf(AiUnavailableException.class);

        Map<String, Object> fields = onlyEvent();
        assertThat(fields).containsEntry("fallback_reason", "INVALID_RESPONSE").containsKey("token_usage");
    }

    @Test
    @DisplayName("T8 — lần thử hỏng được thử lại: thêm một dòng RETRY, vẫn chỉ trường cho phép; dòng kết quả cuối như cũ")
    void retryLine() {
        OpenAiAnalyzer analyzer = new OpenAiAnalyzer(mapper, new OpenAiChatClient(mapper, "http://127.0.0.1:1",
                "gpt-4o-mini", "sk-test", Duration.ofSeconds(1)) {
            @Override
            public Completion complete(Prompt prompt) {
                prompt.onRetry().retrying(Reason.TIMEOUT, 30_000);
                return new Completion(CONTENT, 2962, 175, 31_000);
            }
        });

        analyzer.analyze(CONTEXT);

        assertThat(appender.list).hasSize(2);
        List<Map<String, Object>> lines = appender.list.stream()
                .map(e -> e.getKeyValuePairs().stream().collect(Collectors.toMap(p -> p.key, p -> p.value)))
                .toList();
        assertThat(lines).allSatisfy(f -> assertThat(f.keySet()).isSubsetOf(AiCallLog.FIELDS));
        assertThat(lines.get(0)).containsEntry("result_status", "RETRY").containsEntry("fallback_reason", "TIMEOUT")
                .containsEntry("call_id", "CALL_a81f2c");
        assertThat(lines.get(1)).containsEntry("result_status", "OK").containsEntry("latency_ms", 31_000L);
    }

    @Test
    @DisplayName("tập trường là đúng bảy thứ MVP 6.2 liệt kê")
    void allowedFieldsAreMvpList() {
        assertThat(AiCallLog.FIELDS).containsExactly("request_id", "call_id", "model", "latency_ms", "result_status",
                "token_usage", "fallback_reason");
    }
}
