package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;

import java.time.Duration;
import java.util.Optional;

/**
 * Thử lại có kiểm soát cho lời gọi AI (MVP 7.1 T8 "Controlled retry, phân loại lỗi").
 *
 * Chỉ thử lại lỗi TẠM THỜI: {@code TIMEOUT} và {@code PROVIDER_ERROR} (mất mạng, provider lỗi 5xx). Không thử lại:
 * 429 (giới hạn tốc độ / hết hạn mức — thử ngay vẫn bị từ chối), khoá sai, yêu cầu bị từ chối, câu trả lời sai định
 * dạng, bị Guardrails loại (temperature 0: hỏi lại gần như ra cùng câu).
 *
 * "Có kiểm soát" theo ba giới hạn: số lần thử ({@code maxAttempts}), thời gian chờ giữa hai lần ({@code backoff}), và
 * NGÂN SÁCH thời gian cho cả lời gọi ({@code budget}). Lần thử sau chỉ được dùng phần ngân sách còn lại: lần đầu hết
 * giờ ở 30 s với ngân sách 45 s thì lần sau chỉ còn ~14,5 s. Nhờ vậy thử lại không đẩy request quá thời gian trình
 * duyệt chờ. Phần còn lại ít hơn {@code minAttemptTimeout} thì không thử nữa.
 *
 * Thuần domain: chỉ quyết định, không ngủ, không gọi mạng — adapter thực hiện.
 */
public record RetryPolicy(int maxAttempts, Duration backoff, Duration budget, Duration minAttemptTimeout) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts phải ≥ 1");
        }
        backoff = backoff == null ? Duration.ZERO : backoff;
        budget = budget == null ? Duration.ZERO : budget;
        minAttemptTimeout = minAttemptTimeout == null ? Duration.ZERO : minAttemptTimeout;
    }

    /** Không thử lại — như trước Sprint 3 T8. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    /** Lỗi tạm thời: thử lại có cơ hội thành công. */
    public static boolean retryable(Reason reason) {
        return reason == Reason.TIMEOUT || reason == Reason.PROVIDER_ERROR;
    }

    /**
     * @param attemptsSoFar số lần đã thử (lần đầu = 1)
     * @param elapsed       thời gian đã trôi qua từ lần thử đầu
     * @param perAttempt    thời gian chờ tối đa của một lần thử theo cấu hình
     * @return thời gian chờ cho lần thử tiếp theo; rỗng khi không thử nữa
     */
    public Optional<Duration> nextAttemptTimeout(Reason reason, int attemptsSoFar, Duration elapsed,
                                                 Duration perAttempt) {
        if (!retryable(reason) || attemptsSoFar >= maxAttempts) {
            return Optional.empty();
        }
        Duration remaining = budget.minus(elapsed).minus(backoff);
        if (remaining.compareTo(minAttemptTimeout) < 0) {
            return Optional.empty();
        }
        return Optional.of(remaining.compareTo(perAttempt) < 0 ? remaining : perAttempt);
    }
}
