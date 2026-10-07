package io.hason.callanalysis.domain.request;

import io.hason.callanalysis.domain.ai.TokenUsage;

import java.util.List;

/**
 * Kết quả Request Parser (MVP mục 6.1 T4): intent, trọng tâm, Call-ID ghi trong câu hỏi.
 *
 * @param question       câu hỏi ĐÃ làm sạch — chuyển tiếp cho bước phân tích, không giữ bản thô
 * @param focus          khác null khi và chỉ khi intent là ANALYZE_WITH_FOCUS
 * @param callId         Call-ID người dùng ghi trong câu hỏi; null khi không ghi hoặc ghi nhiều Call-ID khác nhau
 * @param fallbackReason vì sao không dùng kết quả AI (mã ngắn, ghi log được); null khi dùng AI hoặc không cần AI
 * @param notes          điều cần nêu ở "Giới hạn dữ liệu" (ví dụ câu hỏi nêu hai Call-ID)
 * @param usage          token của lời gọi phân loại; null khi không gọi AI hoặc AI không trả lời
 */
public record ParsedRequest(String question, Intent intent, String focus, String callId, Source source,
                            String fallbackReason, List<String> notes, TokenUsage usage) {

    public enum Source {
        /** Phân loại bằng AI, đã qua kiểm {@link IntentClassification#fromAi}. */
        AI,
        /** Bộ từ khoá: AI lỗi / bị loại, hoặc không cần AI (câu hỏi trống). */
        KEYWORD
    }

    /**
     * Câu từ chối theo mẫu CỐ ĐỊNH (MVP mục 3.3, 4.4): không do AI viết, nên không bao giờ lỡ trả lời
     * luôn câu hỏi ngoài phạm vi, và giống hệt nhau mọi lần.
     */
    public static final String OUT_OF_SCOPE_REPLY =
            "Xin lỗi, mình chỉ hỗ trợ phân tích cuộc gọi từ log bạn đính kèm (kết luận, evidence, chỉ số,"
                    + " đề xuất và giới hạn dữ liệu). Câu hỏi này nằm ngoài phạm vi đó. Bạn có thể hỏi ví dụ:"
                    + " \"Phân tích cuộc gọi này giúp mình\" hoặc \"Vì sao bên nhận không nghe được?\".";

    public ParsedRequest {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Không có lời gọi AI nào trả lời (đường lui từ khoá, câu hỏi trống). */
    public ParsedRequest(String question, Intent intent, String focus, String callId, Source source,
                         String fallbackReason, List<String> notes) {
        this(question, intent, focus, callId, source, fallbackReason, notes, null);
    }

    public boolean isOutOfScope() {
        return intent == Intent.OUT_OF_SCOPE;
    }
}
