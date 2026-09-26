package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.rule.TurnFailure;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phần Đề xuất của report cho TURN_FAILURE.
 *
 * Trước đây mọi ca TURN_FAILURE nhận cùng hai câu "kiểm tra credential" và "cổng UDP 3478
 * không bị chặn" — chỉ đúng khi request đã rời máy. Với 703100CF (0 request) và 7B56D7AD
 * (lỗi gửi ngay trên máy) cả hai đều sai hướng.
 */
class ReportBuilderTest {

    private final CallTimeline timeline =
            new TimelineBuilder().build("CALL-1", List.of(), LegAssignment.unknown());

    private List<String> suggestionsFor(TurnFailure turn) {
        RuleVerdict verdict = new RuleVerdict(Verdict.FAIL, false, IssueCategory.TURN_FAILURE,
                ConfidenceLevel.HIGH, "không xét ở test này", List.of(), turn);
        return new ReportBuilder()
                .build(timeline, new MetricsCalculator().calculate(timeline), List.of(), verdict,
                        new IssueTaxonomy(List.of()))
                .suggestions();
    }

    @Test
    @DisplayName("không tạo được socket (703100CF) -> KHÔNG đề xuất credential hay cổng 3478")
    void socketFailureDoesNotPointAtServerOrFirewall() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.SOCKET_NOT_CREATED, 0, 0, null, "3478/udp", null));

        assertThat(s).noneMatch(line -> line.contains("credential") || line.contains("3478"));
        assertThat(s).anyMatch(line -> line.contains("Chưa có request nào tới được TURN server"));
    }

    @Test
    @DisplayName("lỗi gửi ngay trên thiết bị (7B56D7AD) -> nêu mã nguyên văn, hướng về mạng của thiết bị")
    void sendFailureQuotesErrorVerbatim() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.SEND_FAILED_ON_DEVICE, 20, 0, "error: 65", "3478/udp", null));

        assertThat(s.getFirst()).contains("error: 65").contains("kết nối mạng của thiết bị");
        assertThat(s).noneMatch(line -> line.contains("credential"));
    }

    @Test
    @DisplayName("gửi đi không có phản hồi (E9D6C112) -> nêu cả hai khả năng, cổng đọc từ log")
    void noResponseNamesBothPossibilities() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.NO_RESPONSE, 40, 0, null, "3478/udp", null));

        assertThat(s.getFirst()).contains("Đã gửi 40 request").contains("cổng 3478/udp");
        assertThat(s).anyMatch(line -> line.contains("bị chặn") && line.contains("TURN server có hoạt động"));
    }

    @Test
    @DisplayName("cổng không đọc được từ log thì không tự điền 3478")
    void transportIsNeverHardCoded() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.NO_RESPONSE, 5, 0, null, null, null));

        assertThat(s).noneMatch(line -> line.contains("3478"));
        assertThat(s).anyMatch(line -> line.contains("cổng TURN"));
    }

    @Test
    @DisplayName("có VPN -> nêu như DỮ KIỆN đi kèm, không viết thành nguyên nhân")
    void vpnIsReportedAsFactNotCause() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.SOCKET_NOT_CREATED, 0, 0, null, "3478/udp", "tun0"));

        assertThat(s).filteredOn(line -> line.contains("VPN")).singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("tun0")
                        .contains("chưa đủ để kết luận VPN là nguyên nhân"));
    }

    @Test
    @DisplayName("không có VPN thì không nhắc tới VPN")
    void noVpnMeansNoVpnSuggestion() {
        List<String> s = suggestionsFor(new TurnFailure(
                TurnFailure.Kind.NO_RESPONSE, 40, 0, null, "3478/udp", null));

        assertThat(s).noneMatch(line -> line.contains("VPN"));
    }

    @Test
    @DisplayName("số 0 của leg chưa có media được giải thích MỘT lần ở Giới hạn dữ liệu, mỗi leg một dòng")
    void zeroFilledLegIsExplainedOnceInLimitations() {
        // Callee 2D9057AA: app ghi 0 cho mọi chỉ số vì chưa đo được gì
        io.hason.callanalysis.domain.event.CanonicalEvent summary =
                new io.hason.callanalysis.domain.event.CanonicalEvent("callee_endcall.log#185", "CALL-1",
                        io.hason.callanalysis.domain.event.Leg.CALLEE,
                        io.hason.callanalysis.domain.event.LogSource.ENDCALL,
                        io.hason.callanalysis.domain.event.EventTime.absolute(java.time.Instant.parse(
                                "2026-09-21T08:01:00Z"), io.hason.callanalysis.domain.event.ClockDomain.CLIENT_CALLEE),
                        io.hason.callanalysis.domain.event.EventType.CALL_SUMMARY, "CALL_SUMMARY",
                        java.util.Map.of("audio.audioMos", "0", "audio.packetsReceived", "0",
                                "transport.localStunResponse", "0"),
                        io.hason.callanalysis.domain.event.Severity.INFO,
                        new io.hason.callanalysis.domain.event.SourceRef("callee_endcall.log", 185, "raw"));
        CallTimeline withSummary = new TimelineBuilder().build("CALL-1", List.of(summary), LegAssignment.unknown());

        List<String> limitations = new ReportBuilder()
                .build(withSummary, new MetricsCalculator().calculate(withSummary), List.of(),
                        new RuleVerdict(Verdict.FAIL, false, IssueCategory.ICE_FAILURE, ConfidenceLevel.HIGH,
                                "không xét ở test này", List.of()),
                        new IssueTaxonomy(List.of()))
                .dataLimitations();

        assertThat(limitations).filteredOn(l -> l.contains("điền vào chỗ trống")).singleElement()
                .isEqualTo("Leg callee chưa nhận được gói audio nào (audio.packetsReceived = 0)"
                        + " và chưa có phản hồi STUN nào (transport.localStunResponse = 0):"
                        + " MOS, packet loss, jitter, RTT trong log đều là 0 điền vào chỗ trống,"
                        + " không phải kết quả đo");
    }

    @Test
    @DisplayName("MOS/RTT/jitter đo được -> nêu MỘT lần là giá trị lúc kết thúc; nhãn trong bảng giữ như mẫu MVP")
    void endOfCallSnapshotIsDisclosedOnce() {
        io.hason.callanalysis.domain.event.CanonicalEvent summary =
                new io.hason.callanalysis.domain.event.CanonicalEvent("callee_endcall.log#181", "CALL-1",
                        io.hason.callanalysis.domain.event.Leg.CALLEE,
                        io.hason.callanalysis.domain.event.LogSource.ENDCALL,
                        io.hason.callanalysis.domain.event.EventTime.absolute(java.time.Instant.parse(
                                "2026-09-21T08:01:00Z"), io.hason.callanalysis.domain.event.ClockDomain.CLIENT_CALLEE),
                        io.hason.callanalysis.domain.event.EventType.CALL_SUMMARY, "CALL_SUMMARY",
                        java.util.Map.of("audio.audioMos", "4.37331", "audio.packetsReceived", "4024",
                                "audio.packetsLost", "21", "transport.localStunResponse", "30",
                                "transport.currentRttMs", "18"),
                        io.hason.callanalysis.domain.event.Severity.INFO,
                        new io.hason.callanalysis.domain.event.SourceRef("callee_endcall.log", 181, "raw"));
        CallTimeline withSummary = new TimelineBuilder().build("CALL-1", List.of(summary), LegAssignment.unknown());

        CallReport report = new ReportBuilder()
                .build(withSummary, new MetricsCalculator().calculate(withSummary), List.of(),
                        new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.HIGH,
                                "không xét ở test này", List.of()),
                        new IssueTaxonomy(List.of()));

        assertThat(report.dataLimitations()).filteredOn(l -> l.contains("lúc kết thúc")).singleElement()
                .isEqualTo("MOS, RTT, jitter là giá trị lúc kết thúc cuộc gọi (mẫu stats cuối), không phải của cả cuộc");
        assertThat(report.metrics()).anyMatch(m -> m.name().equals("MOS (callee)"));
    }

    @Test
    @DisplayName("không có MOS/RTT/jitter nào đo được thì KHÔNG thêm câu 'lúc kết thúc'")
    void noSnapshotNoteWithoutMeasuredValues() {
        CallReport report = new ReportBuilder()
                .build(timeline, new MetricsCalculator().calculate(timeline), List.of(),
                        new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.HIGH,
                                "không xét ở test này", List.of()),
                        new IssueTaxonomy(List.of()));

        assertThat(report.dataLimitations()).noneMatch(l -> l.contains("lúc kết thúc"));
    }

    @Test
    @DisplayName("dòng 'Chính' in căn cứ đo được của cuộc gọi khi có, thay cho định nghĩa chung của category")
    void primaryCauseUsesMeasuredBasis() {
        RuleVerdict verdict = new RuleVerdict(Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS,
                ConfidenceLevel.LOW, "không xét ở test này", List.of(), TurnFailure.NONE,
                "caller có 18/80 mẫu stats mất gói > 5 % (cao nhất 11.32 %)");

        CallReport report = new ReportBuilder()
                .build(timeline, new MetricsCalculator().calculate(timeline), List.of(), verdict,
                        new IssueTaxonomy(List.of()));

        assertThat(report.possibleCauses().primary())
                .isEqualTo("NETWORK_PACKET_LOSS — caller có 18/80 mẫu stats mất gói > 5 % (cao nhất 11.32 %)");
    }
}
