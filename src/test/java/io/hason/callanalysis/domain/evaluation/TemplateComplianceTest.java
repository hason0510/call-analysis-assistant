package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateComplianceTest {

    private static String rendered() {
        CallReport r = new CallReport("CALL", Verdict.SUCCESS, false, null, ConfidenceLevel.HIGH, "Tóm tắt.",
                List.of(), List.of(), null, List.of(), List.of(), false);
        return new ReportRenderer().render(r);
    }

    @Test
    @DisplayName("bản render của ReportRenderer đạt mẫu 4.5")
    void rendererOutputComplies() {
        assertThat(TemplateCompliance.check(rendered())).isTrue();
    }

    @Test
    @DisplayName("thiếu một mục -> không đạt")
    void missingSection() {
        assertThat(TemplateCompliance.check(rendered().replace("## Đề xuất\n", ""))).isFalse();
    }

    @Test
    @DisplayName("hai mục đổi chỗ -> không đạt")
    void sectionsOutOfOrder() {
        String swapped = rendered().replace("## Đề xuất", "@@").replace("## Giới hạn dữ liệu", "## Đề xuất")
                .replace("@@", "## Giới hạn dữ liệu");
        assertThat(TemplateCompliance.check(swapped)).isFalse();
    }

    @Test
    @DisplayName("dòng đầu sai nhãn -> không đạt; null -> không đạt")
    void headerAndNull() {
        assertThat(TemplateCompliance.check(rendered().replace("Độ tin cậy:", "Tin cậy:"))).isFalse();
        assertThat(TemplateCompliance.check(null)).isFalse();
    }
}
