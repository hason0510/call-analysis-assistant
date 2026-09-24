package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;

import java.util.Set;

/**
 * Các tín hiệu THÔ rút từ timeline, tách khỏi kết luận.
 *
 * Tách như vậy để Sprint 2 khi Guardrails đối chiếu verdict của AI với rule verdict
 * có thể chỉ ra LỆCH Ở TÍN HIỆU NÀO, thay vì chỉ báo "hai bên khác nhau".
 */
public record RuleSignals(
        boolean sentInvite,
        boolean reachedConfirmed,
        boolean terminatedNormally,
        boolean cancelled,
        boolean failHard,
        boolean iceEverConnected,
        boolean iceEverFailed,
        boolean mediaFailFlag,
        boolean noMediaBytes,
        boolean qualityDegraded,
        boolean signalingTruncated,
        /** Có file WebRTC đã thử TURN nhưng KHÔNG một lần allocate nào thành công. */
        boolean turnAllocationFailed,
        /** App hết giờ chờ ICE candidate (`_waitingCandidateTimer with error`, mã 421). */
        boolean candidateTimeout,
        /** Server từ chối INIT_CALL, dạng "428 call.outgoing.error.privacy_restricted"; null nếu không. */
        String initCallRejection,
        Set<LogSource> availableSources
) {

    public RuleSignals {
        availableSources = availableSources == null ? Set.of() : Set.copyOf(availableSources);
    }

    public boolean hasSignaling() {
        return availableSources.contains(LogSource.SIGNALING);
    }

    public boolean hasClientLog() {
        return availableSources.contains(LogSource.ENDCALL)
                || availableSources.contains(LogSource.WEBRTC);
    }

    /** Có bằng chứng media thất bại từ bất kỳ nguồn nào. */
    public boolean mediaFailed() {
        return (iceEverFailed && !iceEverConnected) || mediaFailFlag || noMediaBytes;
    }
}
