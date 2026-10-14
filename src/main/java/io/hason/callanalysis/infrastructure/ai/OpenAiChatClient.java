package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.ai.RetryPolicy;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;

/**
 * Lời gọi OpenAI Chat Completions với Structured Outputs (`response_format: json_schema`,
 * `strict: true`) — phương án A của docs/ai-provider-proposal.md. Dùng chung cho phân tích cuộc gọi
 * (T5) và phân loại câu hỏi (T4): hai việc chỉ khác system prompt và schema.
 *
 * Key đọc từ biến môi trường OPENAI_API_KEY (qua application.yml), không bao giờ nằm trong repo.
 * Không có key thì ném NOT_CONFIGURED và bên gọi lùi về rule — app vẫn chạy được.
 *
 * Không log gì ở đây: bên gọi log bằng logger của chính nó, chỉ các trường MVP mục 6.2 cho phép,
 * nên log vẫn phân biệt được lời gọi phân tích với lời gọi phân loại mà không thêm trường lạ. Lần thử lại
 * báo cho bên gọi qua {@link Prompt#onRetry()} để bên gọi ghi một dòng log cho lần thử hỏng.
 *
 * Thử lại theo {@link RetryPolicy} (Sprint 3 T8): chỉ lỗi tạm thời, trong ngân sách thời gian của cả lời gọi.
 */
