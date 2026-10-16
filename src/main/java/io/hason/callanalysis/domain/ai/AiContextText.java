package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.ai.AiContext.CategoryItem;
import io.hason.callanalysis.domain.ai.AiContext.EvidenceItem;
import io.hason.callanalysis.domain.ai.AiContext.LogFile;
import io.hason.callanalysis.domain.ai.AiContext.MetricItem;
import io.hason.callanalysis.domain.ai.AiContext.Payload;
import io.hason.callanalysis.domain.ai.AiContext.RuleSummary;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Bản SEMI-STRUCTURED của context gửi AI (T11 Sprint 2, theo ví dụ mentor gửi 2026-10-15): đúng các dữ kiện của
 * {@link Payload} — không thêm, không bớt — nhưng viết thành văn bản thay cho JSON. Dùng để so sánh hai cách dựng
 * context trên cùng benchmark.
 *
 * Mỗi phần mang tên trường JSON trong ngoặc ("Evidence (evidence)", "căn cứ của rule (ruleBasis)"), để cùng một system
 * prompt — vốn gọi tên các trường đó — dùng được cho cả hai dạng. Như vậy hai lượt đo chỉ khác cách trình bày context,
 * cùng dấu vân tay prompt. Danh sách số G05 được phép nhắc lại ({@link AiContext#groundingTexts()}) không đổi, vì dữ kiện
 * không đổi.
 *
 * Thuần, tất định: cùng payload luôn ra cùng chuỗi.
 */
public final class AiContextText {

    private AiContextText() {
    }

    public static String render(Payload payload) {
        StringBuilder out = new StringBuilder();
        RuleSummary rule = payload.ruleVerdict();
        out.append("Kết luận của rule (ruleVerdict): ").append(rule.verdict())
                .append(", nhóm vấn đề ").append(rule.issueCategory())
                .append(", cờ chất lượng ").append(rule.qualityFlag() ? "có" : "không")
                .append(", độ tin cậy ").append(rule.confidence()).append(".\n");
        line(out, "Lý do (reasoning): ", rule.reasoning());
        line(out, "Căn cứ của nguyên nhân (causeBasis): ", rule.causeBasis());
        list(out, "Đề xuất chuẩn (standardSuggestions):", rule.standardSuggestions());

        out.append("Evidence (evidence):\n");
        if (payload.evidence().isEmpty()) {
            out.append("- (không có)\n");
        }
        for (EvidenceItem e : payload.evidence()) {
            out.append('[').append(e.id()).append("][").append(e.source()).append(", ").append(e.leg())
                    .append(", ").append(e.time()).append("] ").append(e.description());
            if (e.citation() != null) {
                out.append(" — dòng ").append(e.citation());
            }
            if (e.ruleBasis()) {
                out.append(" — căn cứ của rule (ruleBasis)");
            }
            out.append('\n');
        }

        out.append("Chỉ số (metrics):\n");
        if (payload.metrics().isEmpty()) {
            out.append("- (không có)\n");
        }
        for (MetricItem m : payload.metrics()) {
            out.append("- ").append(m.id()).append(" — ").append(m.name()).append(": ").append(m.value())
                    .append(" (").append(m.source()).append(")\n");
        }

        out.append("File log có mặt (logs): ").append(payload.logs().isEmpty() ? "không có"
                : payload.logs().stream().map(AiContextText::log).collect(Collectors.joining("; "))).append(".\n");
        out.append("Giới hạn dữ liệu (dataLimitations): ").append(payload.dataLimitations().isEmpty() ? "không có"
                : String.join("; ", payload.dataLimitations())).append(".\n");

        out.append("Nhóm vấn đề hợp lệ (taxonomy):\n");
        for (CategoryItem c : payload.taxonomy()) {
            out.append("- ").append(c.id()).append(c.validated() ? " (đã kiểm chứng)" : " (chưa kiểm chứng)")
                    .append(": ").append(c.definition());
            if (!c.symptoms().isEmpty()) {
                out.append(" Triệu chứng: ").append(String.join("; ", c.symptoms())).append('.');
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static String log(LogFile f) {
        String clock = "ABSOLUTE".equals(f.timeBase()) ? "giờ tuyệt đối"
                : "RELATIVE".equals(f.timeBase()) ? "giờ tương đối" : f.timeBase();
        // Signaling không thuộc leg nào (leg null): bỏ hẳn, không in chữ "null"
        return f.file() + " (" + f.source() + (f.leg() == null ? "" : ", " + f.leg()) + ", " + clock + ")";
    }

    private static void line(StringBuilder out, String label, String text) {
        if (text != null && !text.isBlank()) {
            out.append(label).append(text).append('\n');
        }
    }

    private static void list(StringBuilder out, String label, List<String> items) {
        if (!items.isEmpty()) {
            out.append(label).append('\n');
            items.forEach(i -> out.append("- ").append(i).append('\n'));
        }
    }
}
