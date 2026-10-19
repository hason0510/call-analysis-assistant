package io.hason.callanalysis.domain.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Báo cáo Markdown của T10: tách nhóm nguồn dữ liệu, giải thích nhóm chỉ signaling, bảng từng cuộc. */
class BatchReportRendererTest {

    private final BatchReportRenderer renderer = new BatchReportRenderer();

    private String render(List<BatchResult> results) {
        return renderer.render(BatchSummary.of(results), results, Map.of("Nguồn Call-ID", "Elasticsearch"));
    }

    @Test
    @DisplayName("có cuộc chỉ signaling -> in mục \"Cách đọc\": UNKNOWN là thiếu log máy, loại lỗi chỉ là triệu chứng")
    void explainsSignalingOnlyGroup() {
        String md = render(BatchSummaryTest.SAMPLE);

        assertThat(md).contains("- Nguồn Call-ID: Elasticsearch")
                .contains("| | Cả lượt | Chỉ signaling | Signaling + log máy |")
                .contains("| `UNKNOWN` | 1 (16,7 %) | 1 (50,0 %) | 0 (0,0 %) |")
                .contains("| `TURN_FAILURE` | 2 | 0 | 2 |")
                .contains("UNKNOWN ở nhóm này nghĩa là thiếu log máy")
                .contains("Loại lỗi ở nhóm này là triệu chứng nhìn từ server");
    }

    @Test
    @DisplayName("mọi cuộc có log máy -> không có cột rỗng, không có mục giải thích nhóm chỉ signaling")
    void noSignalingOnlyGroupNoNote() {
        List<BatchResult> withLogs = BatchSummaryTest.SAMPLE.subList(2, 6);
        String md = render(withLogs);

        assertThat(md).contains("| | Cả lượt | Signaling + log máy |")
                .doesNotContain("Chỉ signaling |")
                .doesNotContain("Cách đọc");
    }

    @Test
    @DisplayName("loại lỗi không xuất hiện thì không in dòng; bảng từng cuộc giữ đúng thứ tự đầu vào")
    void perCallTable() {
        String md = render(BatchSummaryTest.SAMPLE);

        assertThat(md).doesNotContain("`ICE_FAILURE`")
                .contains("| 1 | A | Chỉ signaling | FAIL | SIGNALING_FAILURE | Không | MEDIUM | 3 |")
                .contains("| 3 | C | Signaling + log máy | SUCCESS | - | Không | HIGH | 3 |")
                .contains("| 4 | D | Signaling + log máy | SUCCESS | NETWORK_PACKET_LOSS | Có | LOW | 3 |");
        assertThat(md.indexOf("| 1 | A ")).isLessThan(md.indexOf("| 6 | F "));
    }

    @Test
    @DisplayName("lượt không có loại lỗi nào -> nói rõ thay vì in bảng rỗng")
    void noIssues() {
        String md = render(List.of(BatchSummaryTest.SAMPLE.get(2)));

        assertThat(md).contains("Không có cuộc nào có loại lỗi.");
    }

    @Test
    @DisplayName("tỉ lệ dùng dấu phẩy thập phân; nhóm rỗng in 0")
    void shareFormat() {
        assertThat(BatchReportRenderer.share(9, 20)).isEqualTo("9 (45,0 %)");
        assertThat(BatchReportRenderer.share(0, 0)).isEqualTo("0");
    }
}
