package io.hason.callanalysis.domain.request;

import java.util.Arrays;
import java.util.Optional;

/**
 * Intent + focus đã có kiểu và đã nhất quán với nhau.
 *
 * @param focus khác null khi và chỉ khi intent là ANALYZE_WITH_FOCUS
 */
public record IntentClassification(Intent intent, String focus) {

    /** Trọng tâm dài hơn thế này không còn là "khía cạnh được hỏi" mà là chép lại cả đoạn. */
    public static final int MAX_FOCUS_LENGTH = 200;

    public IntentClassification {
        if (intent == null) {
            throw new IllegalArgumentException("intent null");
        }
        focus = focus == null || focus.isBlank() ? null : focus.strip();
        if (intent == Intent.ANALYZE_WITH_FOCUS && focus == null) {
            throw new IllegalArgumentException("ANALYZE_WITH_FOCUS phải có focus");
        }
        if (intent != Intent.ANALYZE_WITH_FOCUS) {
            focus = null;
        }
    }

    /**
     * Kiểm đầu ra AI (giống Guardrails G02 / G03 của bước phân tích): intent phải đúng tên trong
     * danh sách, ANALYZE_WITH_FOCUS phải nêu trọng tâm, trọng tâm không dài bất thường. Không đạt
     * thì rỗng — bên gọi lùi về bộ phân loại theo từ khoá.
     *
     * Intent khác ANALYZE_WITH_FOCUS mà vẫn kèm focus thì bỏ focus đi, không coi là lỗi: thứ quyết
     * định hướng xử lý là intent.
     */
    public static Optional<IntentClassification> fromAi(AiIntent ai) {
        if (ai == null || ai.intent() == null) {
            return Optional.empty();
        }
        Optional<Intent> intent = Arrays.stream(Intent.values()).filter(i -> i.name().equals(ai.intent())).findFirst();
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        String focus = ai.focus() == null ? null : ai.focus().strip();
        if (intent.get() == Intent.ANALYZE_WITH_FOCUS
                && (focus == null || focus.isEmpty() || focus.length() > MAX_FOCUS_LENGTH)) {
            return Optional.empty();
        }
        return Optional.of(new IntentClassification(intent.get(), focus));
    }
}
