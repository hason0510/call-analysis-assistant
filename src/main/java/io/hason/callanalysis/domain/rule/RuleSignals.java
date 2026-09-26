package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;

import java.util.List;
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
        /**
         * Kiểu TURN hỏng của file WebRTC đầu tiên (theo tên) đã thử TURN nhưng KHÔNG một
         * lần allocate nào thành công; {@link TurnFailure#NONE} nếu không có file nào như vậy.
         */
        TurnFailure turnFailure,
        /** App hết giờ chờ ICE candidate (`_waitingCandidateTimer with error`). */
        boolean candidateTimeout,
        /** Server từ chối INIT_CALL, dạng "428 call.outgoing.error.privacy_restricted"; null nếu không. */
        String initCallRejection,
        /**
         * Mã và lý do thất bại app tự ghi ở dòng `_emitFailed` đầu tiên, đọc NGUYÊN VĂN,
         * dạng "421 call.outgoing.error.network_check"; null nếu không có dòng này.
         */
        String clientFailure,
        Set<LogSource> availableSources,
        /** Căn cứ đo được của cờ chất lượng, mỗi leg có bản ghi chỉ số một phần tử. */
        List<LegQuality> qualityByLeg
) {

    public RuleSignals {
        availableSources = availableSources == null ? Set.of() : Set.copyOf(availableSources);
        qualityByLeg = qualityByLeg == null ? List.of() : List.copyOf(qualityByLeg);
        turnFailure = turnFailure == null ? TurnFailure.NONE : turnFailure;
    }

    /** Có file WebRTC đã thử TURN nhưng KHÔNG một lần allocate nào thành công. */
    public boolean turnAllocationFailed() {
        return turnFailure.failed();
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
