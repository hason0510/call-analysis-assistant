package io.hason.callanalysis.domain.confidence;

import io.hason.callanalysis.domain.confidence.ConfidencePolicy.AiCheck;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bảng MVP mục 7.2, từng dòng một:
 * <pre>
 *   Strong evidence + AI khớp rule + đủ file    → HIGH
 *   Partial evidence hoặc thiếu một phần file   → MEDIUM
 *   Weak / ambiguous evidence hoặc AI lệch rule → LOW
 * </pre>
 */
class ConfidencePolicyTest {

    private static final ConfidenceBasis STRONG = ConfidenceBasis.strong("ICE chuyển sang failed");
    private static final DataCoverage FULL = DataCoverage.complete();
    private static final DataCoverage MISSING_CALLEE = new DataCoverage(false, List.of(), List.of(Leg.CALLEE));

    private static ConfidenceAssessment report(ConfidenceBasis basis, DataCoverage coverage, AiCheck ai) {
        return ConfidencePolicy.forReport(ConfidencePolicy.forRule(basis, coverage), ai, "TIMEOUT");
    }

    @Nested
    @DisplayName("dòng 1: bằng chứng mạnh + AI khớp rule + đủ file -> HIGH")
    class Row1 {

        @Test
        @DisplayName("đủ cả ba điều kiện -> HIGH, lý do nêu bằng chứng và việc AI khớp")
        void allThreeGiveHigh() {
            ConfidenceAssessment c = report(STRONG, FULL, AiCheck.AGREES);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.HIGH);
            assertThat(c.reasons()).containsExactly("bằng chứng rõ: ICE chuyển sang failed", "AI khớp rule");
            assertThat(c.describe()).isEqualTo("HIGH — bằng chứng rõ: ICE chuyển sang failed; AI khớp rule");
        }