@Component
public class OpenAiChatClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final RetryPolicy retry;
    private final HttpClient http;

    @Autowired
    public OpenAiChatClient(ObjectMapper mapper,
                            @Value("${ai.openai.base-url}") String baseUrl,
                            @Value("${ai.openai.model}") String model,
                            @Value("${ai.openai.api-key:}") String apiKey,
                            @Value("${ai.openai.timeout}") Duration timeout,
                            @Value("${ai.openai.retry.max-attempts:2}") int maxAttempts,
                            @Value("${ai.openai.retry.backoff:500ms}") Duration backoff,
                            @Value("${ai.openai.retry.budget:45s}") Duration budget,
                            @Value("${ai.openai.retry.min-attempt-timeout:2s}") Duration minAttemptTimeout) {
        this(mapper, baseUrl, model, apiKey, timeout, new RetryPolicy(maxAttempts, backoff, budget, minAttemptTimeout));
    }

    /** Không thử lại — cho test dựng request / đọc response. */
    public OpenAiChatClient(ObjectMapper mapper, String baseUrl, String model, String apiKey, Duration timeout) {
        this(mapper, baseUrl, model, apiKey, timeout, RetryPolicy.none());
    }

    OpenAiChatClient(ObjectMapper mapper, String baseUrl, String model, String apiKey, Duration timeout,
                     RetryPolicy retry) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.retry = retry;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Nội dung model trả về (chuỗi JSON theo schema) cùng số token; latencyMs tính cả các lần thử lại. */
    public record Completion(String content, int promptTokens, int completionTokens, long latencyMs) {}

    /** Một lần thử hỏng sắp được thử lại — bên gọi ghi log. */
    @FunctionalInterface
    public interface RetryListener {
        void retrying(Reason reason, long attemptLatencyMs);

        RetryListener NONE = (reason, ms) -> { };
    }

    /** Một yêu cầu: system prompt + tin nhắn người dùng + schema đầu ra. */
    public record Prompt(String systemPrompt, String userMessage, String schemaName, JsonNode schema,
                         RetryListener onRetry) {

        public Prompt {
            onRetry = onRetry == null ? RetryListener.NONE : onRetry;
        }

        public Prompt(String systemPrompt, String userMessage, String schemaName, JsonNode schema) {
            this(systemPrompt, userMessage, schemaName, schema, RetryListener.NONE);
        }
    }

    public String model() {
        return model;
    }

    /** @throws AiUnavailableException mọi trường hợp không có nội dung dùng được */
    public Completion complete(Prompt prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AiUnavailableException(Reason.NOT_CONFIGURED, "Chưa đặt OPENAI_API_KEY");
        }
        long started = System.nanoTime();
        int attempt = 1;
        Duration attemptTimeout = timeout;
        while (true) {
            long attemptStarted = System.nanoTime();
            try {
                return attemptOnce(prompt, attemptTimeout, started);
            } catch (AiUnavailableException e) {
                // Bị ngắt (server đang tắt, request bị huỷ) thì dừng, không thử lại
                Optional<Duration> next = Thread.currentThread().isInterrupted() ? Optional.empty()
                        : retry.nextAttemptTimeout(e.reason(), attempt, Duration.ofNanos(System.nanoTime() - started),
                                timeout);
                if (next.isEmpty()) {
                    throw e;
                }
                prompt.onRetry().retrying(e.reason(), (System.nanoTime() - attemptStarted) / 1_000_000);
                try {
                    Thread.sleep(retry.backoff().toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                attemptTimeout = next.get();
                attempt++;
            }
        }
    }

    private Completion attemptOnce(Prompt prompt, Duration attemptTimeout, long started) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(attemptTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AiUnavailableException(Reason.TIMEOUT, "OpenAI quá " + attemptTimeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.PROVIDER_ERROR, "Không gọi được OpenAI", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException(Reason.PROVIDER_ERROR, "Bị ngắt khi chờ OpenAI", e);
        }
        int status = response.statusCode();
        if (status == 429) {
            throw rateLimited(response.body());
        }
        if (status / 100 != 2) {
            throw httpError(status, response.body());
        }
        return parseCompletion(response.body(), (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * Phân loại lỗi HTTP khác 429 (Sprint 3 T8). Trước đây mọi mã đều là PROVIDER_ERROR, nên không phân biệt được lỗi
     * nên thử lại (5xx — provider trục trặc) với lỗi thử lại vô ích (4xx — khoá sai, yêu cầu bị từ chối), và câu hỏi
     * vượt cửa sổ ngữ cảnh của model chỉ hiện "không gọi được dịch vụ AI". Chỉ đọc mã HTTP và {@code error.code};
     * phần còn lại của thân lỗi có thể lặp lại một phần request nên không đi đâu cả.
     */
    AiUnavailableException httpError(int status, String body) {
        if (status == 401 || status == 403) {
            return new AiUnavailableException(Reason.AUTH_FAILED, "OpenAI trả HTTP " + status);
        }
        if (status / 100 == 4) {
            return "context_length_exceeded".equals(errorCode(body))
                    ? new AiUnavailableException(Reason.INPUT_TOO_LARGE,
                            "OpenAI trả HTTP " + status + " (vượt cửa sổ ngữ cảnh)")
                    : new AiUnavailableException(Reason.REQUEST_REJECTED, "OpenAI trả HTTP " + status);
        }
        return new AiUnavailableException(Reason.PROVIDER_ERROR, "OpenAI trả HTTP " + status);
    }

    private String errorCode(String body) {
        try {
            return mapper.readTree(body == null ? "" : body).path("error").path("code").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * HTTP 429 có hai nghĩa cần xử lý khác nhau: hết hạn mức tài khoản (`insufficient_quota` — chờ không
     * giải quyết được, phải nạp thêm) và vượt tốc độ gọi (`rate_limit_exceeded` — thử lại sau ít phút).
     * Gộp cả hai vào PROVIDER_ERROR thì người dùng chỉ thấy "không gọi được dịch vụ AI". Chỉ đọc
     * `error.code`, một mã ngắn do OpenAI đặt; phần còn lại của thân lỗi không đi đâu cả.
     */
    AiUnavailableException rateLimited(String body) {
        // Thân lỗi không phải JSON thì errorCode rỗng: coi như vượt tốc độ, là nghĩa thường gặp của 429
        return "insufficient_quota".equals(errorCode(body))
                ? new AiUnavailableException(Reason.QUOTA_EXCEEDED, "OpenAI trả HTTP 429 (hết hạn mức)")
                : new AiUnavailableException(Reason.RATE_LIMITED, "OpenAI trả HTTP 429 (vượt tốc độ gọi)");
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
