package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.report.ReportBuilder;
import io.hason.callanalysis.domain.rule.RuleSignals;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.rule.SignalExtractor;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineNote;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import io.hason.callanalysis.service.port.SignalingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bảo vệ đoạn nối giữa nguồn signaling và tầng rule.
 *
 * Chỗ này từng đứt âm thầm: buildTimeline chỉ lấy events của ParseResult và bỏ
 * metadata của bản fetch, nên cờ truncated không bao giờ tới được SignalExtractor.
 * Cuộc gọi DE7DD314 (trả về 200/201 event) vì thế vẫn được báo là dữ liệu đầy đủ.
 */
class CallLogNormalizationServiceTest {

    private final SignalExtractor extractor = new SignalExtractor();

    /** Nguồn signaling giả — test không cần Elasticsearch. */
    private record StubSource(SignalingFetch fetch) implements SignalingSource {
        @Override
        public SignalingFetch fetchByCallId(String callId) {
            return fetch;
        }
    }

    private static RawSignalingRecord record(int ordinal, String ts, String cmd, String user) {
        return new RawSignalingRecord("CALL-1", ordinal, ts, "SVC1", "INFO", cmd,
                "csid1", "req-" + ordinal, user, "sess1", "MOBIFONE", "AS131429", "VN", null);
    }

    private static List<RawSignalingRecord> healthyCall() {
        return List.of(
                record(0, "2026-09-21T08:00:00.000000000Z", "INIT_CALL", "U-CALLER"),
                record(1, "2026-09-21T08:00:01.000000000Z", "INVITE", "U-CALLER"),
                record(2, "2026-09-21T08:00:02.000000000Z", "OK_ACK_OK", "U-CALLER"),
                record(3, "2026-09-21T08:00:30.000000000Z", "BYE", "U-CALLEE"));
    }

    /**
     * End call log tối thiểu nhưng ĐÚNG định dạng thật: FileTypeDetector nhận dạng
     * qua cột `#tag` ở dòng đặc tả, thiếu cột này thì file rơi vào UNKNOWN và
     * không được parse.
     */
    private static List<String> endCallLog(String callIdInFile) {
        return List.of(
                "#H1\t#ts\t#tag\tcallId\trole\tplatform",
                "1\t1789700842905\tinfo\t" + callIdInFile + "\tcaller\tios");
    }

    /** Dòng TURN theo format iOS thật; IP thay bằng dải tài liệu 203.0.113.0/24. */
    private static String turnLine(String time, String message) {
        return "[" + time + "][5507] (turn_port.cc:1108): TurnPort(Port[57498000:0:1:0:relay:"
                + "Net[en0:192.168.0.x/24:Wifi:id=1]]-Remote[203.0.113.10:3478/udp]: " + message;
    }

    private RuleSignals signalsFrom(Map<String, List<String>> files) {
        return extractor.extract(new CallLogNormalizationService(
                new StubSource(new SignalingFetch("CALL-1", healthyCall(), false, 4, 4)))
                .buildTimeline("CALL-1", files));
    }

    @Test
    @DisplayName("TURN chỉ có lỗi, không một lần allocate thành công (7B56D7AD) -> turnAllocationFailed")
    void turnWithoutAnySuccessIsFlagged() {
        RuleSignals s = signalsFrom(Map.of("caller_webrtc.log", List.of(
                turnLine("039:588", "Trying to connect to TURN server via udp @ 203.0.113.x:3478"),
                turnLine("039:588", "Failed to send TURN message, error: 65 id=WCzPDo17Noyy, type=TURN ALLOCATE request"),
                turnLine("039:839", "TURN probe request 57437a50446 timeout"))));

        assertThat(s.turnAllocationFailed()).isTrue();
    }

    @Test
    @DisplayName("401 rồi allocate thành công là bắt tay chuẩn -> KHÔNG gắn cờ TURN hỏng")
    void turnErrorThenSuccessIsNotFlagged() {
        RuleSignals s = signalsFrom(Map.of("caller_webrtc.log", List.of(
                turnLine("000:070", "TURN allocate request sent, id=5a6778756b6a"),
                turnLine("000:102", "Received TURN allocate error response, id=5a6778756b6a, code=401, rtt=32"),
                turnLine("000:137", "TURN allocate requested successfully, id=57354a677079, code=0, rtt=65"))));

        assertThat(s.turnAllocationFailed()).isFalse();
    }

