package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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
        /** Leg có ít nhất một log client (end call hoặc WebRTC) đính kèm. */
        Set<Leg> legsWithClientLog,
        /** Căn cứ đo được của cờ chất lượng, mỗi leg có bản ghi chỉ số một phần tử. */
        List<LegQuality> qualityByLeg,
        /**
         * App phát tín hiệu `network_lost` về phía BÊN KIA: bản ghi `signal` của end call log, `originator=remote`.
         * Có ở 3 cuộc (2D9057AA, 70C388AD, FE4EF5E7), 0 cuộc thuộc success/. App báo nó ~7 s sau khi hết dữ liệu
         * từ bên kia (70C388AD, FE4EF5E7); ở 70C388AD máy bên kia VẪN ping server — nên đọc là "không nhận được gì
         * từ bên kia", không phải bằng chứng bên kia mất mạng. Chỉ dùng để GIẢI THÍCH, không dùng để kết luận.
         */
        boolean partnerLostNetwork,
        /** Có file đính kèm bị loại vì thuộc cuộc gọi khác ({@code TimelineNote.Kind.FOREIGN_CALL_FILE}). */
        boolean clientFilesFromOtherCall
) {

    public RuleSignals {
        availableSources = availableSources == null ? Set.of() : Set.copyOf(availableSources);
        legsWithClientLog = legsWithClientLog == null ? Set.of() : Set.copyOf(legsWithClientLog);
        qualityByLeg = qualityByLeg == null ? List.of() : List.copyOf(qualityByLeg);
        turnFailure = turnFailure == null ? TurnFailure.NONE : turnFailure;
    }

    /** Có leg mà độ trễ / jitter TRUNG BÌNH vượt ngưỡng Teams CQD (xem {@link LegQuality#delayDegraded()}). */
    public boolean delayDegraded() {
        return qualityByLeg.stream().anyMatch(LegQuality::delayDegraded);
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

    /** Leg không có log client nào, theo thứ tự caller rồi callee. */
    public List<Leg> legsMissingClientLog() {
        return Stream.of(Leg.CALLER, Leg.CALLEE)
                .filter(l -> !legsWithClientLog.contains(l))
                .toList();
    }

    /** Có bằng chứng media thất bại từ bất kỳ nguồn nào. */
    public boolean mediaFailed() {
        return (iceEverFailed && !iceEverConnected) || mediaFailFlag || noMediaBytes;
    }
}
