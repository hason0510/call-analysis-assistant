package io.hason.callanalysis.domain.request;

import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Câu hỏi của người dùng ĐÃ qua Sanitizer — kiểu duy nhất cổng phân loại câu hỏi nhận.
 *
 * Constructor private, chỉ tạo được qua {@link #of}: không đường nào gửi câu hỏi thô cho AI. Câu
 * hỏi là văn bản người dùng tự gõ, nên có thể chứa đúng thứ scenario 6 (MVP mục 10) cài vào: JWT,
 * số điện thoại, IP.
 */
public final class SafeQuestion {

    private final String text;
    private final Map<String, Integer> redactions;

    private SafeQuestion(String text, Map<String, Integer> redactions) {
        this.text = text;
        this.redactions = Collections.unmodifiableMap(new TreeMap<>(redactions));
    }

    public static SafeQuestion of(String question, SensitiveDataSanitizer sanitizer) {
        SensitiveDataSanitizer.Result result = sanitizer.sanitize(question == null ? "" : question.strip());
        return new SafeQuestion(result.text(), result.findings());
    }

    public String text() {
        return text;
    }

    /** Số giá trị đã che theo từng mục inventory — chỉ đếm, ghi log được. */
    public Map<String, Integer> redactions() {
        return redactions;
    }

    public boolean isBlank() {
        return text.isBlank();
    }
}
