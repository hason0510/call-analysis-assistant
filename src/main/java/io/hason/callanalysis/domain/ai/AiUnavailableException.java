package io.hason.callanalysis.domain.ai;

/**
 * AI không trả được kết quả dùng được: timeout, provider lỗi, thiếu key, trả sai format.
 * Bên gọi bắt lỗi này để fallback về rule verdict (MVP mục 6.1 T8), không để pipeline hỏng.
 *
 * `reason` là mã ngắn, đi thẳng vào log `fallback_reason` (MVP mục 6.2) — vì vậy KHÔNG chứa
 * nội dung request/response của AI.
 */
public class AiUnavailableException extends RuntimeException {

    /**
     * RATE_LIMITED / QUOTA_EXCEEDED: HTTP 429 — tách khỏi PROVIDER_ERROR vì người dùng cần làm việc khác nhau.
     * AUTH_FAILED (401/403), INPUT_TOO_LARGE (400 vượt cửa sổ ngữ cảnh), REQUEST_REJECTED (4xx khác): tách ở Sprint 3
     * T8 — thử lại vô ích. PROVIDER_ERROR chỉ còn lỗi mạng và 5xx, là lỗi tạm thời ({@link RetryPolicy}).
     */
    public enum Reason {
        NOT_CONFIGURED, TIMEOUT, PROVIDER_ERROR, RATE_LIMITED, QUOTA_EXCEEDED, AUTH_FAILED, INPUT_TOO_LARGE,
        REQUEST_REJECTED, INVALID_RESPONSE, REFUSED
    }

    private final Reason reason;

    public AiUnavailableException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AiUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
