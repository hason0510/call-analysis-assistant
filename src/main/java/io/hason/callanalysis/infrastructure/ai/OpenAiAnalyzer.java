package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.service.port.AiAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Adapter AI phân tích cuộc gọi (T5) trên {@link OpenAiChatClient}.
 *
 * Log chỉ gồm các trường MVP mục 6.2 cho phép (request_id, model, latency, status, token);
 * KHÔNG log request hay response thô.
 */
@Component
public class OpenAiAnalyzer implements AiAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAnalyzer.class);
    static final String SCHEMA = "schema/ai-analysis.schema.json";
    static final String SYSTEM_PROMPT = "prompts/analyze-call.system.txt";

    private final ObjectMapper mapper;
    private final OpenAiChatClient client;
    private final JsonNode schema;
    private final String systemPrompt;

    public OpenAiAnalyzer(ObjectMapper mapper, OpenAiChatClient client) {
        this.mapper = mapper;
        this.client = client;
        this.schema = client.readJson(SCHEMA);
        this.systemPrompt = OpenAiChatClient.readText(SYSTEM_PROMPT);
    }

    @Override
    public AiAnalysis analyze(AiContext context) {
        long started = System.nanoTime();
        OpenAiChatClient.Completion completion;
        try {
            completion = client.complete(prompt(context, (reason, ms) -> AiCallLog.retrying(log, context.requestId(),
                    context.logCallId(), client.model(), ms, reason.name())));
        } catch (AiUnavailableException e) {
            AiCallLog.write(log, context.requestId(), context.logCallId(), client.model(),
                    (System.nanoTime() - started) / 1_000_000, null, e.reason().name());
            throw e;
        }
        TokenUsage usage = new TokenUsage(completion.promptTokens(), completion.completionTokens());
        try {
            AiAnalysis analysis = read(completion.content()).withUsage(usage);
            AiCallLog.write(log, context.requestId(), context.logCallId(), client.model(), completion.latencyMs(),
                    usage, null);
            return analysis;
        } catch (AiUnavailableException e) {
            // Đã tốn token mà câu trả lời không đọc được: vẫn ghi số token
            AiCallLog.write(log, context.requestId(), context.logCallId(), client.model(), completion.latencyMs(),
                    usage, e.reason().name());
            throw e;
        }
    }

    record Parsed(AiAnalysis analysis, int promptTokens, int completionTokens) {}

    String requestBody(AiContext context) {
        return client.requestBody(prompt(context, OpenAiChatClient.RetryListener.NONE));
    }

    Parsed parseResponse(String body) {
        OpenAiChatClient.Completion completion = client.parseCompletion(body, 0);
        return new Parsed(read(completion.content()), completion.promptTokens(), completion.completionTokens());
    }

    private OpenAiChatClient.Prompt prompt(AiContext context, OpenAiChatClient.RetryListener onRetry) {
        return new OpenAiChatClient.Prompt(systemPrompt, userMessage(context), "call_analysis", schema, onRetry);
    }

    private AiAnalysis read(String content) {
        try {
            return mapper.readValue(content, AiAnalysis.class);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời không phải JSON hợp lệ", e);
        }
    }

    /**
     * Toàn bộ phần dữ liệu cuộc gọi rời khỏi máy (phần còn lại của request là system prompt cố định
     * và JSON Schema). Public để `--ai-context` in đúng chuỗi này ra cho người duyệt xem.
     *
     * JSON gọn, không thụt lề: khoảng trắng cũng là token.
     */
    public String userMessage(AiContext context) {
        StringBuilder text = new StringBuilder("Câu hỏi của người dùng:\n")
                .append(context.question() == null ? "" : context.question());
        if (context.focus() != null) {
            text.append("\n\nTrọng tâm câu hỏi: ").append(context.focus());
        }
        try {
            return text.append("\n\nContext đã chuẩn hoá (JSON):\n")
                    .append(mapper.writeValueAsString(context.payload())).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
