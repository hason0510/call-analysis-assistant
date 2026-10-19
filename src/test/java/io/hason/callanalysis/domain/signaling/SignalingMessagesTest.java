package io.hason.callanalysis.domain.signaling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalingMessagesTest {

    @Test
    @DisplayName("message có 'No sessions found' -> true; message khác -> false; không có message -> null")
    void noSessionsFound() {
        assertThat(SignalingMessages.noSessionsFound(
                "[OUT][NATs][INVITE][CALLEE] No sessions found for Call-ID: CALL-1, User-ID CABCDEF")).isTrue();
        assertThat(SignalingMessages.noSessionsFound("[IN][NATs][Partition 10][INVITE][CALLER] Current dialog state"))
                .isFalse();
        assertThat(SignalingMessages.noSessionsFound(null)).isNull();
    }
}