    @Test
    @DisplayName("ACK của INIT_CALL mang callErrorCode (1B009D42) và hẹn giờ candidate lỗi được rút ra")
    void initCallRejectionAndCandidateTimeoutAreExtracted() {
        RuleSignals s = signalsFrom(Map.of("caller_endcall.log", List.of(
                "#H2\t#ts\t#tag\tmsg\tstatus\ttype",
                "#H3\t#ts\t#tag\tackCmd\tcmd\tcseq\tfromTag\tpayload\tseq\ttoTag\tts\ttsRecv",
                "3\t1789980269044\trecv_cmd\tINIT_CALL\tACK\t1\tCALL-1-F\t"
                        + "{\"callResp\":{\"callError\":\"Người này hiện chưa thể nhận cuộc gọi\","
                        + "\"callErrorCode\":428,\"callErrorMsg\":{\"defVal\":\"x\","
                        + "\"key\":\"call.outgoing.error.privacy_restricted\"}}}"
                        + "\t5\t\t1789980268990000000\t0",
                "2\t1789980275000\tlog_detail\t_waitingCandidateTimer with error\tTERMINATED\twebrtc")));

        assertThat(s.initCallRejection()).isEqualTo("428 call.outgoing.error.privacy_restricted");
        assertThat(s.candidateTimeout()).isTrue();
    }

    @Test
    @DisplayName("ACK INIT_CALL thành công (callError \"0\", không có callErrorCode) -> không coi là bị từ chối")
    void successfulInitCallAckIsNotRejection() {
        RuleSignals s = signalsFrom(Map.of("caller_endcall.log", List.of(
                "#H3\t#ts\t#tag\tackCmd\tcmd\tcseq\tfromTag\tpayload\tseq\ttoTag\tts\ttsRecv",
                "3\t1789700841553\trecv_cmd\tINIT_CALL\tACK\t1\tCALL-1-F\t"
                        + "{\"callResp\":{\"callError\":\"0\",\"callId\":\"CALL-1\"},\"ok\":true}"
                        + "\t5\t\t1789700841489080369\t0")));

        assertThat(s.initCallRejection()).isNull();
        assertThat(s.candidateTimeout()).isFalse();
    }

    private CallTimeline timelineFrom(SignalingFetch fetch) {
        return new CallLogNormalizationService(new StubSource(fetch))
                .buildTimeline("CALL-1", Map.of());
    }

