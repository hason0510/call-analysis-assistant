package io.hason.callanalysis.domain.observability;

import java.time.Instant;

/**
 * Bản ghi một request của Chat API, ghi vào Elasticsearch để xem trên Kibana (MVP 7.1 T9: "Ghi log và kết quả phân tích
 * vào ES local; Kibana view cho request, latency, fallback, lỗi").
 *
 * CHỈ mã, số và cờ — không có trường chữ tự do nào (câu hỏi, tóm tắt, mô tả evidence, thông điệp lỗi). MVP 6.2 đặt
 * "dữ liệu ghi vào ES local" dưới Output Sanitizer; không chở chữ tự do thì không có gì để lộ. {@code callId} là mã giả
 * ({@code CALL_xxxxxx}), {@code error} là tên lớp exception.
 *
 * @param resultStatus REPORT, OUT_OF_SCOPE, INVALID_REQUEST, ERROR
 * @param callId       Call-ID đã mã giả; null khi chưa chạy phân tích
 * @param verdict      null khi không ra report
 * @param fallbackReason mã lý do lùi về rule; null khi dùng được AI hoặc không ra report
 * @param intentSource AI hoặc KEYWORD (đường lui từ khoá)
 * @param latencyMs    toàn request; các trường {@code *Ms} còn lại là từng bước, null khi bước đó không chạy
 * @param error        tên lớp exception khi result_status là ERROR
 */
public record RequestRecord(
        Instant timestamp,
        String requestId,
        String resultStatus,
        String callId,
        String intent,
        String intentSource,
        boolean garbledQuestion,
        int filesAttached,
        String verdict,
        String issueCategory,
        Boolean qualityFlag,
        String confidence,
        String analysisSource,
        Boolean degraded,
        String fallbackReason,
        Boolean needsReview,
        long latencyMs,
        Long intentMs,
        Long analysisMs,
        Long aiMs,
        Long reportMs,
        Integer promptTokens,
        Integer completionTokens,
        String error
) {
}
