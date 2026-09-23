package io.hason.callanalysis.infrastructure.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.IssueDefinition;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyAndReportTest {

    private final IssueTaxonomy taxonomy = new TaxonomyLoader().load();
    private final ReportSchemaValidator validator = new ReportSchemaValidator(new ObjectMapper());

    @Test
    @DisplayName("taxonomy.yaml khai bao du 6 category theo MVP muc 4.2")
    void taxonomyCoversAllCategories() {
        assertThat(taxonomy.definitions()).hasSize(IssueCategory.values().length);
        for (IssueCategory category : IssueCategory.values()) {
            assertThat(taxonomy.find(category)).as(category.name()).isPresent();
        }
    }

    @Test
    @DisplayName("moi category co du 5 phan MVP muc 5.1 yeu cau")
    void everyDefinitionIsComplete() {
        for (IssueDefinition d : taxonomy.definitions()) {
            assertThat(d.definition()).as(d.id() + ".definition").isNotBlank();
            assertThat(d.knownAmbiguity()).as(d.id() + ".knownAmbiguity").isNotBlank();
            assertThat(d.detectionConditions()).as(d.id() + ".detectionConditions").isNotEmpty();
            assertThat(d.calibration()).as(d.id() + ".calibration").isNotNull();
            if (d.id() != IssueCategory.UNKNOWN) {
                assertThat(d.symptoms()).as(d.id() + ".symptoms").isNotEmpty();
                assertThat(d.requiredEvidence()).as(d.id() + ".requiredEvidence").isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("ba category khong co ca mau duoc danh dau UNVALIDATED")
    void categoriesWithoutSamplesAreMarkedUnvalidated() {
        // Data mau chi phu 2/6 category: SIGNALING_FAILURE va ICE_FAILURE.
        assertThat(taxonomy.unvalidatedCategories()).containsExactlyInAnyOrder(
                IssueCategory.TURN_FAILURE,
                IssueCategory.NETWORK_PACKET_LOSS,
                IssueCategory.NETWORK_DELAY_JITTER);
    }

    @Test
    @DisplayName("TURN_FAILURE ghi ro cam dem rieng dong loi allocate")
    void turnFailureWarnsAgainstCountingErrorLines() {
        String ambiguity = taxonomy.find(IssueCategory.TURN_FAILURE).orElseThrow().knownAmbiguity();
        assertThat(ambiguity).contains("401").contains("73");
    }

    @Test
    @DisplayName("report day du hop le theo schema v1")
    void validReportPassesSchema() {
        assertThat(validator.validate(sampleReport()).valid()).isTrue();
    }

    @Test
    @DisplayName("chi so thieu gia tri MA khong co ly do thi bi schema tu choi")
    void metricWithoutValueMustCarryReason() {
        CallReport invalid = new CallReport("CALL-1", Verdict.SUCCESS, false, null,
                ConfidenceLevel.HIGH, "tom tat", List.of(),
                List.of(new CallReport.MetricEntry("MOS", null, null, null, "ENDCALL")),
                new CallReport.PossibleCauses(null, List.of()), List.of(), List.of(), false);

        ReportSchemaValidator.Result result = validator.validate(invalid);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("verdict ngoai taxonomy bi tu choi (ca kiem thu G03 cua Sprint 2)")
    void verdictOutsideTaxonomyIsRejected() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        json.put("verdict", "MAYBE");

        assertThat(validator.validate(json).valid()).isFalse();
    }

    @Test
    @DisplayName("evidence phai co sourceRef de trace ve dong log goc")
    void evidenceMustCarrySourceRef() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        ((ObjectNode) json.withArray("evidence").get(0)).remove("sourceRef");

        assertThat(validator.validate(json).valid()).isFalse();
    }

    @Test
    @DisplayName("field la cho schema bi tu choi — chan AI tu them truong")
    void unknownFieldIsRejected() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        json.put("aiConfidenceScore", 0.93);

        assertThat(validator.validate(json).valid()).isFalse();
    }

    private static CallReport sampleReport() {
        return new CallReport(
                "2D9057AA-C496-48B2-946A-98FA2896D086",
                Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS, ConfidenceLevel.MEDIUM,
                "Cuoc goi thiet lap thanh cong nhung co dau hieu suy giam chat luong.",
                List.of(new CallReport.EvidenceEntry("EV01", "SIGNALING",
                        "2026-09-21T08:44:28.953756952Z", "Signaling INIT_CALL", "signaling.json:1")),
                List.of(
                        new CallReport.MetricEntry("Thoi gian thiet lap", "6774", "ms", null, "SIGNALING"),
                        new CallReport.MetricEntry("MOS (caller)", null, null,
                                "khong co end call log cua caller", "ENDCALL")),
                new CallReport.PossibleCauses("NETWORK_PACKET_LOSS", List.of("Diem mo ho: ...")),
                List.of("Kiem tra chat luong mang."),
                List.of("Thieu caller_webrtc.log."),
                false);
    }
}
