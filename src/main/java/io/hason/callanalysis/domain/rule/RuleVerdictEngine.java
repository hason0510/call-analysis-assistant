package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Suy verdict từ tín hiệu thô.
 *
 * Thứ tự kiểm tra có chủ đích, và ca quan trọng nhất là ca thứ tự:
 * cuộc gọi 2D9057AA trong data mẫu có signaling HOÀN HẢO (OK_ACK_OK, BYE, PAIR_PING
 * đều đặn 33 giây) nhưng ground truth là FAIL vì ICE thất bại và 0 byte audio.
 * Rule chỉ xét signaling sẽ kết luận SUCCESS và sai ngay trên tập dev.
 */
public class RuleVerdictEngine {

    public RuleVerdict decide(RuleSignals signals) {
        List<String> limitations = new ArrayList<>();

        if (!signals.hasSignaling()) {
            limitations.add("Không có dữ liệu signaling nên không dựng được timeline cuộc gọi");
            return unknown("Thiếu nguồn signaling", limitations);
        }
        if (signals.signalingTruncated()) {
            limitations.add("Bản export signaling bị cắt bớt, có thể thiếu sự kiện");
        }
        if (!signals.availableSources().contains(LogSource.ENDCALL)) {
            limitations.add("Không có end call log nên không kiểm chứng được chất lượng media");
        }

        // 1. Chưa bao giờ gọi tới callee
        if (!signals.sentInvite()) {
            // Kết luận này dựa vào việc KHÔNG THẤY một event. Bản export thiếu event
            // thì chính cái không thấy đó có thể chỉ là do bị cắt mất.
            return fail(IssueCategory.SIGNALING_FAILURE, cappedBySignalingGaps(signals),
                    "Không tồn tại INVITE: server chưa từng gọi tới callee", limitations);
        }

        // 2. Lỗi cứng ở tầng signaling
        if (signals.failHard()) {
            return fail(IssueCategory.SIGNALING_FAILURE, cappedBySignalingGaps(signals),
                    "Xuất hiện FAIL_HARD trước khi thiết lập xong", limitations);
        }

        // 3. Huỷ trước khi bắt tay xong
        if (signals.cancelled() && !signals.reachedConfirmed()) {
            limitations.add("CANCEL chưa phân biệt được là người dùng chủ động huỷ"
                    + " hay hệ thống timeout — xem knownAmbiguity của SIGNALING_FAILURE");
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.MEDIUM,
                    "Có CANCEL và không đạt OK_ACK_OK", limitations);
        }

        // 4. Bắt tay không hoàn tất vì lý do khác
        if (!signals.reachedConfirmed()) {
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.MEDIUM,
                    "Không đạt OK_ACK_OK nên cuộc gọi chưa được thiết lập", limitations);
        }

        // 5. Đã thiết lập nhưng media không chạy — ca 2D9057AA
        if (signals.mediaFailed()) {
            // KHÔNG hạ theo signalingTruncated: kết luận này đứng trên bằng chứng
            // DƯƠNG TÍNH từ end call log và WebRTC log (ICE failed, 0 byte audio),
            // không phải trên việc thiếu vắng một event signaling nào.
            return fail(IssueCategory.ICE_FAILURE,
                    signals.iceEverFailed() ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                    mediaFailureReason(signals), limitations);
        }

        // 6. Thiết lập được, media chạy, nhưng chất lượng kém
        if (signals.qualityDegraded()) {
            return new RuleVerdict(Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS,
                    ConfidenceLevel.LOW,
                    "Cuộc gọi thiết lập và kết thúc bình thường nhưng chỉ số chất lượng vượt ngưỡng",
                    withUnvalidatedThresholdNote(limitations));
        }

        // 7. Bình thường
        if (!signals.terminatedNormally()) {
            limitations.add("Không thấy BYE nên chưa xác nhận được cuộc gọi kết thúc bình thường");
            return new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.MEDIUM,
                    "Đã đạt OK_ACK_OK và không có dấu hiệu lỗi media", limitations);
        }

        return new RuleVerdict(Verdict.SUCCESS, false, null,
                confidenceForSuccess(signals),
                "Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media", limitations);
    }

    private static String mediaFailureReason(RuleSignals signals) {
        if (signals.iceEverFailed() && !signals.iceEverConnected()) {
            return "ICE chuyển sang failed và không bao giờ đạt connected";
        }
        if (signals.noMediaBytes()) {
            return "Không một byte audio nào được truyền dù đã thiết lập cuộc gọi";
        }
        return "transport.hasMediaFail bật cờ báo lỗi media";
    }

    /**
     * Độ tin cậy suy bằng logic tất định (MVP mục 7.2), không để AI sinh số.
     * Đủ cả ba nguồn log, ICE từng connected, và bản export không bị cắt thì mới HIGH.
     */
    private static ConfidenceLevel confidenceForSuccess(RuleSignals signals) {
        // Kết luận "bình thường" là kết luận dựa trên việc KHÔNG thấy dấu hiệu lỗi,
        // nên nó yếu đi theo mọi thiếu hụt: thiếu nguồn log, hay bản export bị cắt.
        if (signals.availableSources().size() == 3
                && signals.iceEverConnected()
                && !signals.signalingTruncated()) {
            return ConfidenceLevel.HIGH;
        }
        return signals.hasClientLog() ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    /**
     * Hạ trần độ tin cậy cho kết luận rút ra TỪ SIGNALING khi bản export bị cắt bớt.
     *
     * Chỉ hạ theo giới hạn nào ĐỤNG TỚI căn cứ của chính kết luận đó, không hạ theo
     * mọi giới hạn: kết luận SIGNALING_FAILURE chỉ cần signaling, nên thiếu end call
     * log không làm nó kém chắc chắn đi. Hạ tuốt thì gần như mọi cuộc gọi đều tụt
     * xuống MEDIUM và con số tin cậy hết còn phân biệt được gì.
     */
    private static ConfidenceLevel cappedBySignalingGaps(RuleSignals signals) {
        return signals.signalingTruncated() ? ConfidenceLevel.MEDIUM : ConfidenceLevel.HIGH;
    }

    private static List<String> withUnvalidatedThresholdNote(List<String> limitations) {
        List<String> all = new ArrayList<>(limitations);
        all.add("Ngưỡng phát hiện chất lượng kém CHƯA kiểm chứng được:"
                + " tập data mẫu không có cuộc gọi nào bị suy giảm chất lượng");
        return all;
    }

    private static RuleVerdict fail(IssueCategory category, ConfidenceLevel confidence,
                                    String reasoning, List<String> limitations) {
        return new RuleVerdict(Verdict.FAIL, false, category, confidence, reasoning, limitations);
    }

    private static RuleVerdict unknown(String reasoning, List<String> limitations) {
        return new RuleVerdict(Verdict.UNKNOWN, false, IssueCategory.UNKNOWN,
                ConfidenceLevel.LOW, reasoning, limitations);
    }
}
