package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.report.ReportRenderer;

import java.util.List;

/**
 * Template Compliance (MVP mục 6.5): report đủ mục, đúng thứ tự theo mẫu 4.5.
 *
 * Kiểm trên bản Markdown ĐÃ render — thứ người dùng thấy — chứ không trên JSON: report JSON đúng schema
 * vẫn có thể render sai bố cục nếu renderer hỏng.
 */
public final class TemplateCompliance {

    private TemplateCompliance() {
    }

    /** Dòng đầu là tiêu đề, năm dòng kế là các nhãn đầu report đúng thứ tự, rồi mỗi mục đúng một lần, đúng thứ tự. */
    public static boolean check(String rendered) {
        if (rendered == null) {
            return false;
        }
        List<String> lines = rendered.lines().toList();
        if (lines.size() < 1 + ReportRenderer.HEADER.size() || !lines.getFirst().equals(ReportRenderer.TITLE)) {
            return false;
        }
        for (int i = 0; i < ReportRenderer.HEADER.size(); i++) {
            if (!lines.get(1 + i).startsWith(ReportRenderer.HEADER.get(i) + ": ")) {
                return false;
            }
        }
        List<String> sections = lines.stream().filter(l -> l.startsWith("## ")).toList();
        return sections.equals(ReportRenderer.SECTIONS);
    }
}
