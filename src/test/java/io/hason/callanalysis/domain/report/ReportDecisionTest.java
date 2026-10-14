package io.hason.callanalysis.domain.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineBuilder;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ghép kết luận sau AI + Guardrails vào report: ai viết phần nào, độ tin cậy theo MVP mục 7.2, và
 * mọi biến thể đều hợp lệ theo schema (MVP mục 6.1 T2: JSON qua schema trước khi render).
 */
class ReportDecisionTest {

    private static final CallTimeline TIMELINE = new TimelineBuilder().build("CALL-1", List.of(), LegAssignment.unknown());
    private static final RuleVerdict RULE = new RuleVerdict(Verdict.FAIL, false, IssueCategory.ICE_FAILURE,
            ConfidenceLevel.HIGH, "ICE chuyển sang failed và không bao giờ đạt connected", List.of());
    private static final ReportSchemaValidator SCHEMA = new ReportSchemaValidator(new ObjectMapper());

    private static CallReport build(ReportDecision decision, List<String> extra) {
        CallReport r = new ReportBuilder().build(TIMELINE, new MetricsCalculator().calculate(TIMELINE), List.of(),
                RULE, new TaxonomyLoader().load(), decision, extra);
        assertThat(SCHEMA.validate(r).errors()).isEmpty();
        return r;
    }

    private static ReportDecision ai(Verdict verdict, IssueCategory category, boolean needsReview, String... notes) {
        return new ReportDecision(CallReport.AnalysisSource.AI, verdict, false, category, needsReview, null,
                "ICE thất bại phía callee.", "Không có gói audio nào được truyền.", List.of("Kiểm tra TURN."),
                List.of(notes), List.of("EV01"));
    }

    @Test
    @DisplayName("AI khớp rule ở ca FAIL -> tóm tắt do CODE viết từ lý do của rule; phân tích của AI; đề xuất chuẩn GIỮ")
    void agreeingAiFillsTheText() {
        CallReport r = build(ai(Verdict.FAIL, IssueCategory.ICE_FAILURE, false), List.of());

        assertThat(r.analysisSource()).isEqualTo(CallReport.AnalysisSource.AI);
        // AI từng mở đầu tóm tắt ca FAIL bằng "không thiết lập được" cho cuộc ĐÃ thiết lập (70C388AD, FE4EF5E7)
        assertThat(r.summary()).isEqualTo("Cuộc gọi không thành công. ICE chuyển sang failed và không bao giờ đạt connected.")
                .doesNotContain("ICE thất bại phía callee.");
        assertThat(r.analysis()).isEqualTo("Không có gói audio nào được truyền.");
        assertThat(r.suggestions()).isEqualTo(ReportBuilder.standardSuggestions(RULE));      // MVP 7.1 T2
        assertThat(r.aiSuggestions()).containsExactly("Kiểm tra TURN.");
        assertThat(r.confidenceLevel()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(r.confidenceReasons()).last().isEqualTo("AI khớp rule");
        assertThat(r.citedEvidenceIds()).containsExactly("EV01");
        assertThat(r.degraded()).isFalse();
        assertThat(r.needsReview()).isFalse();
    }

    @Test
    @DisplayName("AI khớp rule ở ca SUCCESS -> tóm tắt vẫn là chữ AI (chỉ ca FAIL do code viết)")
    void agreeingAiOnSuccessKeepsAiSummary() {
        RuleVerdict success = new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.HIGH,
                "Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media", List.of());
        ReportDecision decision = new ReportDecision(CallReport.AnalysisSource.AI, Verdict.SUCCESS, false, null, false,
                null, "Cuộc gọi bình thường, bên nhận nghe rõ.", "Không có dấu hiệu lỗi media.", List.of(), List.of(),
                List.of("EV01"));
        CallReport r = new ReportBuilder().build(TIMELINE, new MetricsCalculator().calculate(TIMELINE), List.of(),
                success, new TaxonomyLoader().load(), decision, List.of());

        assertThat(SCHEMA.validate(r).errors()).isEmpty();
        assertThat(r.summary()).isEqualTo("Cuộc gọi bình thường, bên nhận nghe rõ.");
    }

