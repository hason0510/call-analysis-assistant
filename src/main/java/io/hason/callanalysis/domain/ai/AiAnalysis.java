package io.hason.callanalysis.domain.ai;

import java.util.List;

/**
 * Structured output của AI (MVP mục 6.1 T5), giữ NGUYÊN DẠNG như model trả về.
 *
 * `verdict` và `issueCategory` cố ý là chuỗi chứ không phải enum: đầu ra AI chưa đáng tin,
 * và nếu ép kiểu ngay lúc đọc JSON thì giá trị ngoài taxonomy thành lỗi parse chung chung —
 * Guardrails mất cơ hội báo đúng ca G03. Chỉ sau khi qua {@link Guardrails} mới có kiểu.
 *
 * `confidenceLevel` của AI chỉ để tham khảo: độ tin cậy cuối cùng suy bằng logic tất định
 * (MVP mục 7.2), không lấy theo lời AI tự nhận.
 *
 * `usage` không phải đầu ra của model: adapter gắn vào sau, từ trường `usage` của phản hồi — null khi
 * không biết (AI giả trong test, hoặc đọc JSON không qua adapter).
 */
public record AiAnalysis(
        String verdict,
        boolean qualityFlag,
        String issueCategory,
        String confidenceLevel,
        String summary,
        List<String> evidenceIds,
        String analysis,
        List<String> suggestions,
        TokenUsage usage
) {

    /** Giá trị issueCategory khi không có vấn đề (SUCCESS không cờ). Strict schema không nhận null trong enum. */
    public static final String NO_ISSUE = "NONE";

    public AiAnalysis {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** Đầu ra của model, chưa biết số token. */
    public AiAnalysis(String verdict, boolean qualityFlag, String issueCategory, String confidenceLevel,
                      String summary, List<String> evidenceIds, String analysis, List<String> suggestions) {
        this(verdict, qualityFlag, issueCategory, confidenceLevel, summary, evidenceIds, analysis, suggestions, null);
    }

    public AiAnalysis withUsage(TokenUsage tokens) {
        return new AiAnalysis(verdict, qualityFlag, issueCategory, confidenceLevel, summary, evidenceIds, analysis,
                suggestions, tokens);
    }

    /** Mọi đoạn văn AI tự viết — nơi Guardrails tìm số liệu bịa (G05). */
    public List<String> freeText() {
        List<String> all = new java.util.ArrayList<>();
        if (summary != null) all.add(summary);
        if (analysis != null) all.add(analysis);
        all.addAll(suggestions);
        return all;
    }
}
