package io.hason.callanalysis.domain.ai;

/**
 * AI không trả được kết quả dùng được: timeout, provider lỗi, thiếu key, trả sai format.
 * Bên gọi bắt lỗi này để fallback về rule verdict (MVP mục 6.1 T8), không để pipeline hỏng.
 *
 * `reason` là mã ngắn, đi thẳng vào log `fallback_reason` (MVP mục 6.2) — vì vậy KHÔNG chứa
 * nội dung request/response của AI.
 */
public class AiUnavailableException extends RuntimeException {

    public enum Reason { NOT_CONFIGURED, TIMEOUT, PROVIDER_ERROR, INVALID_RESPONSE, REFUSED }

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
