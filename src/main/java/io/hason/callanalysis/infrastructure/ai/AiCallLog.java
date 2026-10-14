package io.hason.callanalysis.infrastructure.ai;

import io.hason.callanalysis.domain.ai.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dòng log của MỘT lời gọi AI (MVP mục 6.2): chỉ gồm request_id, call_id (đã mã giả), model, latency, result_status,
 * token_usage, fallback_reason — không nội dung request / response. Thành công hay lỗi đều ghi đúng một dòng.
 *
 * Trường đi dạng key-value của SLF4J, không ghép vào câu: log JSON (profile web) ra mỗi trường một khoá, log chữ
 * của CLI in {@code khoá=giá trị} như trước. Một chỗ duy nhất dựng dòng này, nên test khẳng định được tập trường.
 */
final class AiCallLog {

    /** Tập trường được phép — MVP mục 6.2 liệt kê đúng bảy thứ. */
    static final List<String> FIELDS = List.of("request_id", "call_id", "model", "latency_ms", "result_status",
            "token_usage", "fallback_reason");

    static final String MESSAGE = "AI call";
    /** result_status của một lần thử hỏng sắp được thử lại (Sprint 3 T8); lý do ghi ở fallback_reason. */
    static final String RETRY = "RETRY";

    private AiCallLog() {
    }

    /**
     * @param callId         Call-ID ĐÃ mã giả; null khi chưa biết (bước phân loại câu hỏi)
     * @param usage          null khi provider không trả lời
     * @param fallbackReason null khi thành công
     */
    static void write(Logger log, String requestId, String callId, String model, long latencyMs,
                      TokenUsage usage, String fallbackReason) {
        LoggingEventBuilder event = fallbackReason == null ? log.atInfo() : log.atWarn();
        event = event.addKeyValue("request_id", requestId);
        if (callId != null) {
            event = event.addKeyValue("call_id", callId);
        }
        event = event.addKeyValue("model", model)
                .addKeyValue("latency_ms", latencyMs)
                .addKeyValue("result_status", fallbackReason == null ? "OK" : "FALLBACK");
        if (usage != null) {
            Map<String, Integer> tokens = new LinkedHashMap<>();
            tokens.put("prompt", usage.promptTokens());
            tokens.put("completion", usage.completionTokens());
            event = event.addKeyValue("token_usage", tokens);
        }
        if (fallbackReason != null) {
            event = event.addKeyValue("fallback_reason", fallbackReason);
        }
        event.log(MESSAGE);
    }

    /**
     * Một lần thử hỏng sắp được thử lại: vẫn đúng tập trường cho phép, {@code result_status=RETRY}, lý do ở
     * {@code fallback_reason}. Lần thử cuối (thành công hay hỏng hẳn) ghi bằng {@link #write} như trước, nên mỗi lời
     * gọi có một dòng kết quả và thêm một dòng cho mỗi lần thử lại.
     */
    static void retrying(Logger log, String requestId, String callId, String model, long attemptLatencyMs,
                         String reason) {
        LoggingEventBuilder event = log.atWarn().addKeyValue("request_id", requestId);
        if (callId != null) {
            event = event.addKeyValue("call_id", callId);
        }
        event.addKeyValue("model", model)
                .addKeyValue("latency_ms", attemptLatencyMs)
                .addKeyValue("result_status", RETRY)
                .addKeyValue("fallback_reason", reason)
                .log(MESSAGE);
    }
}
