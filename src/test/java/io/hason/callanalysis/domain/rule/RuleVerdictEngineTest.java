package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVerdictEngineTest {

    private final RuleVerdictEngine engine = new RuleVerdictEngine();

    private static final Set<LogSource> ALL_SOURCES =
            Set.of(LogSource.SIGNALING, LogSource.ENDCALL, LogSource.WEBRTC);

    /** Builder gọn cho từng kịch bản; mặc định là cuộc gọi khoẻ mạnh. */
    private static RuleSignals signals(java.util.function.Consumer<Builder> tweak) {
        Builder b = new Builder();
        tweak.accept(b);
        return b.build();
    }

    private static final class Builder {
        boolean sentInvite = true;
        boolean reachedConfirmed = true;
        boolean terminatedNormally = true;
        boolean cancelled = false;
        boolean failHard = false;
        boolean iceEverConnected = true;
        boolean iceEverFailed = false;
        boolean mediaFailFlag = false;
        boolean noMediaBytes = false;
        boolean qualityDegraded = false;
        boolean signalingTruncated = false;
        TurnFailure turnFailure = TurnFailure.NONE;
        boolean candidateTimeout = false;
        String initCallRejection = null;
        String clientFailure = null;
        Set<LogSource> sources = ALL_SOURCES;
        List<LegQuality> qualityByLeg = List.of();

        RuleSignals build() {
            return new RuleSignals(sentInvite, reachedConfirmed, terminatedNormally, cancelled,
                    failHard, iceEverConnected, iceEverFailed, mediaFailFlag, noMediaBytes,
                    qualityDegraded, signalingTruncated, turnFailure, candidateTimeout,
                    initCallRejection, clientFailure, sources, qualityByLeg);
        }
    }

    @Test
    @DisplayName("server từ chối INIT_CALL (1B009D42) -> FAIL, lý do nêu đúng mã, không đổ cho mạng")
    void initCallRejectionIsReportedAsPolicyNotNetwork() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.initCallRejection = "428 call.outgoing.error.privacy_restricted";
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.reasoning()).contains("428").contains("từ chối");
        assertThat(v.dataLimitations()).anyMatch(l -> l.contains("không phải lỗi mạng"));
    }

    private static TurnFailure turn(TurnFailure.Kind kind, int sent, int responses, String sendError) {
        return new TurnFailure(kind, sent, responses, sendError, "3478/udp", null);
    }

    @Test
    @DisplayName("TURN không cấp phát được + hết giờ chờ candidate (703100CF) -> TURN_FAILURE, HIGH")
    void turnFailureWithCandidateTimeoutIsTurnFailure() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.cancelled = true;
            b.turnFailure = turn(TurnFailure.Kind.SOCKET_NOT_CREATED, 0, 0, null);
            b.candidateTimeout = true;
            b.clientFailure = "421 call.outgoing.error.network_check";   // dòng _emitFailed thật
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.TURN_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.reasoning()).contains("421 call.outgoing.error.network_check")
                .contains("chưa gửi được request nào");
        // kiểu TURN đi theo verdict để report chọn đề xuất đúng hướng
        assertThat(v.turnFailure().kind()).isEqualTo(TurnFailure.Kind.SOCKET_NOT_CREATED);
    }

    @Test
    @DisplayName("TURN không cấp phát được nhưng thiếu end call log (7B56D7AD) -> TURN_FAILURE, MEDIUM")
    void turnFailureWithoutEndCallLogIsMedium() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.cancelled = true;
            b.turnFailure = turn(TurnFailure.Kind.SEND_FAILED_ON_DEVICE, 20, 0, "error: 65");
            b.sources = Set.of(LogSource.SIGNALING, LogSource.WEBRTC);
        }));

        assertThat(v.issueCategory()).isEqualTo(IssueCategory.TURN_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        // mã lỗi đọc nguyên văn, không diễn giải thành tên errno
        assertThat(v.reasoning()).contains("lỗi ngay khi gửi trên thiết bị với error: 65");
    }

    @Test
    @DisplayName("kiểu TURN hỏng chưa có ca mẫu có nhãn -> ghi rõ ở giới hạn dữ liệu là chưa kiểm chứng")
    void unvalidatedTurnKindIsDisclosed() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.turnFailure = turn(TurnFailure.Kind.NOT_ALLOCATED_AFTER_RESPONSE, 3, 3, null);
        }));

        assertThat(v.issueCategory()).isEqualTo(IssueCategory.TURN_FAILURE);
        assertThat(v.reasoning()).contains("có phản hồi 3 lần nhưng không lần nào cấp phát");
        assertThat(v.dataLimitations())
                .anyMatch(l -> l.contains("NOT_ALLOCATED_AFTER_RESPONSE") && l.contains("chưa có ca mẫu"));
    }

    @Test
    @DisplayName("ba kiểu đã có ca mẫu trong fail/ thì KHÔNG bị ghi là chưa kiểm chứng")
    void validatedTurnKindsAreNotDisclosedAsUnvalidated() {
        for (TurnFailure.Kind kind : List.of(TurnFailure.Kind.SOCKET_NOT_CREATED,
                TurnFailure.Kind.SEND_FAILED_ON_DEVICE, TurnFailure.Kind.NO_RESPONSE)) {
            RuleVerdict v = engine.decide(signals(b -> {
                b.sentInvite = false;
                b.reachedConfirmed = false;
                b.terminatedNormally = false;
                b.turnFailure = turn(kind, 1, 0, "error: 65");
            }));

            assertThat(v.dataLimitations()).as(kind.name()).noneMatch(l -> l.contains("chưa có ca mẫu"));
        }
    }

    @Test
    @DisplayName("hết giờ chờ candidate mà TURN vẫn tốt (311A9B6A) -> KHÔNG gán TURN_FAILURE")
    void candidateTimeoutWithHealthyTurnIsNotTurnFailure() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.cancelled = true;
            b.candidateTimeout = true;
            b.clientFailure = "421 call.outgoing.error.network_check";
        }));

        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
        assertThat(v.reasoning()).contains("421").contains("phía client");
    }

    @Test
    @DisplayName("không có dòng _emitFailed thì lý do KHÔNG tự điền mã lỗi theo loại lỗi")
    void errorCodeIsNeverFilledInWithoutEmitFailedLine() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.cancelled = true;
            b.turnFailure = turn(TurnFailure.Kind.NO_RESPONSE, 40, 0, null);
            b.candidateTimeout = true;
        }));

        assertThat(v.issueCategory()).isEqualTo(IssueCategory.TURN_FAILURE);
        assertThat(v.reasoning()).contains("hết thời gian chờ candidate").doesNotContain("mã");
    }

    @Test
    @DisplayName("cuộc gọi khoẻ mạnh đủ ba nguồn -> SUCCESS, độ tin cậy HIGH")
    void healthyCallIsSuccess() {
        RuleVerdict v = engine.decide(signals(b -> { }));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.qualityFlag()).isFalse();
        assertThat(v.issueCategory()).isNull();
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.turnFailure()).isEqualTo(TurnFailure.NONE);
    }

    @Test
    @DisplayName("chưa từng gửi INVITE -> FAIL + SIGNALING_FAILURE")
    void neverSentInviteIsSignalingFailure() {
        // Tái hiện 1B009D42 và D114749E: chỉ có INIT_CALL rồi im lặng.
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
            b.sources = Set.of(LogSource.SIGNALING);
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.reasoning()).contains("INVITE");
    }

    @Test
    @DisplayName("FAIL_HARD -> FAIL + SIGNALING_FAILURE")
    void failHardIsSignalingFailure() {
        // Tái hiện 0EC7B700: INVITE gửi 15 lần rồi FAIL_HARD.
        RuleVerdict v = engine.decide(signals(b -> {
            b.failHard = true;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
    }

    @Test
    @DisplayName("CANCEL trước khi bắt tay xong -> FAIL, độ tin cậy MEDIUM vì còn mơ hồ")
    void cancelBeforeConfirmedIsAmbiguousFailure() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.cancelled = true;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("CANCEL"));
    }

    @Test
    @DisplayName("SIGNALING HOÀN HẢO nhưng ICE thất bại -> vẫn phải FAIL")
    void perfectSignalingWithFailedIceIsStillFailure() {
        // Đây là ca 2D9057AA: OK_ACK_OK + BYE + PAIR_PING đều 33 giây, nhưng ICE failed
        // và 0 byte audio. Rule chỉ xét signaling sẽ kết luận SUCCESS và sai.
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.iceEverFailed = true;
            b.mediaFailFlag = true;
            b.noMediaBytes = true;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("không một byte audio nào dù đã thiết lập -> FAIL dù chưa thấy ICE failed")
    void zeroMediaBytesAloneIsEnoughToFail() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.noMediaBytes = true;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    @DisplayName("chất lượng kém -> SUCCESS kèm cờ, kèm ghi chú ngưỡng chưa kiểm chứng")
    void degradedQualityIsSuccessWithFlag() {
        RuleVerdict v = engine.decide(signals(b -> b.qualityDegraded = true));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.qualityFlag()).isTrue();
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.NETWORK_PACKET_LOSS);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("CHƯA kiểm chứng"));
    }

    @Test
    @DisplayName("không có signaling -> UNKNOWN, không đoán bừa")
    void missingSignalingIsUnknown() {
        RuleVerdict v = engine.decide(signals(b -> b.sources = Set.of(LogSource.WEBRTC)));

        assertThat(v.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.UNKNOWN);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("signaling"));
    }

    @Test
    @DisplayName("thiếu end call log -> SUCCESS nhưng hạ độ tin cậy và ghi giới hạn dữ liệu")
    void missingClientLogLowersConfidence() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sources = Set.of(LogSource.SIGNALING);
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("end call log"));
    }

    @Test
    @DisplayName("bản export signaling bị cắt bớt được ghi vào giới hạn dữ liệu")
    void truncatedExportIsRecorded() {
        // Cuộc gọi DE7DD314: truncated=true, trả về 200/201 event.
        RuleVerdict v = engine.decide(signals(b -> b.signalingTruncated = true));

        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("cắt bớt"));
    }

    @Test
    @DisplayName("bản export bị cắt bớt thì KHÔNG được báo độ tin cậy HIGH")
    void truncatedExportLowersConfidence() {
        // Cùng một cuộc gọi khoẻ mạnh, chỉ khác ở chỗ nguồn trả thiếu event.
        // Trước đây confidenceForSuccess bỏ qua limitations nên vẫn ra HIGH,
        // tức là tự nhận chắc chắn trên dữ liệu mà chính nó vừa ghi nhận là thiếu.
        assertThat(engine.decide(signals(b -> { })).confidence())
                .isEqualTo(ConfidenceLevel.HIGH);

        assertThat(engine.decide(signals(b -> b.signalingTruncated = true)).confidence())
                .isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    @DisplayName("kết luận rút từ signaling bị hạ tin cậy khi bản export thiếu event")
    void signalingVerdictIsCappedWhenExportTruncated() {
        // "Chưa từng gửi INVITE" là kết luận dựa trên việc KHÔNG THẤY một event.
        // Nếu bản export bị cắt thì đúng cái event quyết định đó có thể đã mất.
        assertThat(engine.decide(signals(b -> b.sentInvite = false)).confidence())
                .isEqualTo(ConfidenceLevel.HIGH);

        assertThat(engine.decide(signals(b -> {
            b.sentInvite = false;
            b.signalingTruncated = true;
        })).confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    @DisplayName("kết luận media KHÔNG bị hạ vì signaling thiếu event — căn cứ nằm ở nguồn khác")
    void mediaVerdictIsNotCappedBySignalingGaps() {
        // Ca 2D9057AA: bằng chứng là ICE failed + 0 byte audio, lấy từ end call log
        // và WebRTC log. Bản export signaling thiếu event không đụng tới căn cứ đó.
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.iceEverFailed = true;
            b.noMediaBytes = true;
            b.signalingTruncated = true;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("cắt bớt"));
    }

    @Test
    @DisplayName("thiếu end call log KHÔNG hạ tin cậy của kết luận SIGNALING_FAILURE")
    void missingEndCallLogDoesNotCapSignalingVerdict() {
        // Signaling một mình đã đủ chứng minh server chưa từng gọi tới callee.
        // Hạ tin cậy theo MỌI giới hạn sẽ làm gần như cuộc gọi nào cũng thành MEDIUM.
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.sources = Set.of(LogSource.SIGNALING);
        }));

        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("end call log"));
    }

    @Test
    @DisplayName("câu lý do TURN tách thành câu riêng, không nhét ngoặc dài giữa câu chính")
    void turnReasoningIsSplitIntoSentences() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.turnFailure = turn(TurnFailure.Kind.SOCKET_NOT_CREATED, 0, 0, null);
        }));

        assertThat(v.reasoning()).isEqualTo("Không cấp phát được relay trên TURN server nào:"
                + " không tạo được socket TURN nên chưa gửi được request nào."
                + " Vì vậy không có candidate để gửi INVITE");
    }

    @Test
    @DisplayName("cờ chất lượng nêu căn cứ ĐO ĐƯỢC của cuộc gọi, cả ủng hộ lẫn phản bác — không chép định nghĩa (271D1FAF)")
    void qualityFlagCarriesMeasuredBasis() {
        // Định nghĩa taxonomy khẳng định "mất gói đủ làm giảm chất lượng thoại" — rule không kiểm điều đó.
        RuleVerdict v = engine.decide(signals(b -> {
            b.qualityDegraded = true;
            b.qualityByLeg = List.of(
                    new LegQuality(io.hason.callanalysis.domain.event.Leg.CALLER, 80, 18,
                            new java.math.BigDecimal("11.3208"), 0, new java.math.BigDecimal("4.10477"), 0),
                    new LegQuality(io.hason.callanalysis.domain.event.Leg.CALLEE, 80, 3,
                            new java.math.BigDecimal("7.54717"), 0, new java.math.BigDecimal("4.34143"), 0));
        }));

        assertThat(v.causeBasis()).isEqualTo("caller có 18/80 mẫu stats mất gói > 5 % (cao nhất 11.32 %);"
                + " callee có 3/80 mẫu stats mất gói > 5 % (cao nhất 7.55 %)."
                + " Đối chiếu: MOS thấp nhất 4.10477 (caller), app không bật hasMediaPoor ở mẫu nào");
    }

    @Test
    @DisplayName("kết luận FAIL không đặt căn cứ riêng -> report dùng định nghĩa taxonomy như cũ")
    void failVerdictHasNoCauseBasis() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.iceEverFailed = true;
        }));

        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.causeBasis()).isNull();
    }
}
