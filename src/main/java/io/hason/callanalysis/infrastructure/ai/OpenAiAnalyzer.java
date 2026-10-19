package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiContextText;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.service.port.AiAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Cách trình bày context (T11 Sprint 2): {@code JSON} — structured, mặc định; {@code TEXT} — semi-structured
     * ({@link AiContextText}). Cùng dữ kiện, cùng system prompt; chỉ để so sánh trên benchmark.
     */
    public enum ContextFormat { JSON, TEXT }

    private final ObjectMapper mapper;
    private final OpenAiChatClient client;
    private final JsonNode schema;
    private final String systemPrompt;
    private final ContextFormat format;

    public OpenAiAnalyzer(ObjectMapper mapper, OpenAiChatClient client) {
        this(mapper, client, ContextFormat.JSON);
    }

    @Autowired
    public OpenAiAnalyzer(ObjectMapper mapper, OpenAiChatClient client,
                          @Value("${ai.context-format:json}") String format) {
        this(mapper, client, ContextFormat.valueOf(format.trim().toUpperCase(java.util.Locale.ROOT)));
    }

    public OpenAiAnalyzer(ObjectMapper mapper, OpenAiChatClient client, ContextFormat format) {
        this.mapper = mapper;
        this.client = client;
        this.schema = client.readJson(SCHEMA);
        this.systemPrompt = OpenAiChatClient.readText(SYSTEM_PROMPT);
        this.format = format;
    }

    public ContextFormat format() {
        return format;
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

    /**
     * Kiểm trường bắt buộc NGAY TẠI ĐÂY theo chính danh sách {@code required} của schema gửi đi, không chỉ trông
     * vào strict schema phía OpenAI: Jackson điền mặc định cho trường thiếu, nên {@code {"verdict":"FAIL"}} sẽ
     * thành qualityFlag=false, analysis=null mà không ai biết (MVP 7.1: G01-G05 kèm "missing fields").
     */
    private AiAnalysis read(String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời không phải JSON hợp lệ", e);
        }
        if (root == null || !root.isObject()) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời không phải đối tượng JSON");
        }
        List<String> missing = new ArrayList<>();
        schema.path("required").forEach(f -> {
            if (!root.hasNonNull(f.asText())) {
                missing.add(f.asText());
            }
        });
        if (!missing.isEmpty()) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời thiếu trường: " + missing);
        }
        try {
            return mapper.treeToValue(root, AiAnalysis.class);
        } catch (IOException e) {
            throw new AiUnavailableException(Reason.INVALID_RESPONSE, "Câu trả lời sai kiểu dữ liệu", e);
        }
    }

    /**
     * Toàn bộ phần dữ liệu cuộc gọi rời khỏi máy (phần còn lại của request là system prompt cố định
     * và JSON Schema). Public để `--ai-context` in đúng chuỗi này ra cho người duyệt xem.
     *
     * JSON gọn, không thụt lề: khoảng trắng cũng là token. Dạng {@code TEXT}: {@link AiContextText}.
     */
    public String userMessage(AiContext context) {
        StringBuilder text = new StringBuilder("Câu hỏi của người dùng:\n")
                .append(context.question() == null ? "" : context.question());
        if (context.focus() != null) {
            text.append("\n\nTrọng tâm câu hỏi: ").append(context.focus());
        }
        if (format == ContextFormat.TEXT) {
            return text.append("\n\nContext đã chuẩn hoá (văn bản):\n")
                    .append(AiContextText.render(context.payload())).toString();
        }
        try {
            return text.append("\n\nContext đã chuẩn hoá (JSON):\n")
                    .append(mapper.writeValueAsString(context.payload())).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
