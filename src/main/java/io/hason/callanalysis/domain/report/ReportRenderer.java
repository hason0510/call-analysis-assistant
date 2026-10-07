package io.hason.callanalysis.domain.report;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Report Renderer (MVP mục 6.1 T2): dựng report dạng Markdown theo ĐÚNG bố cục mẫu MVP mục 4.5 từ
 * {@link CallReport} — bản JSON đã qua schema. Bố cục nằm ở đây, trong code; AI chỉ điền field
 * (MVP mục 3.2), nên không câu trả lời nào của AI làm đổi thứ tự hay thêm bớt mục được.
 *
 * Sáu mục theo thứ tự cố định, mục rỗng vẫn in kèm "Không có." để Template Compliance (MVP mục 6.5)
 * kiểm được bằng cách đếm tiêu đề.
 *
 * Hai chỗ khác mẫu, có chủ ý:
 * - Evidence in cả số dòng: {@code [EV05][callee_endcall.log:142 10:00:41.000Z]}. Mẫu chỉ có nguồn +
 *   giờ, nhưng MVP mục 8.2 đòi trace được về dòng log gốc. Giờ in theo UTC, có chữ Z, cùng múi với
 *   signaling gốc — người đọc grep log được ngay.
 * - Phần phân tích của AI (trả lời trọng tâm câu hỏi, MVP mục 4.4) là dòng "Phân tích:" trong mục
 *   "Vấn đề chất lượng / nguyên nhân khả dĩ", kèm dòng "Căn cứ:" nêu evidence AI trích — không thêm mục mới.
 */
public class ReportRenderer {

    public static final String TITLE = "# Báo cáo phân tích cuộc gọi";

    /** Nhãn các dòng đầu report, đúng thứ tự mẫu 4.5 (sau tiêu đề). Web UI lấy từ đây qua API. */
    public static final List<String> HEADER = List.of(
            "Call-ID", "Kết luận", "Cờ chất lượng", "Độ tin cậy", "Tóm tắt");

    public static final List<String> SECTIONS = List.of(
            "## Evidence chính",
            "## Chỉ số cuộc gọi",
            "## Vấn đề chất lượng / nguyên nhân khả dĩ",
            "## Đề xuất",
            "## Giới hạn dữ liệu");

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final Map<String, String> SOURCE_NAMES = Map.of(
            "SIGNALING", "Signaling", "ENDCALL", "End Call", "WEBRTC", "WebRTC");

    private static final String NONE = "- Không có.";

    public String render(CallReport r) {
        StringBuilder out = new StringBuilder();
        line(out, TITLE);
        line(out, HEADER.get(0) + ": " + r.callId());
        line(out, HEADER.get(1) + ": " + r.verdict());
        // Mẫu 4.5: category chỉ ghi ở dòng này khi cờ BẬT. Với FAIL, category là nguyên nhân cuộc gọi
        // hỏng, không phải của cờ chất lượng — đã có ở mục "Chính:" bên dưới.
        line(out, HEADER.get(2) + ": " + (r.qualityFlag() ? "Có - " + r.issueCategory() : "Không"));
        line(out, HEADER.get(3) + ": " + r.confidenceLevel());
        line(out, HEADER.get(4) + ": " + oneLine(r.summary()));

        section(out, SECTIONS.get(0));
        if (r.evidence().isEmpty()) {
            line(out, NONE);
        }
        int no = 0;
        for (CallReport.EvidenceEntry e : r.evidence()) {
            line(out, ++no + ". [" + e.id() + "][" + e.sourceRef() + " " + clock(e.timestamp()) + "] "
                    + oneLine(e.description()));
        }

        section(out, SECTIONS.get(1));
        line(out, "| Chỉ số | Giá trị | Nguồn |");
        line(out, "| --- | --- | --- |");
        for (CallReport.MetricEntry m : r.metrics()) {
            line(out, "| " + cell(m.name()) + " | " + cell(value(m)) + " | "
                    + SOURCE_NAMES.getOrDefault(m.source(), m.source()) + " |");
        }

        section(out, SECTIONS.get(2));
        CallReport.PossibleCauses causes = r.possibleCauses();
        line(out, "- Chính: " + (causes == null || causes.primary() == null
                ? "không có vấn đề nào được phát hiện" : oneLine(causes.primary())));
        if (r.analysis() != null) {
            line(out, "- Phân tích: " + oneLine(r.analysis()));
        }
        if (!r.citedEvidenceIds().isEmpty()) {
            line(out, "- Căn cứ: " + String.join(", ", r.citedEvidenceIds()));
        }
        if (causes != null) {
            causes.alternatives().forEach(a -> line(out, "- Khả dĩ khác: " + oneLine(a)));
        }

        section(out, SECTIONS.get(3));
        bullets(out, r.suggestions());

        section(out, SECTIONS.get(4));
        bullets(out, r.dataLimitations());
        return out.toString();
    }

    private static String value(CallReport.MetricEntry m) {
        if (m.value() == null) {
            return "N/A (" + m.naReason() + ")";
        }
        return m.unit() == null || m.unit().isBlank() ? m.value() : m.value() + " " + m.unit();
    }

    /** Mốc tuyệt đối ISO-8601 → giờ UTC; mốc tương đối của WebRTC ("+4592ms (tương đối)") giữ nguyên. */
    static String clock(String timestamp) {
        if (timestamp == null) {
            return "-";
        }
        try {
            return CLOCK.format(Instant.parse(timestamp));
        } catch (DateTimeParseException e) {
            return timestamp;
        }
    }

    private static void bullets(StringBuilder out, List<String> items) {
        if (items.isEmpty()) {
            line(out, NONE);
        }
        items.forEach(i -> line(out, "- " + oneLine(i)));
    }

    private static void section(StringBuilder out, String title) {
        line(out, "");
        line(out, title);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }

    /** Mỗi mục một dòng: xuống dòng trong chữ của AI sẽ làm vỡ danh sách Markdown. */
    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s*\\R\\s*", " ");
    }

    /** Ô bảng Markdown: dấu | trong giá trị làm lệch cột. */
    private static String cell(String text) {
        return oneLine(text).replace("|", "\\|");
    }
}