        @Test
        @DisplayName("AI khớp rule không NÂNG mức: bằng chứng chưa trọn vẫn MEDIUM")
        void agreementNeverRaises() {
            assertThat(report(ConfidenceBasis.partial("CANCEL mơ hồ"), FULL, AiCheck.AGREES).level())
                    .isEqualTo(ConfidenceLevel.MEDIUM);
            assertThat(report(ConfidenceBasis.weak("ngưỡng chưa kiểm chứng"), FULL, AiCheck.AGREES).level())
                    .isEqualTo(ConfidenceLevel.LOW);
        }
    }

    @Nested
    @DisplayName("dòng 2: bằng chứng chưa trọn HOẶC thiếu một phần file -> MEDIUM")
    class Row2 {

        @Test
        @DisplayName("bằng chứng chưa trọn, đủ file, AI khớp -> MEDIUM, lý do đứng đầu")
        void partialEvidence() {
            ConfidenceAssessment c = report(ConfidenceBasis.partial("CANCEL chưa rõ ai huỷ"), FULL, AiCheck.AGREES);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.MEDIUM);
            assertThat(c.reasons().getFirst()).isEqualTo("bằng chứng chưa trọn: CANCEL chưa rõ ai huỷ");
        }

        @Test
        @DisplayName("bằng chứng mạnh nhưng thiếu log client một leg -> MEDIUM, yếu tố hạ mức đứng trước bằng chứng")
        void missingLeg() {
            ConfidenceAssessment c = report(STRONG, MISSING_CALLEE, AiCheck.AGREES);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.MEDIUM);
            assertThat(c.reasons()).containsExactly("thiếu log client của callee",
                    "bằng chứng rõ: ICE chuyển sang failed", "AI khớp rule");
        }

        @Test
        @DisplayName("kết luận cần đủ ba nguồn (SUCCESS) mà thiếu một loại nguồn -> MEDIUM")
        void missingSourceWhenAllRequired() {
            DataCoverage noEndCall = new DataCoverage(false, List.of(LogSource.ENDCALL), List.of());

            assertThat(ConfidencePolicy.forRule(STRONG.asRequiringAllSources(), noEndCall).level())
                    .isEqualTo(ConfidenceLevel.MEDIUM);
            // Kết luận FAIL đứng trên bằng chứng dương tính: thiếu một LOẠI nguồn không làm nó yếu đi
            assertThat(ConfidencePolicy.forRule(STRONG, noEndCall).level()).isEqualTo(ConfidenceLevel.HIGH);
        }

        @Test
        @DisplayName("bản export signaling bị cắt -> MEDIUM chỉ với kết luận dựa trên việc không thấy sự kiện")
        void truncationOnlyHitsAbsenceBased() {
            DataCoverage truncated = new DataCoverage(true, List.of(), List.of());

            ConfidenceAssessment absence = ConfidencePolicy.forRule(
                    ConfidenceBasis.strong("signaling không có INVITE nào").asAbsenceBased(), truncated);
            assertThat(absence.level()).isEqualTo(ConfidenceLevel.MEDIUM);
            assertThat(absence.reasons().getFirst()).contains("bị cắt bớt");
            // ICE failed là bằng chứng dương tính từ log client: bản signaling thiếu event không đụng tới
            assertThat(ConfidencePolicy.forRule(STRONG, truncated).level()).isEqualTo(ConfidenceLevel.HIGH);
        }

        @Test
        @DisplayName("ngoại lệ: server từ chối INIT_CALL kèm mã -> thiếu log client không hạ mức")
        void serverRejectionIgnoresMissingLegs() {
            ConfidenceBasis rejection = ConfidenceBasis.strong("server từ chối INIT_CALL với mã 428")
                    .asExemptFromMissingLegs();

            ConfidenceAssessment c = ConfidencePolicy.forRule(rejection, MISSING_CALLEE);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.HIGH);
            assertThat(c.reasons()).noneMatch(r -> r.contains("thiếu log client"));
        }

        @Test
        @DisplayName("không có AI để đối chiếu (fallback) -> tối đa MEDIUM, nêu mã lý do")
        void aiUnavailableCapsAtMedium() {
            ConfidenceAssessment c = report(STRONG, FULL, AiCheck.UNAVAILABLE);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.MEDIUM);
            assertThat(c.reasons().getFirst()).isEqualTo("không có AI để đối chiếu (TIMEOUT)");
            // Không hạ thêm mức đã thấp
            assertThat(report(ConfidenceBasis.weak("x"), FULL, AiCheck.UNAVAILABLE).level())
                    .isEqualTo(ConfidenceLevel.LOW);
        }
    }

    @Nested
    @DisplayName("dòng 3: bằng chứng yếu HOẶC AI lệch rule -> LOW")
    class Row3 {

        @Test
        @DisplayName("bằng chứng yếu (ngưỡng chưa kiểm chứng) -> LOW dù đủ file và AI khớp")
        void weakEvidence() {
            ConfidenceAssessment c = report(ConfidenceBasis.weak("ngưỡng chưa kiểm chứng"), FULL, AiCheck.AGREES);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.LOW);
            assertThat(c.reasons().getFirst()).isEqualTo("bằng chứng yếu: ngưỡng chưa kiểm chứng");
        }

        @Test
        @DisplayName("AI lệch rule -> LOW dù bằng chứng mạnh và đủ file; lý do đứng đầu")
        void aiDisagrees() {
            ConfidenceAssessment c = report(STRONG, FULL, AiCheck.DISAGREES);

            assertThat(c.level()).isEqualTo(ConfidenceLevel.LOW);
            assertThat(c.reasons().getFirst()).isEqualTo("AI và rule không thống nhất");
        }
    }

    @Test
    @DisplayName("luồng CLI chỉ rule (không chạy AI) giữ nguyên mức và lý do của rule")
    void ruleOnlyKeepsRuleAssessment() {
        ConfidenceAssessment rule = ConfidencePolicy.forRule(STRONG, FULL);

        assertThat(ConfidencePolicy.forReport(rule, AiCheck.NOT_RUN, null)).isEqualTo(rule);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EvidenceStrength.class)
    @DisplayName("mọi mức đều có lý do: report không bao giờ in mức trần")
    void everyLevelHasReasons(EvidenceStrength strength) {
        ConfidenceBasis basis = new ConfidenceBasis(strength, "căn cứ", false, false, false);

        for (AiCheck ai : AiCheck.values()) {
            assertThat(report(basis, FULL, ai).reasons()).isNotEmpty();
        }
    }
}
