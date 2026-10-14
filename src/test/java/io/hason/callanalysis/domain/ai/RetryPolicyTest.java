package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Thử lại có kiểm soát (MVP 7.1 T8): chỉ lỗi tạm thời, giới hạn số lần, trong ngân sách thời gian. */
class RetryPolicyTest {

    private static final Duration PER_ATTEMPT = Duration.ofSeconds(30);
    private final RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(500), Duration.ofSeconds(45),
            Duration.ofSeconds(2));

    @ParameterizedTest(name = "{0}")
    @EnumSource(Reason.class)
    @DisplayName("chỉ TIMEOUT và PROVIDER_ERROR (mạng, 5xx) được thử lại")
    void onlyTransientErrorsAreRetried(Reason reason) {
        boolean transientError = reason == Reason.TIMEOUT || reason == Reason.PROVIDER_ERROR;

        assertThat(RetryPolicy.retryable(reason)).isEqualTo(transientError);
        assertThat(policy.nextAttemptTimeout(reason, 1, Duration.ofSeconds(1), PER_ATTEMPT)).isEqualTo(
                transientError ? java.util.Optional.of(PER_ATTEMPT) : java.util.Optional.empty());
    }

    @Test
    @DisplayName("tối đa maxAttempts lần thử")
    void attemptLimit() {
        assertThat(policy.nextAttemptTimeout(Reason.PROVIDER_ERROR, 2, Duration.ofSeconds(1), PER_ATTEMPT)).isEmpty();
    }

    @Test
    @DisplayName("lần sau chỉ dùng phần ngân sách còn lại: hết giờ ở 30 s, ngân sách 45 s -> lần sau 14,5 s")
    void retryUsesRemainingBudget() {
        assertThat(policy.nextAttemptTimeout(Reason.TIMEOUT, 1, Duration.ofSeconds(30), PER_ATTEMPT))
                .contains(Duration.ofMillis(14_500));
    }

    @Test
    @DisplayName("ngân sách còn ít hơn mức tối thiểu -> không thử nữa")
    void noRetryWhenBudgetNearlySpent() {
        assertThat(policy.nextAttemptTimeout(Reason.TIMEOUT, 1, Duration.ofMillis(43_000), PER_ATTEMPT)).isEmpty();
    }

    @Test
    @DisplayName("none() không bao giờ thử lại")
    void noneNeverRetries() {
        assertThat(RetryPolicy.none().nextAttemptTimeout(Reason.TIMEOUT, 1, Duration.ZERO, PER_ATTEMPT)).isEmpty();
    }
}
