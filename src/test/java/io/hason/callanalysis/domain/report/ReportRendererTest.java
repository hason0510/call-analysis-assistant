package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Renderer dựng đúng bố cục mẫu MVP mục 4.5 — đủ mục, đúng thứ tự (Template Compliance, mục 6.5). */
class ReportRendererTest {

    private final ReportRenderer renderer = new ReportRenderer();

    private static CallReport report(boolean qualityFlag, String analysis, List<CallReport.EvidenceEntry> evidence,
                                     List<String> suggestions, List<String> limitations) {
        return new CallReport("CALL-EXAMPLE-001", Verdict.SUCCESS, qualityFlag,
                qualityFlag ? IssueCategory.NETWORK_PACKET_LOSS : null, ConfidenceLevel.MEDIUM,
                "Cuộc gọi thiết lập thành công.", evidence,
                List.of(new CallReport.MetricEntry("Thời gian thiết lập", "6200", "ms", null, "SIGNALING"),
                        new CallReport.MetricEntry("RTT (callee)", null, null, "không có trong file log", "ENDCALL"),
                        new CallReport.MetricEntry("Khoảng trống PAIR_PING lớn nhất (caller)", "1019", "ms [proxy]",
                                null, "SIGNALING")),
                new CallReport.PossibleCauses(qualityFlag ? "NETWORK_PACKET_LOSS — mất gói phía callee" : null,
                        qualityFlag ? List.of("NETWORK_DELAY_JITTER - chưa xác nhận được") : List.of()),
                suggestions, limitations, false, analysis,
                analysis == null ? CallReport.AnalysisSource.RULE : CallReport.AnalysisSource.AI, false, null,
                analysis == null ? List.of() : List.of("EV02", "EV10"));
    }

    private static final List<CallReport.EvidenceEntry> EVIDENCE = List.of(
            new CallReport.EvidenceEntry("EV02", "SIGNALING", "2026-09-21T10:00:06.320123456Z",
                    "OK_ACK: LegA CONFIRMED", "signaling#12"),
            new CallReport.EvidenceEntry("EV10", "WEBRTC", "+4592ms (tương đối)", "ICE chuyển checking => failed",
                    "callee_webrtc.log:902"));

    @Test
    @DisplayName("đầu report và sáu mục theo đúng thứ tự mẫu 4.5")
    void followsTemplateOrder() {
        String text = renderer.render(report(true, "Mất gói 9.8% phía callee.", EVIDENCE,
                List.of("Kiểm tra mạng phía callee."), List.of("Thiếu callee_webrtc.log.")));
        List<String> lines = text.lines().toList();

        assertThat(lines.subList(0, 6)).containsExactly(
                "# Báo cáo phân tích cuộc gọi",
                "Call-ID: CALL-EXAMPLE-001",
                "Kết luận: SUCCESS",
                "Cờ chất lượng: Có - NETWORK_PACKET_LOSS",
                "Độ tin cậy: MEDIUM",
                "Tóm tắt: Cuộc gọi thiết lập thành công.");
        assertThat(lines.stream().filter(l -> l.startsWith("## ")).toList()).containsExactlyElementsOf(ReportRenderer.SECTIONS);
    }

    @Test
    @DisplayName("evidence: [ID][file:dòng giờ UTC]; mốc WebRTC tương đối giữ nguyên")
    void evidenceLinesTraceToSource() {
        String text = renderer.render(report(false, null, EVIDENCE, List.of(), List.of()));

        assertThat(text).contains("1. [EV02][signaling#12 10:00:06.320Z] OK_ACK: LegA CONFIRMED")
                .contains("2. [EV10][callee_webrtc.log:902 +4592ms (tương đối)] ICE chuyển checking => failed");
    }

    @Test
    @DisplayName("bảng chỉ số: N/A kèm lý do, proxy ghi rõ, nguồn viết như mẫu (Signaling / End Call)")
    void metricsTable() {
        String text = renderer.render(report(false, null, EVIDENCE, List.of(), List.of()));

        assertThat(text).contains("| Chỉ số | Giá trị | Nguồn |")
                .contains("| Thời gian thiết lập | 6200 ms | Signaling |")
                .contains("| RTT (callee) | N/A (không có trong file log) | End Call |")
                .contains("| Khoảng trống PAIR_PING lớn nhất (caller) | 1019 ms [proxy] | Signaling |");
    }

    @Test
    @DisplayName("không cờ chất lượng -> 'Cờ chất lượng: Không', nguyên nhân 'không có vấn đề'")
    void noQualityFlag() {
        String text = renderer.render(report(false, null, EVIDENCE, List.of(), List.of()));

        assertThat(text).contains("Cờ chất lượng: Không\n")
                .contains("- Chính: không có vấn đề nào được phát hiện")
                .doesNotContain("- Căn cứ:");                                    // report từ rule: AI không trích gì
    }

    @Test
    @DisplayName("phân tích của AI nằm trong mục nguyên nhân, giữa 'Chính' và 'Khả dĩ khác' — không thêm mục mới")
    void aiAnalysisSitsInCausesSection() {
        String text = renderer.render(report(true, "Mất gói 9.8% phía callee.", EVIDENCE, List.of(), List.of()));

        assertThat(text).containsSubsequence("- Chính: NETWORK_PACKET_LOSS", "- Phân tích: Mất gói 9.8% phía callee.",
                "- Căn cứ: EV02, EV10", "- Khả dĩ khác: NETWORK_DELAY_JITTER");
    }

    @Test
    @DisplayName("mục rỗng vẫn có tiêu đề kèm 'Không có.'")
    void emptySectionsStayVisible() {
        String text = renderer.render(report(false, null, List.of(), List.of(), List.of()));

        assertThat(text).contains("## Evidence chính\n- Không có.").contains("## Đề xuất\n- Không có.")
                .contains("## Giới hạn dữ liệu\n- Không có.");
    }

    @Test
    @DisplayName("chữ AI có xuống dòng hay dấu | không làm vỡ danh sách / bảng")
    void aiTextCannotBreakLayout() {
        CallReport r = report(false, "Dòng một.\n\n## Mục giả\nDòng hai.", EVIDENCE,
                List.of("Bước 1\nBước 2"), List.of());
        String text = renderer.render(r);

        assertThat(text).contains("- Phân tích: Dòng một. ## Mục giả Dòng hai.").contains("- Bước 1 Bước 2");
        assertThat(text.lines().filter(l -> l.startsWith("## ")).count()).isEqualTo(5);
    }
}
