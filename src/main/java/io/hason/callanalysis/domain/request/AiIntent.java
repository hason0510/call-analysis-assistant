package io.hason.callanalysis.domain.request;

import io.hason.callanalysis.domain.ai.TokenUsage;

/**
 * Đầu ra AI của bước phân loại câu hỏi, giữ NGUYÊN DẠNG như model trả về — cùng lý do với
 * {@link io.hason.callanalysis.domain.ai.AiAnalysis}: ép kiểu lúc đọc JSON thì intent ngoài danh
 * sách thành lỗi parse chung chung. Chỉ {@link IntentClassification#fromAi} mới gán kiểu.
 *
 * @param focus chuỗi rỗng khi câu hỏi không có trọng tâm (strict schema không nhận trường thiếu)
 * @param usage số token của lời gọi, adapter gắn vào sau; null khi không biết
 */
public record AiIntent(String intent, String focus, TokenUsage usage) {

    /** Đầu ra của model, chưa biết số token. */
    public AiIntent(String intent, String focus) {
        this(intent, focus, null);
    }

    public AiIntent withUsage(TokenUsage tokens) {
        return new AiIntent(intent, focus, tokens);
    }
}