    @Test
    @DisplayName("AI lệch verdict (G04) -> UNKNOWN, LOW, cần kiểm tra; tóm tắt do code viết, không lấy chữ AI")
    void verdictMismatchIsCodeWritten() {
        CallReport r = build(ai(Verdict.UNKNOWN, IssueCategory.UNKNOWN, true, "AI kết luận SUCCESS, rule kết luận FAIL"),
                List.of());

        assertThat(r.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(r.confidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(r.needsReview()).isTrue();
        assertThat(r.summary()).startsWith("Không đủ căn cứ để kết luận: AI kết luận SUCCESS, rule kết luận FAIL")
                .doesNotContain("ICE thất bại phía callee.");
        assertThat(r.suggestions()).last().asString().contains("Đối chiếu evidence");
        assertThat(r.analysis()).isEqualTo("Không có gói audio nào được truyền.");     // giữ cho người kiểm tra
        assertThat(r.dataLimitations()).anyMatch(l -> l.startsWith("Cần kiểm tra: AI kết luận SUCCESS"));
        // Lý do độ tin cậy ở dòng "Độ tin cậy", không lặp lại ở Giới hạn dữ liệu
        assertThat(r.confidenceReasons().getFirst()).isEqualTo("AI và rule không thống nhất");
        assertThat(r.dataLimitations()).noneMatch(l -> l.toLowerCase().contains("độ tin cậy"));
    }

    @Test
    @DisplayName("cùng verdict, khác category (G04) -> giữ category của rule, LOW, tóm tắt của rule")
    void categoryMismatchKeepsRuleText() {
        CallReport r = build(ai(Verdict.FAIL, IssueCategory.ICE_FAILURE, true, "AI xếp TURN_FAILURE, rule xếp ICE_FAILURE"),
                List.of());

        assertThat(r.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(r.confidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(r.summary()).contains("ICE chuyển sang failed").doesNotContain("ICE thất bại phía callee.");
    }

    @Test
    @DisplayName("fallback -> report từ rule, degraded + mã lý do, HIGH hạ xuống MEDIUM (không có AI để đối chiếu)")
    void fallbackIsDegraded() {
        CallReport r = build(new ReportDecision(CallReport.AnalysisSource.RULE, Verdict.FAIL, false,
                IssueCategory.ICE_FAILURE, false, "TIMEOUT", null, null, List.of(), List.of(), List.of()), List.of());

        assertThat(r.degraded()).isTrue();
        assertThat(r.fallbackReason()).isEqualTo("TIMEOUT");
        assertThat(r.analysisSource()).isEqualTo(CallReport.AnalysisSource.RULE);
        assertThat(r.analysis()).isNull();
        assertThat(r.citedEvidenceIds()).isEmpty();
        assertThat(r.confidenceLevel()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(r.summary()).startsWith("Cuộc gọi không thành công.");
        assertThat(r.dataLimitations()).anyMatch(l -> l.contains("(TIMEOUT)"));
        assertThat(r.confidenceReasons().getFirst()).isEqualTo("không có AI để đối chiếu (TIMEOUT)");
        assertThat(r.dataLimitations()).noneMatch(l -> l.toLowerCase().contains("độ tin cậy"));
    }

    @Test
    @DisplayName("T2 — đề xuất AI trùng bộ chuẩn (khác hoa thường, dấu câu) bị bỏ; tối đa 3 câu bổ sung")
    void aiSuggestionsAreOnlyAdditions() {
        List<String> standard = List.of("Xác nhận TURN server có cấp phát được relay không.");

        List<String> extra = ReportBuilder.additionalSuggestions(List.of(
                "xác nhận TURN server có cấp phát được relay không", "Một", "Hai", "", "Ba", "Bốn"), standard);

        assertThat(extra).containsExactly("Một", "Hai", "Ba");
    }

    @Test
    @DisplayName("T2 — AI lệch rule hoặc fallback: không có đề xuất AI; schema chặn report RULE mang đề xuất AI")
    void noAiSuggestionsWithoutUsableAi() {
        CallReport flagged = build(ai(Verdict.UNKNOWN, IssueCategory.UNKNOWN, true, "AI kết luận SUCCESS, rule kết luận FAIL"),
                List.of());
        CallReport fallback = build(new ReportDecision(CallReport.AnalysisSource.RULE, Verdict.FAIL, false,
                IssueCategory.ICE_FAILURE, false, "TIMEOUT", null, null, List.of("x"), List.of(), List.of()), List.of());
        CallReport ruleWithAiSuggestion = new CallReport(fallback.callId(), fallback.verdict(), fallback.qualityFlag(),
                fallback.issueCategory(), fallback.confidenceLevel(), fallback.confidenceReasons(), fallback.summary(),
                fallback.evidence(), fallback.metrics(), fallback.possibleCauses(), fallback.suggestions(),
                fallback.dataLimitations(), true, null, CallReport.AnalysisSource.RULE, false, "TIMEOUT", List.of(),
                List.of("chữ của AI"));

        assertThat(flagged.aiSuggestions()).isEmpty();
        assertThat(fallback.aiSuggestions()).isEmpty();
        assertThat(fallback.suggestions()).isEqualTo(ReportBuilder.standardSuggestions(RULE));
        assertThat(SCHEMA.validate(ruleWithAiSuggestion).valid()).isFalse();
    }

    @Test
    @DisplayName("T2 — AI lệch verdict: 'vì sao kết luận' nói AI và rule không thống nhất, kèm lý do của rule")
    void changedVerdictExplainsWhy() {
        CallReport r = build(ai(Verdict.UNKNOWN, IssueCategory.UNKNOWN, true, "AI kết luận SUCCESS, rule kết luận FAIL"),
                List.of());

        assertThat(r.possibleCauses().reasoning()).startsWith("AI và rule không thống nhất (AI kết luận SUCCESS")
                .contains("ICE chuyển sang failed");
    }

    @Test
    @DisplayName("ghi chú của Request Parser đi vào giới hạn dữ liệu")
    void requestNotesReachLimitations() {
        CallReport r = build(ai(Verdict.FAIL, IssueCategory.ICE_FAILURE, false),
                List.of("Câu hỏi nêu 2 Call-ID khác nhau"));

        assertThat(r.dataLimitations()).contains("Câu hỏi nêu 2 Call-ID khác nhau");
    }

    @Test
    @DisplayName("schema bắt report tự mâu thuẫn: degraded mà không có lý do, hoặc RULE mà có phân tích AI")
    void schemaRejectsInconsistentFlags() {
        CallReport ok = build(ai(Verdict.FAIL, IssueCategory.ICE_FAILURE, false), List.of());
        CallReport degradedNoReason = new CallReport(ok.callId(), ok.verdict(), ok.qualityFlag(), ok.issueCategory(),
                ok.confidenceLevel(), ok.confidenceReasons(), ok.summary(), ok.evidence(), ok.metrics(), ok.possibleCauses(), ok.suggestions(),
                ok.dataLimitations(), true, null, CallReport.AnalysisSource.RULE, false, null, List.of(), List.of());
        CallReport ruleWithAnalysis = new CallReport(ok.callId(), ok.verdict(), ok.qualityFlag(), ok.issueCategory(),
                ok.confidenceLevel(), ok.confidenceReasons(), ok.summary(), ok.evidence(), ok.metrics(), ok.possibleCauses(), ok.suggestions(),
                ok.dataLimitations(), false, "chữ của AI", CallReport.AnalysisSource.RULE, false, null, List.of(), List.of());

        assertThat(SCHEMA.validate(degradedNoReason).valid()).isFalse();
        assertThat(SCHEMA.validate(ruleWithAnalysis).valid()).isFalse();
    }
}
