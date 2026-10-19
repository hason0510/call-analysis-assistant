package io.hason.callanalysis.domain.batch;

import io.hason.callanalysis.domain.batch.BatchSummary.Group;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Báo cáo Markdown của một lượt phân tích hàng loạt (MVP 7.1 T10): phân bố verdict / issue category, tách theo nguồn
 * dữ liệu, kèm bảng từng cuộc. File JSON đi kèm để audit; báo cáo này để đọc.
 *
 * Cột của nhóm nguồn dữ liệu chỉ in khi nhóm có cuộc. Mục "Cách đọc" chỉ in khi có cuộc chỉ có signaling — đó là chỗ
 * con số dễ bị đọc sai nhất.
 */
public final class BatchReportRenderer {

    /** @param setup cấu hình của lượt chạy, in đúng thứ tự truyền vào */
    public String render(BatchSummary summary, List<BatchResult> results, Map<String, String> setup) {
        List<SourceGroup> columns = Arrays.stream(SourceGroup.values())
                .filter(c -> summary.groups().get(c).calls() > 0)
                .toList();
        StringBuilder out = new StringBuilder();
        line(out, "# Phân tích hàng loạt — " + summary.all().calls() + " cuộc gọi trong Elasticsearch");
        line(out, "");
        setup.forEach((k, v) -> line(out, "- " + k + ": " + v));

        line(out, "");
        line(out, "## Phân bố kết luận");
        line(out, "");
        header(out, columns);
        row(out, columns, summary, "Số cuộc", g -> String.valueOf(g.calls()));
        for (Verdict v : Verdict.values()) {
            row(out, columns, summary, "`" + v + "`", g -> share(g.verdict(v), g.calls()));
        }
        row(out, columns, summary, "— SUCCESS có cờ chất lượng kém", g -> String.valueOf(g.qualityFlagged()));

        line(out, "");
        line(out, "## Loại lỗi (FAIL và SUCCESS có cờ chất lượng kém)");
        line(out, "");
        boolean anyIssue = false;
        for (IssueCategory c : IssueCategory.values()) {
            if (summary.all().categories().get(c) > 0) {
                if (!anyIssue) {
                    header(out, columns);
                    anyIssue = true;
                }
                row(out, columns, summary, "`" + c + "`", g -> String.valueOf(g.categories().get(c)));
            }
        }
        if (!anyIssue) {
            line(out, "Không có cuộc nào có loại lỗi.");
        }

        line(out, "");
        line(out, "## Độ tin cậy");
        line(out, "");
        header(out, columns);
        for (ConfidenceLevel c : ConfidenceLevel.values()) {
            row(out, columns, summary, "`" + c + "`", g -> String.valueOf(g.confidence().get(c)));
        }

        if (summary.groups().get(SourceGroup.SIGNALING_ONLY).calls() > 0) {
            line(out, "");
            line(out, "## Cách đọc nhóm \"" + SourceGroup.SIGNALING_ONLY.label() + "\"");
            line(out, "");
            line(out, "- Elasticsearch chỉ chứa signaling; log máy (end call, WebRTC) do người dùng đính kèm (MVP 1.2)."
                    + " Signaling cho biết cuộc gọi có thiết lập được không, KHÔNG cho biết âm thanh có tới không —"
                    + " một cuộc signaling hoàn hảo vẫn có thể 0 byte âm thanh. Vì vậy rule không kết luận SUCCESS khi"
                    + " chưa kiểm được media: **UNKNOWN ở nhóm này nghĩa là thiếu log máy, không phải cuộc gọi có vấn"
                    + " đề**.");
            line(out, "- FAIL ở nhóm này tin được: signaling tự cho thấy cuộc gọi không thiết lập được.");
            line(out, "- **Loại lỗi ở nhóm này là triệu chứng nhìn từ server, chưa phải nguyên nhân.** Lỗi TURN / ICE chỉ"
                    + " thấy trong WebRTC log, nên hiện ra thành `SIGNALING_FAILURE`. Muốn biết nguyên nhân: chạy lại"
                    + " với `--logs=<thư mục log>`.");
        }

        line(out, "");
        line(out, "## Từng cuộc");
        line(out, "");
        line(out, "| # | Call-ID | Nguồn dữ liệu | Kết luận | Loại lỗi | Cờ chất lượng | Tin cậy | Evidence |");
        line(out, "| --- | --- | --- | --- | --- | --- | --- | --- |");
        int i = 0;
        for (BatchResult r : results) {
            line(out, "| " + (++i) + " | " + r.callId() + " | " + r.sourceGroup().label() + " | " + r.verdict()
                    + " | " + (r.issueCategory() == null ? "-" : r.issueCategory()) + " | "
                    + (r.qualityFlag() ? "Có" : "Không") + " | " + r.confidence() + " | " + r.evidenceCount() + " |");
        }
        return out.toString();
    }

    private static void header(StringBuilder out, List<SourceGroup> columns) {
        StringBuilder h = new StringBuilder("| | Cả lượt");
        StringBuilder sep = new StringBuilder("| --- | ---");
        for (SourceGroup c : columns) {
            h.append(" | ").append(c.label());
            sep.append(" | ---");
        }
        line(out, h.append(" |").toString());
        line(out, sep.append(" |").toString());
    }

    private static void row(StringBuilder out, List<SourceGroup> columns, BatchSummary s, String label,
                            Function<Group, String> value) {
        StringBuilder r = new StringBuilder("| ").append(label).append(" | ").append(value.apply(s.all()));
        for (SourceGroup c : columns) {
            r.append(" | ").append(value.apply(s.groups().get(c)));
        }
        line(out, r.append(" |").toString());
    }

    /** {@code 9 (45,0 %)} — dấu phẩy thập phân như các báo cáo khác của dự án; nhóm rỗng in "0". */
    static String share(int count, int total) {
        if (total == 0) {
            return "0";
        }
        return count + " (" + String.format(Locale.ROOT, "%.1f", 100.0 * count / total).replace('.', ',') + " %)";
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
