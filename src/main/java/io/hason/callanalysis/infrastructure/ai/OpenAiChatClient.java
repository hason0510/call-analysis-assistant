package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Lời gọi OpenAI Chat Completions với Structured Outputs (`response_format: json_schema`,
 * `strict: true`) — phương án A của docs/ai-provider-proposal.md. Dùng chung cho phân tích cuộc gọi
 * (T5) và phân loại câu hỏi (T4): hai việc chỉ khác system prompt và schema.
 *
 * Key đọc từ biến môi trường OPENAI_API_KEY (qua application.yml), không bao giờ nằm trong repo.
 * Không có key thì ném NOT_CONFIGURED và bên gọi lùi về rule — app vẫn chạy được.
 *
 * Không log gì ở đây: bên gọi log bằng logger của chính nó, chỉ các trường MVP mục 6.2 cho phép,
 * nên log vẫn phân biệt được lời gọi phân tích với lời gọi phân loại mà không thêm trường lạ.
 */
@Component
public class OpenAiChatClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;

    public OpenAiChatClient(ObjectMapper mapper,
                            @Value("${ai.openai.base-url}") String baseUrl,
                            @Value("${ai.openai.model}") String model,
                            @Value("${ai.openai.api-key:}") String apiKey,
                            @Value("${ai.openai.timeout}") Duration timeout) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Nội dung model trả về (chuỗi JSON theo schema) cùng số token và thời gian chờ. */
    public record Completion(String content, int promptTokens, int completionTokens, long latencyMs) {}

    /** Một yêu cầu: system prompt + tin nhắn người dùng + schema đầu ra. */
    public record Prompt(String systemPrompt, String userMessage, String schemaName, JsonNode schema) {}

    public String model() {
        return model;
    }

    /** @throws AiUnavailableException mọi trường hợp không có nội dung dùng được */
    public Completion complete(Prompt prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiUnavailableException(Reason.NOT_CONFIGURED, "Chưa đặt OPENAI_API_KEY");
        }
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiUnavailableException(Reason.TIMEOUT, "OpenAI quá " + timeout.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.PROVIDER_ERROR, "Không gọi được OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException(Reason.PROVIDER_ERROR, "Bị ngắt khi chờ OpenAI", e);
        }
        if (response.statusCode() / 100 != 2) {
            // Chỉ giữ mã HTTP: thân lỗi có thể lặp lại một phần request.
            throw new AiUnavailableException(Reason.PROVIDER_ERROR, "OpenAI trả HTTP " + response.statusCode());
        }
        return parseCompletion(response.body(), (System.nanoTime() - started) / 1_000_000);
    }

    String requestBody(Prompt prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // Mục tiêu Consistency ≥ 95% (MVP 6.5): giảm ngẫu nhiên tối đa phía model.
        body.put("temperature", 0);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompt.systemPrompt());
        messages.addObject().put("role", "user").put("content", prompt.userMessage());
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", prompt.schemaName());
        jsonSchema.put("strict", true);
        jsonSchema.set("schema", prompt.schema());
        try {
            return mapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Completion parseCompletion(String body, long latencyMs) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Phản hồi của OpenAI không phải JSON", e);
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        if (message.hasNonNull("refusal")) {
            throw new AiUnavailableException(Reason.REFUSED, "Model từ chối trả lời");
        }
        if ("length".equals(choice.path("finish_reason").asText())) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời bị cắt do hết token");
        }
        String content = message.path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Không có nội dung trả về");
        }
        JsonNode usage = root.path("usage");
        return new Completion(content, usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0), latencyMs);
    }

    JsonNode readJson(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được " + path, e);
        }
    }

    static String readText(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được " + path, e);
        }
    }
}
