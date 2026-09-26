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
    @DisplayName("taxonomy.yaml khai báo đủ 6 category theo MVP mục 4.2")
    void taxonomyCoversAllCategories() {
        assertThat(taxonomy.definitions()).hasSize(IssueCategory.values().length);
        for (IssueCategory category : IssueCategory.values()) {
            assertThat(taxonomy.find(category)).as(category.name()).isPresent();
        }
    }

    @Test
    @DisplayName("mỗi category có đủ 5 phần MVP mục 5.1 yêu cầu")
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
    @DisplayName("ba category không có ca mẫu có nhãn được đánh dấu UNVALIDATED")
    void categoriesWithoutSamplesAreMarkedUnvalidated() {
        // Data có nhãn phủ 3/6 category: SIGNALING_FAILURE, ICE_FAILURE, TURN_FAILURE
        // (TURN: 3 ca CANCEL trong fail/ không cấp phát được relay nào).
        assertThat(taxonomy.unvalidatedCategories()).containsExactlyInAnyOrder(
                IssueCategory.NETWORK_PACKET_LOSS,
                IssueCategory.NETWORK_DELAY_JITTER,
                IssueCategory.UNKNOWN);
    }

    @Test
    @DisplayName("knownAmbiguity là cảnh báo chung: ngắn và không nhắc Call-ID cụ thể")
    void knownAmbiguityIsGenericForEveryCategory() {
        // ReportBuilder in knownAmbiguity vào MỌI report thuộc category đó. Nhắc tên một cuộc
        // gọi hay một mã lỗi riêng (vd. 428) sẽ đọc như nhận định về chính cuộc đang xem —
        // report của 311A9B6A từng in "Mã 428 privacy_restricted..." dù log không có mã 428.
        for (IssueDefinition d : taxonomy.definitions()) {
            assertThat(d.knownAmbiguity()).as(d.id() + ".knownAmbiguity")
                    .hasSizeLessThan(250)
                    .doesNotContainPattern("[0-9A-F]{8}")
                    .doesNotContain("428");
        }
    }

    @Test
    @DisplayName("TURN_FAILURE ghi rõ cấm đếm riêng dòng lỗi allocate")
    void turnFailureWarnsAgainstCountingErrorLines() {
        var turn = taxonomy.find(IssueCategory.TURN_FAILURE).orElseThrow();

        // knownAmbiguity in ra MỌI report nên phải ngắn: chỉ nêu cảnh báo.
        assertThat(turn.knownAmbiguity()).contains("401").hasSizeLessThan(250);

        // Con số chứng minh nằm ở calibration, chỗ dành cho chi tiết.
        assertThat(turn.calibration().evidence()).contains("73").contains("11/20");
    }

    @Test
    @DisplayName("report đầy đủ hợp lệ theo schema v1")
    void validReportPassesSchema() {
        assertThat(validator.validate(sampleReport()).valid()).isTrue();
    }

    @Test
    @DisplayName("chỉ số thiếu giá trị MÀ không có lý do thì bị schema từ chối")
    void metricWithoutValueMustCarryReason() {
        CallReport invalid = new CallReport("CALL-1", Verdict.SUCCESS, false, null,
                ConfidenceLevel.HIGH, "tom tat", List.of(),
                List.of(new CallReport.MetricEntry("MOS", null, null, null, "ENDCALL")),
                new CallReport.PossibleCauses(null, List.of()), List.of(), List.of(), false);

        ReportSchemaValidator.Result result = validator.validate(invalid);
        assertThat(result.valid()).isFalse();
    }

    @Test
    @DisplayName("verdict ngoài taxonomy bị từ chối (ca kiểm thử G03 của Sprint 2)")
    void verdictOutsideTaxonomyIsRejected() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        json.put("verdict", "MAYBE");

        assertThat(validator.validate(json).valid()).isFalse();
    }

    @Test
    @DisplayName("evidence phải có sourceRef để trace về dòng log gốc")
    void evidenceMustCarrySourceRef() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        ((ObjectNode) json.withArray("evidence").get(0)).remove("sourceRef");

        assertThat(validator.validate(json).valid()).isFalse();
    }

    @Test
    @DisplayName("field lạ cho schema bị từ chối — chặn AI tự thêm trường")
    void unknownFieldIsRejected() {
        ObjectNode json = (ObjectNode) new ObjectMapper().valueToTree(sampleReport());
        json.put("aiConfidenceScore", 0.93);

        assertThat(validator.validate(json).valid()).isFalse();
    }

    private static CallReport sampleReport() {
        return new CallReport(
                "2D9057AA-C496-48B2-946A-98FA2896D086",
                Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS, ConfidenceLevel.MEDIUM,
                "Cuộc gọi thiết lập thành công nhưng có dấu hiệu suy giảm chất lượng.",
                List.of(new CallReport.EvidenceEntry("EV01", "SIGNALING",
                        "2026-09-21T08:44:28.953756952Z", "Signaling INIT_CALL", "signaling#1")),
                List.of(
                        new CallReport.MetricEntry("Thời gian thiết lập", "6774", "ms", null, "SIGNALING"),
                        new CallReport.MetricEntry("MOS (caller)", null, null,
                                "không có end call log của caller", "ENDCALL")),
                new CallReport.PossibleCauses("NETWORK_PACKET_LOSS", List.of("Diem mo ho: ...")),
                List.of("Kiem tra chat luong mang."),
                List.of("Thieu caller_webrtc.log."),
                false);
    }
}
