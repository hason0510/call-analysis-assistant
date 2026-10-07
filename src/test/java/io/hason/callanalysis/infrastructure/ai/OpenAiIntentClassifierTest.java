package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.request.AiIntent;
import io.hason.callanalysis.domain.request.SafeQuestion;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dựng request và đọc response mà KHÔNG gọi mạng. */
class OpenAiIntentClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final SensitiveDataSanitizer SANITIZER = new SensitiveDataSanitizer(
            new SensitiveDataInventoryLoader().load(), Pseudonymizer.withRandomKey());

    private OpenAiIntentClassifier classifier(String key) {
        return new OpenAiIntentClassifier(mapper,
                new OpenAiChatClient(mapper, "http://127.0.0.1:1", "gpt-4o-mini", key, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("request: strict schema 3 intent, chỉ gửi câu hỏi đã che, rào bằng <<< >>>")
    void requestCarriesOnlyTheSanitizedQuestion() throws Exception {
        JsonNode body = mapper.readTree(classifier("sk-test")
                .requestBody(SafeQuestion.of("Gọi 0912345678 không nghe được", SANITIZER)));

        JsonNode schema = body.path("response_format").path("json_schema");
        assertThat(schema.path("strict").asBoolean()).isTrue();
        assertThat(schema.path("schema").path("properties").path("intent").path("enum"))
                .extracting(JsonNode::asText).containsExactly("ANALYZE_CALL", "ANALYZE_WITH_FOCUS", "OUT_OF_SCOPE");
        String user = body.path("messages").path(1).path("content").asText();
        assertThat(user).startsWith("<<<\n").endsWith("\n>>>").contains("không nghe được").doesNotContain("0912345678");
        assertThat(body.path("temperature").asInt()).isZero();
    }

    @Test
    @DisplayName("response hợp lệ -> giữ chuỗi thô; nội dung không phải JSON -> INVALID_RESPONSE")
    void parsesResponse() {
        assertThat(classifier("sk-test").parseResponse(response("{\"intent\":\"ANALYZE_WITH_FOCUS\",\"focus\":\"bên nhận\"}")))
                .isEqualTo(new AiIntent("ANALYZE_WITH_FOCUS", "bên nhận"));
        assertThatThrownBy(() -> classifier("sk-test").parseResponse(response("OUT_OF_SCOPE")))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    private String response(String content) {
        var root = mapper.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", content);
        root.putObject("usage").put("prompt_tokens", 300).put("completion_tokens", 12);
        return root.toString();
    }
}
