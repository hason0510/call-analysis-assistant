package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Suy verdict từ tín hiệu thô.
 *
 * Thứ tự kiểm tra có chủ đích, và ca quan trọng nhất là ca thứ tự:
 * cuộc gọi 2D9057AA trong data mẫu có signaling trông BÌNH THƯỜNG (đạt OK_ACK_OK, kết
 * thúc bằng BYE, PAIR_PING phía callee đều tới sát lúc BYE) nhưng ground truth là FAIL
 * vì ICE thất bại và 0 byte audio.
 * Rule chỉ xét signaling sẽ kết luận SUCCESS và sai ngay trên tập dev.
 */
public class RuleVerdictEngine {

    public RuleVerdict decide(RuleSignals signals) {
        return capByMissingClientLegs(decideBySignals(signals), signals);
    }

    /**
     * Thiếu log client của một leg thì tối đa MEDIUM (MVP mục 7.2: "thiếu một phần file → MEDIUM").
     *
     * Bản Sprint 1 chỉ hạ khi giới hạn đụng tới căn cứ của chính kết luận, nên `311A9B6A` (chỉ có
     * log caller) vẫn báo HIGH; mentor yêu cầu hạ theo mức dữ liệu cho phép. Ngoại lệ duy nhất:
     * server từ chối INIT_CALL kèm mã nguyên văn — đó là câu trả lời của chính server, không có
     * log client nào bổ sung hay phản bác được nó.
     */
    private static RuleVerdict capByMissingClientLegs(RuleVerdict v, RuleSignals signals) {
        List<Leg> missing = signals.legsMissingClientLog();
        if (v.confidence() != ConfidenceLevel.HIGH || missing.isEmpty()
                || signals.initCallRejection() != null) {
            return v;
        }
        List<String> limitations = new ArrayList<>(v.dataLimitations());
        limitations.add("Độ tin cậy tối đa MEDIUM vì thiếu log client của "
                + String.join(", ", missing.stream().map(l -> l.name().toLowerCase()).toList()));
        return new RuleVerdict(v.verdict(), v.qualityFlag(), v.issueCategory(), ConfidenceLevel.MEDIUM,
                v.reasoning(), limitations, v.turnFailure(), v.causeBasis());
    }

    private RuleVerdict decideBySignals(RuleSignals signals) {
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

        // 1. Chưa bao giờ gọi tới callee. INVITE phải mang SDP offer + lô candidate đầu,
        //    nên thiếu INVITE có ba nguyên nhân khác hẳn nhau; xét nguyên nhân có bằng
        //    chứng DƯƠNG TÍNH trước, rồi mới rơi về kết luận dựa trên sự vắng mặt.
        if (!signals.sentInvite()) {
            if (signals.initCallRejection() != null) {
                // Taxonomy MVP mục 4.2 không có category riêng cho "bị chặn theo chính
                // sách"; SIGNALING_FAILURE là gần nhất vì cuộc gọi dừng ở bước signaling.
                limitations.add("Mã " + signals.initCallRejection() + " là server từ chối theo"
                        + " chính sách, không phải lỗi mạng; taxonomy chưa có category riêng"
                        + " nên xếp tạm vào SIGNALING_FAILURE");
                return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.HIGH,
                        "Server từ chối INIT_CALL (mã " + signals.initCallRejection()
                                + "): cuộc gọi bị chặn trước khi tới callee", limitations);
            }
            if (signals.turnAllocationFailed()) {
                TurnFailure turn = signals.turnFailure();
                if (turn.unvalidated()) {
                    limitations.add("Kiểu TURN hỏng này (" + turn.kind() + ") chưa có ca mẫu có nhãn"
                            + " nên hướng điều tra trong phần Đề xuất chưa được kiểm chứng");
                }
                // relay là đường mặc định (iceTransportPolicy NOHOST): không cấp phát được
                // TURN thì không có candidate nào để gói vào INVITE.
                return new RuleVerdict(Verdict.FAIL, false, IssueCategory.TURN_FAILURE,
                        signals.candidateTimeout() ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                        "Không cấp phát được relay trên TURN server nào: " + turnDetail(turn)
                                + ". Vì vậy không có candidate để gửi INVITE"
                                + (signals.candidateTimeout()
                                ? "; app hết thời gian chờ candidate" + clientFailureNote(signals) : ""),
                        limitations, turn);
            }
            if (signals.candidateTimeout()) {
                return fail(IssueCategory.SIGNALING_FAILURE, cappedBySignalingGaps(signals),
                        "App hết thời gian chờ candidate" + clientFailureNote(signals)
                                + " trước khi gửi được INVITE;"
                                + " TURN vẫn cấp phát được nên nguyên nhân nằm ở bước tạo offer"
                                + " hoặc thu candidate phía client", limitations);
            }
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

        // Từ đây trở xuống là kết luận SUCCESS, tức kết luận dựa trên việc KHÔNG THẤY lỗi media.
        // Không có log client nào thì không có gì để thấy: 2D9057AA có signaling hoàn hảo mà
        // vẫn FAIL vì media. MVP mục 4.1: thiếu file → UNKNOWN.
        if (!signals.hasClientLog()) {
            limitations.add("Không có log client nào (end call / WebRTC) nên không kiểm chứng được"
                    + " media; signaling đẹp chưa đủ để kết luận SUCCESS");
            return unknown("Signaling đạt OK_ACK_OK nhưng không có log client để kiểm chứng media",
                    limitations);
        }

        // 6. Thiết lập được, media chạy, nhưng chất lượng kém
        if (signals.qualityDegraded()) {
            return new RuleVerdict(Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS,
                    ConfidenceLevel.LOW,
                    "Cuộc gọi thiết lập và kết thúc bình thường nhưng chỉ số chất lượng vượt ngưỡng",
                    withUnvalidatedThresholdNote(limitations), TurnFailure.NONE,
                    qualityBasis(signals.qualityByLeg()));
        }

        // 7. Bình thường. SUCCESS đòi "kết thúc bình thường" (MVP mục 4.1); không thấy BYE thì
        //    không biết cuộc gọi kết thúc ra sao — có thể rớt giữa chừng, có thể bản export thiếu.
        if (!signals.terminatedNormally()) {
            limitations.add("Không thấy BYE nên không xác nhận được cuộc gọi kết thúc bình thường"
                    + (signals.signalingTruncated() ? " (bản export bị cắt bớt, BYE có thể nằm ở phần mất)" : ""));
            return unknown("Đã đạt OK_ACK_OK nhưng không thấy BYE", limitations);
        }

        return new RuleVerdict(Verdict.SUCCESS, false, null,
                confidenceForSuccess(signals),
                "Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media", limitations);
    }

