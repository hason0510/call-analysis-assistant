package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.request.AiIntent;
import io.hason.callanalysis.domain.request.SafeQuestion;
import io.hason.callanalysis.service.port.IntentClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Adapter AI phân loại câu hỏi (T4) trên {@link OpenAiChatClient}. Chỉ gửi câu hỏi đã làm sạch —
 * không gửi gì về cuộc gọi: phân loại intent không cần log.
 *
 * Log chỉ gồm các trường MVP mục 6.2 cho phép; KHÔNG log câu hỏi hay câu trả lời.
 */
@Component
public class OpenAiIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(OpenAiIntentClassifier.class);
    static final String SCHEMA = "schema/request-intent.schema.json";
    static final String SYSTEM_PROMPT = "prompts/parse-request.system.txt";

    private final ObjectMapper mapper;
    private final OpenAiChatClient client;
    private final JsonNode schema;
    private final String systemPrompt;

    public OpenAiIntentClassifier(ObjectMapper mapper, OpenAiChatClient client) {
        this.mapper = mapper;
        this.client = client;
        this.schema = client.readJson(SCHEMA);
        this.systemPrompt = OpenAiChatClient.readText(SYSTEM_PROMPT);
    }

    @Override
    public AiIntent classify(String requestId, SafeQuestion question) {
        long started = System.nanoTime();
        OpenAiChatClient.Completion completion;
        try {
            completion = client.complete(prompt(question, (reason, ms) -> AiCallLog.retrying(log, requestId, null,
                    client.model(), ms, reason.name())));
        } catch (AiUnavailableException e) {
            // Chưa biết cuộc gọi nào ở bước này: call_id bỏ trống
            AiCallLog.write(log, requestId, null, client.model(), (System.nanoTime() - started) / 1_000_000, null,
                    e.reason().name());
            throw e;
        }
        TokenUsage usage = new TokenUsage(completion.promptTokens(), completion.completionTokens());
        try {
            AiIntent intent = read(completion.content()).withUsage(usage);
            AiCallLog.write(log, requestId, null, client.model(), completion.latencyMs(), usage, null);
            return intent;
        } catch (AiUnavailableException e) {
            AiCallLog.write(log, requestId, null, client.model(), completion.latencyMs(), usage, e.reason().name());
            throw e;
        }
    }

    String requestBody(SafeQuestion question) {
        return client.requestBody(prompt(question, OpenAiChatClient.RetryListener.NONE));
    }

    AiIntent parseResponse(String body) {
        return read(client.parseCompletion(body, 0).content());
    }

    /**
     * Rào câu hỏi bằng <<< >>> để model tách rõ dữ liệu cần phân loại với chỉ dẫn. Chỉ là cách trình bày
     * prompt, KHÔNG phải cơ chế phòng thủ prompt injection — việc đó nằm ngoài phạm vi (MVP mục 9).
     */
    private OpenAiChatClient.Prompt prompt(SafeQuestion question, OpenAiChatClient.RetryListener onRetry) {
        return new OpenAiChatClient.Prompt(systemPrompt, "<<<\n" + question.text() + "\n>>>", "request_intent", schema,
                onRetry);
    }

    private AiIntent read(String content) {
        try {
            return mapper.readValue(content, AiIntent.class);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời không phải JSON hợp lệ", e);
        }
    }
}
