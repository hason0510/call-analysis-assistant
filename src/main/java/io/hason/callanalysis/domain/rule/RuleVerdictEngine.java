package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.confidence.ConfidenceAssessment;
import io.hason.callanalysis.domain.confidence.ConfidenceBasis;
import io.hason.callanalysis.domain.confidence.ConfidencePolicy;
import io.hason.callanalysis.domain.confidence.DataCoverage;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Mỗi nhánh dưới đây chỉ nói bằng chứng của kết luận mạnh tới đâu ({@link ConfidenceBasis}); mức tin cậy
     * và lý do do {@link ConfidencePolicy} tính, theo bảng MVP mục 7.2. Trước Sprint 3 T3 mỗi nhánh tự gán
     * HIGH / MEDIUM, cộng một bước hạ mức riêng khi thiếu log client.
     */
    public RuleVerdict decide(RuleSignals signals) {
        Draft d = decideBySignals(signals);
        ConfidenceAssessment confidence = ConfidencePolicy.forRule(d.basis(), coverage(signals));
        return new RuleVerdict(d.verdict(), d.qualityFlag(), d.issueCategory(), confidence.level(), d.reasoning(),
                d.limitations(), d.turnFailure(), d.causeBasis(), confidence.reasons(), d.signalingCause());
    }

    /** Kết luận trước khi có mức tin cậy. */
    private record Draft(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory, ConfidenceBasis basis,
                         String reasoning, List<String> limitations, TurnFailure turnFailure, String causeBasis,
                         SignalingCause signalingCause) {

        Draft(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory, ConfidenceBasis basis,
              String reasoning, List<String> limitations, TurnFailure turnFailure, String causeBasis) {
            this(verdict, qualityFlag, issueCategory, basis, reasoning, limitations, turnFailure, causeBasis, null);
        }
    }

    static DataCoverage coverage(RuleSignals signals) {
        List<LogSource> missing = Arrays.stream(LogSource.values())
                .filter(source -> !signals.availableSources().contains(source)).toList();
        return new DataCoverage(signals.signalingTruncated(), missing, signals.legsMissingClientLog());
    }

    private Draft decideBySignals(RuleSignals signals) {
        List<String> limitations = new ArrayList<>();

        if (!signals.hasSignaling()) {
            limitations.add("Không có dữ liệu signaling nên không dựng được timeline cuộc gọi");
            return unknown("Thiếu nguồn signaling", "không có signaling để dựng timeline", limitations);
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
                // Câu trả lời của chính server: không log client nào bổ sung hay phản bác được.
                return signalingFail(SignalingCause.INIT_CALL_REJECTED,
                        ConfidenceBasis.strong("server từ chối INIT_CALL với mã " + signals.initCallRejection())
                                .asExemptFromMissingLegs(),
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
                ConfidenceBasis basis = signals.candidateTimeout()
                        ? ConfidenceBasis.strong("không cấp phát được TURN và app hết thời gian chờ candidate")
                        : ConfidenceBasis.partial("không cấp phát được TURN nhưng không thấy app hết thời gian"
                                + " chờ candidate");
                return new Draft(Verdict.FAIL, false, IssueCategory.TURN_FAILURE, basis,
                        "Không cấp phát được relay trên TURN server nào: " + turnDetail(turn)
                                + ". Vì vậy không có candidate để gửi INVITE"
                                + (signals.candidateTimeout()
                                ? "; app hết thời gian chờ candidate" + clientFailureNote(signals) : ""),
                        limitations, turn, null);
            }
            if (signals.candidateTimeout()) {
                // Dòng timer là bằng chứng dương tính, nhưng kết luận còn gồm "không gửi được INVITE" —
                // một sự vắng mặt, nên vẫn nhạy với bản export bị cắt.
                return signalingFail(SignalingCause.CANDIDATE_TIMEOUT,
                        ConfidenceBasis.strong("app hết thời gian chờ candidate trước khi gửi INVITE").asAbsenceBased(),
                        "App hết thời gian chờ candidate" + clientFailureNote(signals)
                                + " trước khi gửi được INVITE;"
                                + " TURN vẫn cấp phát được nên nguyên nhân nằm ở bước tạo offer"
                                + " hoặc thu candidate phía client", limitations);
            }
            // Kết luận này dựa vào việc KHÔNG THẤY một event. Bản export thiếu event
            // thì chính cái không thấy đó có thể chỉ là do bị cắt mất.
            return signalingFail(SignalingCause.NO_INVITE,
                    ConfidenceBasis.strong("signaling không có INVITE nào").asAbsenceBased(),
                    "Không tồn tại INVITE: server chưa từng gọi tới callee", limitations);
        }

        // 2. Lỗi cứng ở tầng signaling
        if (signals.failHard()) {
            return signalingFail(SignalingCause.FAIL_HARD,
                    ConfidenceBasis.strong("signaling có FAIL_HARD trước khi thiết lập xong").asAbsenceBased(),
                    "Xuất hiện FAIL_HARD trước khi thiết lập xong", limitations);
        }

        // 3. Huỷ trước khi bắt tay xong
        if (signals.cancelled() && !signals.reachedConfirmed()) {
            limitations.add("CANCEL chưa phân biệt được là người dùng chủ động huỷ"
                    + " hay hệ thống timeout — xem knownAmbiguity của SIGNALING_FAILURE");
            return signalingFail(SignalingCause.CANCELLED,
                    ConfidenceBasis.partial("CANCEL chưa phân biệt được người dùng huỷ hay hệ thống hết giờ")
                            .asAbsenceBased(),
                    "Có CANCEL và không đạt OK_ACK_OK", limitations);
        }

        // 4. Bắt tay không hoàn tất vì lý do khác
        if (!signals.reachedConfirmed()) {
            return signalingFail(SignalingCause.NOT_CONFIRMED,
                    ConfidenceBasis.partial("không đạt OK_ACK_OK mà không thấy nguyên nhân cụ thể").asAbsenceBased(),
                    "Không đạt OK_ACK_OK nên cuộc gọi chưa được thiết lập", limitations);
        }

        // 5. Đã thiết lập nhưng media không chạy — ca 2D9057AA
        if (signals.mediaFailed()) {
            // KHÔNG nhạy với signalingTruncated: kết luận này đứng trên bằng chứng
            // DƯƠNG TÍNH từ end call log và WebRTC log (ICE failed, 0 byte audio),
            // không phải trên việc thiếu vắng một event signaling nào.
            return fail(IssueCategory.ICE_FAILURE, mediaFailureBasis(signals),
                    mediaFailureReason(signals), limitations);
        }

        // Từ đây trở xuống là kết luận SUCCESS, tức kết luận dựa trên việc KHÔNG THẤY lỗi media.
        // Không có log client nào thì không có gì để thấy: 2D9057AA có signaling hoàn hảo mà
        // vẫn FAIL vì media. MVP mục 4.1: thiếu file → UNKNOWN.
        if (!signals.hasClientLog()) {
            limitations.add("Không có log client nào (end call / WebRTC) nên không kiểm chứng được"
                    + " media; signaling đẹp chưa đủ để kết luận SUCCESS");
            return unknown("Signaling đạt OK_ACK_OK nhưng không có log client để kiểm chứng media",
                    "không có log client để kiểm chứng media", limitations);
        }

        // 6. Thiết lập được, media chạy, nhưng chất lượng kém
        if (signals.qualityDegraded()) {
            return new Draft(Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS,
                    ConfidenceBasis.weak("ngưỡng chất lượng kém chưa kiểm chứng trên data có nhãn"),
                    "Cuộc gọi thiết lập và kết thúc bình thường nhưng chỉ số chất lượng vượt ngưỡng",
                    withUnvalidatedThresholdNote(limitations), TurnFailure.NONE,
                    qualityBasis(signals.qualityByLeg()));
        }

        // 6b. Không mất gói đáng kể nhưng độ trễ / jitter trung bình cao (ngưỡng Teams CQD, xem LegQuality). Đứng SAU
        //     mất gói: MVP mục 10 scenario 2 xếp "packet loss ↑, RTT ↑" vào NETWORK_PACKET_LOSS. Data dev không cuộc nào
        //     chạm ngưỡng (RTT trung bình cao nhất 126 ms); FC3187B4 (ai20k_new_dataset) RTT trung bình ~540 ms cả cuộc.
        if (signals.delayDegraded()) {
            List<String> withNote = new ArrayList<>(limitations);
            withNote.add("Ngưỡng độ trễ / jitter lấy từ bộ phân loại \"Poor\" của Microsoft Teams CQD"
                    + " (RTT hoặc jitter TRUNG BÌNH, luồng trên 500 gói), CHƯA kiểm chứng trên data có nhãn;"
                    + " vượt ngưỡng không chắc người dùng nghe thấy vấn đề");
            return new Draft(Verdict.SUCCESS, true, IssueCategory.NETWORK_DELAY_JITTER,
                    ConfidenceBasis.weak("ngưỡng độ trễ / jitter chưa kiểm chứng trên data có nhãn"),
                    "Cuộc gọi thiết lập và kết thúc bình thường nhưng độ trễ / jitter trung bình vượt ngưỡng",
                    withNote, TurnFailure.NONE, delayBasis(signals.qualityByLeg()));
        }

        // 7. Bình thường. SUCCESS đòi "kết thúc bình thường" (MVP mục 4.1); không thấy BYE thì
        //    không biết cuộc gọi kết thúc ra sao — có thể rớt giữa chừng, có thể bản export thiếu.
        if (!signals.terminatedNormally()) {
            limitations.add("Không thấy BYE nên không xác nhận được cuộc gọi kết thúc bình thường"
                    + (signals.signalingTruncated() ? " (bản export bị cắt bớt, BYE có thể nằm ở phần mất)" : ""));
            return unknown("Đã đạt OK_ACK_OK nhưng không thấy BYE",
                    "không thấy BYE nên không biết cuộc gọi kết thúc ra sao", limitations);
        }

        return new Draft(Verdict.SUCCESS, false, null, successBasis(signals),
                "Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media", limitations,
                TurnFailure.NONE, null);
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
     * Cờ bật theo mất gói CẢ CUỘC ({@link LegQuality#degraded()}); câu nêu đúng con số đó và ngưỡng, in từ chính hằng số
     * để không lệch với điều kiện bật cờ. Phần "Đối chiếu" giữ các số đo theo mẫu — mẫu mất gói > 5 %, MOS thấp nhất,
     * hasMediaPoor — để người đọc tự cân, vì rule chỉ kiểm mất gói, không kiểm hậu quả với người nghe. Không có số liệu
     * theo leg (chỉ xảy ra khi gọi thẳng engine) thì trả null để report quay về định nghĩa.
     */
    private static String qualityBasis(List<LegQuality> legs) {
        List<String> signs = legs.stream().filter(LegQuality::degraded)
                .map(q -> q.leg().name().toLowerCase() + " mất gói cả cuộc "
                        + plain(q.wholeCallLoss().setScale(2, RoundingMode.HALF_UP)) + " %")
                .toList();
        if (signs.isEmpty()) {
            return null;
        }
        List<String> counter = new ArrayList<>();
        for (LegQuality q : legs) {
            if (q.lossOverThreshold() > 0) {
                counter.add(q.leg().name().toLowerCase() + " có " + q.lossOverThreshold() + "/" + q.samples()
                        + " mẫu stats mất gói > " + plain(SignalExtractor.LOSS_THRESHOLD_PERCENT) + " % (cao nhất "
                        + plain(q.maxLoss().setScale(2, RoundingMode.HALF_UP)) + " %)");
            }
        }
        legs.stream().filter(q -> q.minMos() != null)
                .min(Comparator.comparing(LegQuality::minMos))
                .ifPresent(q -> counter.add("MOS thấp nhất " + plain(q.minMos())
                        + " (" + q.leg().name().toLowerCase() + ")"));
        int mediaPoor = legs.stream().mapToInt(LegQuality::mediaPoor).sum();
        counter.add(mediaPoor == 0 ? "app không bật hasMediaPoor ở mẫu nào"
                : "app bật hasMediaPoor ở " + mediaPoor + " mẫu");
        return String.join("; ", signs) + " (ngưỡng: mất gói cả cuộc từ "
                + plain(LegQuality.WHOLE_CALL_LOSS_POOR_PERCENT) + " %). Đối chiếu: " + String.join(", ", counter);
    }

    /**
     * Căn cứ đo được của cờ độ trễ, in ở dòng "Chính": mỗi leg vượt ngưỡng ghi RTT / jitter trung bình, số mẫu và số
     * gói. Ngưỡng in từ CHÍNH hằng số của LegQuality để câu không lệch với điều kiện bật cờ.
     */
    private static String delayBasis(List<LegQuality> legs) {
        List<String> signs = new ArrayList<>();
        for (LegQuality q : legs) {
            if (!q.delayDegraded()) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            if (q.avgRttMs() != null) {
                parts.add("RTT trung bình " + plain(q.avgRttMs().setScale(0, RoundingMode.HALF_UP)) + " ms");
            }
            if (q.avgJitterMs() != null) {
                parts.add("jitter trung bình " + plain(q.avgJitterMs()) + " ms");
            }
            signs.add(q.leg().name().toLowerCase() + " có " + String.join(", ", parts)
                    + " (" + q.samples() + " mẫu, " + q.packetsReceived() + " gói nhận)");
        }
        return signs.isEmpty() ? null : String.join("; ", signs) + ". Ngưỡng (Teams CQD): RTT trung bình > "
                + plain(LegQuality.RTT_POOR_MS) + " ms hoặc jitter trung bình > " + plain(LegQuality.JITTER_POOR_MS)
                + " ms, luồng trên " + LegQuality.MIN_PACKETS + " gói";
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
        // ICE đã connected và đã có byte âm thanh, rồi mới mất: FE4EF5E7 nói ~30 s rồi rớt, 70C388AD chỉ nhận
        // ~0,7 s rồi im 12 s. Câu cũ "hasMediaFail bật cờ" đứng cạnh định nghĩa ICE_FAILURE "media không thiết
        // lập được" làm AI viết "không có đường truyền media nào được thiết lập" — ngược với evidence.
        // network_lost viết trung tính: ở 70C388AD app báo nó ~7 s sau khi hết dữ liệu trong khi máy bên kia
        // vẫn ping server — nó nói "không nhận được gì từ bên kia", không chứng minh bên kia mất mạng.
        if (signals.iceEverConnected()) {
            return "ICE đã đạt connected và đã nhận được một phần âm thanh, sau đó không còn nhận được dữ liệu"
                    + " (transport.hasMediaFail bật cờ"
                    + (signals.partnerLostNetwork() ? "; app phát tín hiệu network_lost về phía bên kia" : "") + ")";
        }
        return "transport.hasMediaFail bật cờ báo lỗi media";
    }

    /** ICE chuyển sang failed là dấu hiệu dương tính; chỉ 0 byte hay cờ hasMediaFail thì chưa trọn. */
    private static ConfidenceBasis mediaFailureBasis(RuleSignals signals) {
        if (signals.iceEverFailed()) {
            return ConfidenceBasis.strong("ICE chuyển sang failed"
                    + (signals.noMediaBytes() ? ", không có byte audio nào" : ""));
        }
        return ConfidenceBasis.partial((signals.noMediaBytes() ? "0 byte audio" : "cờ hasMediaFail")
                + " nhưng không thấy ICE chuyển sang failed");
    }

    /**
     * SUCCESS là kết luận dựa trên việc KHÔNG thấy dấu hiệu lỗi, nên nó yếu đi theo mọi thiếu hụt: thiếu
     * nguồn log, bản export bị cắt. Đủ cả ba nguồn, ICE từng connected, bản export đủ thì mới HIGH.
     */
    private static ConfidenceBasis successBasis(RuleSignals signals) {
        ConfidenceBasis basis = signals.iceEverConnected()
                ? ConfidenceBasis.strong("ICE đạt connected, kết thúc bằng BYE, không thấy lỗi media")
                : ConfidenceBasis.partial("không thấy ICE đạt connected");
        return basis.asAbsenceBased().asRequiringAllSources();
    }

    private static List<String> withUnvalidatedThresholdNote(List<String> limitations) {
        List<String> all = new ArrayList<>(limitations);
        all.add("Ngưỡng mất gói (cả cuộc từ " + plain(LegQuality.WHOLE_CALL_LOSS_POOR_PERCENT) + " %, theo mentor)"
                + " CHƯA kiểm chứng được: tập data có nhãn không có cuộc gọi nào bị suy giảm chất lượng");
        return all;
    }

    private static Draft fail(IssueCategory category, ConfidenceBasis basis, String reasoning,
                              List<String> limitations) {
        return new Draft(Verdict.FAIL, false, category, basis, reasoning, limitations, TurnFailure.NONE, null);
    }

    private static Draft signalingFail(SignalingCause cause, ConfidenceBasis basis, String reasoning,
                                       List<String> limitations) {
        return new Draft(Verdict.FAIL, false, IssueCategory.SIGNALING_FAILURE, basis, reasoning, limitations,
                TurnFailure.NONE, null, cause);
    }

    private static Draft unknown(String reasoning, String weakEvidence, List<String> limitations) {
        return new Draft(Verdict.UNKNOWN, false, IssueCategory.UNKNOWN, ConfidenceBasis.weak(weakEvidence),
                reasoning, limitations, TurnFailure.NONE, null);
    }
}
