package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Kết quả MỘT lần chạy một câu hỏi của một case (MVP mục 6.5: mỗi câu chạy lặp nhiều lần).
 *
 * Chỉ giữ kết luận và mã — không giữ nội dung report hay chữ AI viết: file kết quả gửi lại cho mentor
 * phải auditable mà không mang theo dữ liệu cuộc gọi.
 *
 * @param questionIndex       thứ tự câu hỏi trong case, từ 1
 * @param question            câu hỏi ĐÃ qua Sanitizer (bản Request Parser chuyển tiếp); null khi lỗi trước bước đó
 * @param repeat              lần chạy thứ mấy, từ 1
 * @param error               mã lỗi khi pipeline ném exception (Pipeline Success Rate); null khi chạy xong
 * @param type                REPORT / OUT_OF_SCOPE / INVALID_REQUEST; null khi lỗi
 * @param intentSource        AI hay KEYWORD (đường lui) — Intent Accuracy tách theo nguồn
 * @param verdict             verdict cuối của report; null khi không phải REPORT
 * @param issueCategory       category cuối; null khi không có vấn đề
 * @param ruleVerdict         kết luận rule — cột "Rule Verdict" của bảng AI vs Rule
 * @param aiVerdict           verdict AI trả về TRƯỚC Guardrails, nguyên chuỗi (có thể ngoài taxonomy); null khi AI không trả lời
 * @param analysisSource      AI hoặc RULE: report cuối dùng kết luận của ai
 * @param violations          vi phạm Guardrails dạng "G05: số liệu không có trong input: 8.6", theo thứ tự
 *                            Guardrails báo. Chỉ có mã và con số / ID AI nêu — đủ để biết AI sai gì mà không
 *                            mang nội dung cuộc gọi
 * @param templateCompliant   bản render đúng bố cục mẫu 4.5; null khi không phải REPORT
 * @param metricsMatchCalculator bảng chỉ số của report cuối trùng bảng dựng thẳng từ Metrics Calculator; null khi không phải REPORT
 * @param sensitiveValues     số giá trị nhạy cảm GỐC trong đầu vào của case (file + signaling thô), xem {@link SensitiveValues}
 * @param aiInputLeaks        giá trị gốc còn nguyên văn trong chuỗi gửi AI, đếm theo loại; null khi lỗi
 * @param outputLeaks         giá trị gốc còn nguyên văn trong phản hồi trả người dùng, đếm theo loại; null khi lỗi
 * @param intentTokens        token của lời gọi AI phân loại câu hỏi; null khi không gọi hoặc AI không trả lời
 * @param analysisTokens      token của lời gọi AI phân tích; null khi không gọi hoặc AI không trả lời
 * @param latencyMs           end-to-end, từ lúc nhận câu hỏi tới khi có response
 */
public record EvaluationRun(
        String caseId,
        int questionIndex,
        String question,
        int repeat,
        Intent expectedIntent,
        String error,
        String type,
        Intent intent,
        String intentSource,
        Verdict verdict,
        Boolean qualityFlag,
        IssueCategory issueCategory,
        String confidenceLevel,
        Verdict ruleVerdict,
        IssueCategory ruleIssueCategory,
        String aiVerdict,
        String aiIssueCategory,
        String analysisSource,
        boolean degraded,
        String fallbackReason,
        boolean needsReview,
        List<String> violations,
        Boolean templateCompliant,
        Boolean metricsMatchCalculator,
        int sensitiveValues,
        Map<String, Integer> aiInputLeaks,
        Map<String, Integer> outputLeaks,
        TokenUsage intentTokens,
        TokenUsage analysisTokens,
        long latencyMs
) {

    public static final String REPORT = "REPORT";
    public static final String OUT_OF_SCOPE = "OUT_OF_SCOPE";

    public EvaluationRun {
        violations = violations == null ? List.of() : List.copyOf(violations);
        aiInputLeaks = aiInputLeaks == null ? null : new TreeMap<>(aiInputLeaks);
        outputLeaks = outputLeaks == null ? null : new TreeMap<>(outputLeaks);
    }

    /** Mã vi phạm (G01-G05), mỗi mã một lần. */
    public List<String> violationCodes() {
        return violations.stream().map(v -> v.contains(":") ? v.substring(0, v.indexOf(':')) : v).distinct().toList();
    }

    /** AI nêu evidence không tồn tại (G01) hoặc con số không có trong input (G05) — "unsupported claim" của MVP mục 6.5. */
    public boolean hasUnsupportedClaim() {
        List<String> codes = violationCodes();
        return codes.contains("G01") || codes.contains("G05");
    }

    public boolean succeeded() {
        return error == null;
    }

    public boolean isReport() {
        return REPORT.equals(type);
    }

    /** Kết quả rút gọn để so giữa các lần chạy (Consistency): verdict + category, hoặc loại phản hồi. */
    public String outcomeKey() {
        if (!succeeded()) {
            return "ERROR";
        }
        return isReport() ? verdict + "/" + (issueCategory == null ? "-" : issueCategory) : type;
    }
}
