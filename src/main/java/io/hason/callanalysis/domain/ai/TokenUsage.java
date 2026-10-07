package io.hason.callanalysis.domain.ai;

/**
 * Số token của MỘT lời gọi AI, đúng như provider báo trong phản hồi (trường {@code usage}) — không ước lượng.
 *
 * Cần để so chi phí giữa các cách dựng context (MVP mục 6.1 T11: "accuracy, consistency, token, latency")
 * và để báo cáo chi phí của một lượt đánh giá. Chỉ có khi AI thực sự trả lời: lời gọi lỗi / quá giờ không có số.
 */
public record TokenUsage(int promptTokens, int completionTokens) {

    public int total() {
        return promptTokens + completionTokens;
    }
}