    @Test
    @DisplayName("bản fetch bị cắt bớt sinh ghi chú SIGNALING_TRUNCATED kèm con số cụ thể")
    void truncatedFetchBecomesTypedNote() {
        CallTimeline timeline = timelineFrom(
                new SignalingFetch("CALL-1", healthyCall(), true, 200, 201));

        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.SIGNALING_TRUNCATED)
                .singleElement()
                .satisfies(n -> assertThat(n.message())
                        .contains("200/201").contains("thiếu 1"));
    }

    @Test
    @DisplayName("ghi chú cắt bớt đi tới được tầng rule, không bị rơi giữa đường")
    void truncationReachesRuleSignals() {
        RuleSignals truncated = extractor.extract(timelineFrom(
                new SignalingFetch("CALL-1", healthyCall(), true, 200, 201)));

        assertThat(truncated.signalingTruncated()).isTrue();
    }

    @Test
    @DisplayName("bản fetch đầy đủ thì KHÔNG bị gắn nhầm cờ cắt bớt")
    void completeFetchIsNotFlagged() {
        CallTimeline timeline = timelineFrom(
                new SignalingFetch("CALL-1", healthyCall(), false, 4, 4));

        assertThat(timeline.notes())
                .noneMatch(n -> n.kind() == TimelineNote.Kind.SIGNALING_TRUNCATED);
        assertThat(extractor.extract(timeline).signalingTruncated()).isFalse();
    }

    @Test
    @DisplayName("ca F03: file đính kèm mang Call-ID khác phải hiện ra ở giới hạn dữ liệu")
    void attachedFileWithForeignCallIdIsReported() {
        // MVP mục 6.4 ca F03. Parser vẫn sinh ParseWarning đúng, nhưng trước đây
        // cảnh báo chết tại chỗ: report không hề nhắc tới, người đọc tưởng dữ liệu sạch.
        CallTimeline timeline = new CallLogNormalizationService(
                new StubSource(new SignalingFetch("CALL-1", healthyCall(), false, 4, 4)))
                .buildTimeline("CALL-1", Map.of("caller_endcall.log", endCallLog("CALL-KHAC")));

        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION)
                .singleElement()
                .satisfies(n -> assertThat(n.message())
                        .contains("caller_endcall.log")
                        .contains("CALL-KHAC"));
    }

    @Test
    @DisplayName("file đính kèm đọc sạch thì không sinh ghi chú giới hạn nào")
    void cleanAttachedFileProducesNoLimitation() {
        CallTimeline timeline = new CallLogNormalizationService(
                new StubSource(new SignalingFetch("CALL-1", healthyCall(), false, 4, 4)))
                .buildTimeline("CALL-1", Map.of("caller_endcall.log", endCallLog("CALL-1")));

        assertThat(timeline.notes())
                .noneMatch(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION);
    }

    /** Ráp report từ timeline, dùng verdict trung tính để chỉ soi mục Giới hạn dữ liệu. */
    private List<String> limitationsOf(Map<String, List<String>> attachedFiles) {
        CallTimeline timeline = new CallLogNormalizationService(
                new StubSource(new SignalingFetch("CALL-1", healthyCall(), false, 4, 4)))
                .buildTimeline("CALL-1", attachedFiles);

        RuleVerdict neutral = new RuleVerdict(Verdict.SUCCESS, false, null,
                ConfidenceLevel.HIGH, "không xét ở test này", List.of());

        return new ReportBuilder()
                .build(timeline, new MetricsCalculator().calculate(timeline),
                        List.of(), neutral, new TaxonomyLoader().load())
                .dataLimitations();
    }

    @Test
    @DisplayName("thiếu log của một bên phải được nêu tên trong Giới hạn dữ liệu")
    void missingLogOfOneLegIsReported() {
        // Mẫu report MVP mục 4.5 có sẵn ví dụ "- Thiếu callee_webrtc.log."
        // Không có mục này thì cuộc gọi đính kèm 1/4 file trông đầy đủ như đính kèm cả 4.
        List<String> limitations = limitationsOf(
                Map.of("caller_endcall.log", endCallLog("CALL-1")));

        assertThat(limitations).anyMatch(s -> s.contains("Thiếu end call log của callee"));
        assertThat(limitations).anyMatch(s -> s.contains("Thiếu WebRTC log của cả hai bên"));
    }

    @Test
    @DisplayName("đính kèm đủ log của một bên thì không báo thiếu bên đó")
    void presentLogIsNotReportedAsMissing() {
        List<String> limitations = limitationsOf(
                Map.of("caller_endcall.log", endCallLog("CALL-1")));

        assertThat(limitations).noneMatch(s -> s.contains("end call log của caller"));
    }

    @Test
    @DisplayName("Elasticsearch lỗi -> vẫn dựng timeline từ log đính kèm, nêu lý do ở giới hạn dữ liệu")
    void signalingSourceFailureDoesNotCrash() {
        SignalingSource down = callId -> {
            throw new java.io.UncheckedIOException("Connection refused",
                    new java.net.ConnectException("Connection refused"));
        };

        CallTimeline timeline = new CallLogNormalizationService(down)
                .buildTimeline("CALL-1", Map.of("caller_endcall.log", endCallLog("CALL-1")));

        assertThat(timeline.mainTrack()).isNotEmpty();
        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION)
                .anyMatch(n -> n.message().contains("Không truy vấn được signaling"));
    }

    @Test
    @DisplayName("ca F02/F04: file không nhận diện được phải được nêu tên, không bị bỏ im lặng")
    void unrecognizedFileIsReported() {
        CallTimeline timeline = new CallLogNormalizationService(
                new StubSource(new SignalingFetch("CALL-1", healthyCall(), false, 4, 4)))
                .buildTimeline("CALL-1", Map.of(
                        "caller_webrtc.log", List.of("day khong phai log", "@@@"),
                        "callee_endcall.log", List.of()));

        assertThat(timeline.notes())
                .filteredOn(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION)
                .extracting(TimelineNote::message)
                .anyMatch(m -> m.contains("caller_webrtc.log") && m.contains("không nhận diện được"))
                .anyMatch(m -> m.contains("callee_endcall.log") && m.contains("file rỗng"));
    }

    @Test
    @DisplayName("timeline rỗng là THIẾU DỮ LIỆU, không phải bản export bị cắt bớt")
    void emptyTimelineIsNotTruncation() {
        CallTimeline timeline = timelineFrom(
                new SignalingFetch("CALL-1", List.of(), false, 0, 0));

        assertThat(timeline.notes())
                .anyMatch(n -> n.kind() == TimelineNote.Kind.DATA_LIMITATION);
        assertThat(extractor.extract(timeline).signalingTruncated()).isFalse();
    }
}