    /**
     * Mã lỗi đọc NGUYÊN VĂN từ dòng `_emitFailed` của end call log, không tự điền theo
     * loại lỗi: không có dòng đó thì câu lý do không nêu mã nào.
     */
    private static String clientFailureNote(RuleSignals signals) {
        return signals.clientFailure() == null ? ""
                : " (_emitFailed: mã " + signals.clientFailure() + ")";
    }

    /**
     * Mệnh đề cho biết request TURN dừng ở đâu; mã lỗi đọc nguyên văn từ log.
     * Viết thành câu riêng sau dấu hai chấm, không nhét vào ngoặc giữa câu chính.
     */
    private static String turnDetail(TurnFailure turn) {
        return switch (turn.kind()) {
            case SOCKET_NOT_CREATED -> "không tạo được socket TURN nên chưa gửi được request nào";
            case SEND_FAILED_ON_DEVICE -> "request lỗi ngay khi gửi trên thiết bị với " + turn.sendError()
                    + " và không nhận được phản hồi nào";
            case NO_RESPONSE -> "đã gửi " + turn.requestsSent()
                    + " request nhưng không nhận được phản hồi nào";
            case NOT_ALLOCATED_AFTER_RESPONSE -> "TURN server có phản hồi " + turn.responses()
                    + " lần nhưng không lần nào cấp phát";
            case UNCLASSIFIED, NONE -> "0 lần allocate thành công";
        };
    }

    /**
     * Căn cứ đo được của cờ chất lượng, in ở dòng "Chính" thay cho định nghĩa chung của taxonomy.
     *
     * Định nghĩa NETWORK_PACKET_LOSS khẳng định "mất gói đủ làm giảm chất lượng thoại", nhưng
     * rule chỉ kiểm có mẫu vượt ngưỡng, không kiểm hậu quả. Vì vậy nêu cả bằng chứng ủng hộ lẫn
     * phản bác. Không có số liệu theo leg (chỉ xảy ra khi gọi thẳng engine) thì trả null để report
     * quay về định nghĩa.
     */
    private static String qualityBasis(List<LegQuality> legs) {
        List<String> signs = new ArrayList<>();
        for (LegQuality q : legs) {
            if (!q.degraded()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            if (q.lossOverThreshold() > 0) {
                parts.add(q.lossOverThreshold() + "/" + q.samples() + " mẫu stats mất gói > "
                        + plain(SignalExtractor.LOSS_THRESHOLD_PERCENT) + " % (cao nhất "
                        + plain(q.maxLoss().setScale(2, RoundingMode.HALF_UP)) + " %)");
            }
            if (q.mosBelowThreshold() > 0) {
                parts.add(q.mosBelowThreshold() + "/" + q.samples() + " mẫu MOS < "
                        + plain(SignalExtractor.MOS_THRESHOLD));
            }
            if (q.mediaPoor() > 0) {
                parts.add(q.mediaPoor() + " mẫu app bật hasMediaPoor");
            }
            signs.add(q.leg().name().toLowerCase() + " có " + String.join(", ", parts));
        }
        if (signs.isEmpty()) {
            return null;
        }
        List<String> counter = new ArrayList<>();
        legs.stream().filter(q -> q.minMos() != null)
                .min(Comparator.comparing(LegQuality::minMos))
                .ifPresent(q -> counter.add("MOS thấp nhất " + plain(q.minMos())
                        + " (" + q.leg().name().toLowerCase() + ")"));
        if (legs.stream().allMatch(q -> q.mediaPoor() == 0)) {
            counter.add("app không bật hasMediaPoor ở mẫu nào");
        }
        return String.join("; ", signs) + (counter.isEmpty() ? "" : ". Đối chiếu: " + String.join(", ", counter));
    }

    /** 5.0 → "5", 3.5 → "3.5": bỏ số 0 thừa, không bao giờ in dạng khoa học 5E+1. */
    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
        // Không có log client nào thì đã ra UNKNOWN từ trước, không tới được đây.
        return ConfidenceLevel.MEDIUM;
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
                + " tập data có nhãn không có cuộc gọi nào bị suy giảm chất lượng");
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
