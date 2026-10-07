package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiContextFixtures;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.ai.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dựng request và đọc response mà KHÔNG gọi mạng; lời gọi thật cần key, kiểm tay. */
class OpenAiAnalyzerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final AiContext CONTEXT = AiContextFixtures.withEvidence("req-1", "Phân tích giúp mình", "EV01");

    private OpenAiAnalyzer analyzer(String key) {
        return new OpenAiAnalyzer(mapper,
                new OpenAiChatClient(mapper, "http://127.0.0.1:1", "gpt-4o-mini", key, Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("chưa đặt OPENAI_API_KEY -> NOT_CONFIGURED, không gửi request nào")
    void missingKeyIsNotConfigured() {
        assertThatThrownBy(() -> analyzer("").analyze(CONTEXT))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
    }

    @Test
    @DisplayName("provider không phản hồi -> PROVIDER_ERROR, không ném lỗi lạ ra ngoài")
    void unreachableProviderIsProviderError() {
        assertThatThrownBy(() -> analyzer("sk-test").analyze(CONTEXT))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isIn(Reason.PROVIDER_ERROR, Reason.TIMEOUT));
    }

    @Test
    @DisplayName("request: gpt-4o-mini, temperature 0, strict json_schema, có câu hỏi và context")
    void requestBodyUsesStrictStructuredOutput() throws Exception {
        JsonNode body = mapper.readTree(analyzer("sk-test").requestBody(CONTEXT));

        assertThat(body.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.path("temperature").asInt()).isZero();
        JsonNode format = body.path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        String user = body.path("messages").path(1).path("content").asText();
        assertThat(user).contains("Phân tích giúp mình").contains("\"ruleVerdict\":{\"verdict\":\"FAIL\"")
                .contains("\"id\":\"EV01\"");
    }

    @Test
    @DisplayName("số token provider báo được gắn vào kết quả (T11 so token giữa các cách dựng context)")
    void usageIsAttached() {
        String content = "{\"verdict\":\"FAIL\",\"qualityFlag\":false,\"issueCategory\":\"ICE_FAILURE\","
                + "\"confidenceLevel\":\"HIGH\",\"summary\":\"s\",\"evidenceIds\":[\"EV01\"],"
                + "\"analysis\":\"a\",\"suggestions\":[]}";
        OpenAiChatClient stub = new OpenAiChatClient(mapper, "http://127.0.0.1:1", "gpt-4o-mini", "sk-test",
                Duration.ofSeconds(1)) {
            @Override
            public Completion complete(Prompt prompt) {
                return new Completion(content, 2962, 175, 5);
            }
        };

        AiAnalysis ai = new OpenAiAnalyzer(mapper, stub).analyze(CONTEXT);

        assertThat(ai.verdict()).isEqualTo("FAIL");
        assertThat(ai.usage()).isEqualTo(new TokenUsage(2962, 175));
    }

    @Test
    @DisplayName("key không bao giờ nằm trong thân request")
    void keyIsNotInRequestBody() {
        assertThat(analyzer("sk-secret-123").requestBody(CONTEXT)).doesNotContain("sk-secret-123");
    }

    @Test
    @DisplayName("response hợp lệ -> AiAnalysis giữ nguyên chuỗi thô, đọc được token usage")
    void parsesStructuredContent() {
        String content = """
                {"verdict":"FAIL","qualityFlag":false,"issueCategory":"ICE_FAILURE","confidenceLevel":"HIGH",
                 "summary":"ICE thất bại.","evidenceIds":["EV01"],"analysis":"...","suggestions":["Kiểm tra TURN"]}""";
        OpenAiAnalyzer.Parsed p = analyzer("sk-test").parseResponse(response(content, "stop", null));

        AiAnalysis a = p.analysis();
        assertThat(a.verdict()).isEqualTo("FAIL");
        assertThat(a.issueCategory()).isEqualTo("ICE_FAILURE");
        assertThat(a.evidenceIds()).containsExactly("EV01");
        assertThat(p.promptTokens()).isEqualTo(812);
        assertThat(p.completionTokens()).isEqualTo(95);
    }

    @Test
    @DisplayName("model từ chối -> REFUSED")
    void refusalIsReported() {
        assertThatThrownBy(() -> analyzer("sk-test").parseResponse(response(null, "stop", "I can't help")))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.REFUSED));
    }

    @Test
    @DisplayName("bị cắt vì hết token -> INVALID_RESPONSE, không cố parse nửa JSON")
    void truncatedAnswerIsInvalid() {
        assertThatThrownBy(() -> analyzer("sk-test").parseResponse(response("{\"verdict\":\"FA", "length", null)))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    @Test
    @DisplayName("nội dung không phải JSON (G02) -> INVALID_RESPONSE")
    void nonJsonContentIsInvalid() {
        assertThatThrownBy(() -> analyzer("sk-test").parseResponse(response("Cuộc gọi bị lỗi ICE.", "stop", null)))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    }

    private String response(String content, String finishReason, String refusal) {
        var root = mapper.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content);
        message.put("refusal", refusal);
        root.putObject("usage").put("prompt_tokens", 812).put("completion_tokens", 95);
        return root.toString();
    }
}
