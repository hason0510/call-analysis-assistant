package io.hason.callanalysis.domain.batch;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.hason.callanalysis.domain.batch.SourceGroup.SIGNALING_ONLY;
import static io.hason.callanalysis.domain.batch.SourceGroup.WITH_CLIENT_LOGS;
import static org.assertj.core.api.Assertions.assertThat;

/** Thống kê T10: đếm tay trên 6 kết quả, tách đúng theo nguồn dữ liệu. */
class BatchSummaryTest {

    static BatchResult result(String id, SourceGroup sourceGroup, Verdict verdict, IssueCategory category,
                              boolean flag, ConfidenceLevel confidence) {
        return new BatchResult(id, sourceGroup, verdict, category, flag, confidence, 3);
    }

    /** Giống lượt thật: chỉ signaling thì FAIL hoặc UNKNOWN; có log máy thì đủ loại. */
    static final List<BatchResult> SAMPLE = List.of(
            result("A", SIGNALING_ONLY, Verdict.FAIL, IssueCategory.SIGNALING_FAILURE, false, ConfidenceLevel.MEDIUM),
            result("B", SIGNALING_ONLY, Verdict.UNKNOWN, IssueCategory.UNKNOWN, false, ConfidenceLevel.LOW),
            result("C", WITH_CLIENT_LOGS, Verdict.SUCCESS, null, false, ConfidenceLevel.HIGH),
            result("D", WITH_CLIENT_LOGS, Verdict.SUCCESS, IssueCategory.NETWORK_PACKET_LOSS, true, ConfidenceLevel.LOW),
            result("E", WITH_CLIENT_LOGS, Verdict.FAIL, IssueCategory.TURN_FAILURE, false, ConfidenceLevel.MEDIUM),
            result("F", WITH_CLIENT_LOGS, Verdict.FAIL, IssueCategory.TURN_FAILURE, false, ConfidenceLevel.MEDIUM));

    @Test
    @DisplayName("đếm verdict / độ tin cậy cho cả lượt và từng nhóm nguồn dữ liệu")
    void countsPerGroup() {
        BatchSummary s = BatchSummary.of(SAMPLE);

        assertThat(s.all().calls()).isEqualTo(6);
        assertThat(s.all().verdicts()).containsEntry(Verdict.SUCCESS, 2).containsEntry(Verdict.FAIL, 3)
                .containsEntry(Verdict.UNKNOWN, 1);
        assertThat(s.groups().get(SIGNALING_ONLY).calls()).isEqualTo(2);
        assertThat(s.groups().get(SIGNALING_ONLY).verdict(Verdict.SUCCESS)).isZero();
        assertThat(s.groups().get(WITH_CLIENT_LOGS).verdict(Verdict.SUCCESS)).isEqualTo(2);
        assertThat(s.all().confidence()).containsEntry(ConfidenceLevel.HIGH, 1).containsEntry(ConfidenceLevel.MEDIUM, 3)
                .containsEntry(ConfidenceLevel.LOW, 2);
        assertThat(s.all().qualityFlagged()).isEqualTo(1);
    }

    @Test
    @DisplayName("loại lỗi chỉ đếm FAIL và SUCCESS có cờ — UNKNOWN và SUCCESS bình thường không có loại lỗi")
    void categoriesOnlyForRealIssues() {
        BatchSummary s = BatchSummary.of(SAMPLE);

        assertThat(s.all().categories()).containsEntry(IssueCategory.TURN_FAILURE, 2)
                .containsEntry(IssueCategory.SIGNALING_FAILURE, 1)
                .containsEntry(IssueCategory.NETWORK_PACKET_LOSS, 1)
                .containsEntry(IssueCategory.UNKNOWN, 0);
        assertThat(s.groups().get(SIGNALING_ONLY).categories()).containsEntry(IssueCategory.SIGNALING_FAILURE, 1)
                .containsEntry(IssueCategory.TURN_FAILURE, 0);
    }

    @Test
    @DisplayName("tỉ lệ UNKNOWN tính riêng từng nhóm; nhóm rỗng vẫn có mặt với 0, không chia cho 0")
    void unknownRateAndEmptyGroup() {
        BatchSummary s = BatchSummary.of(SAMPLE);
        BatchSummary onlySignaling = BatchSummary.of(SAMPLE.subList(0, 2));

        assertThat(s.groups().get(SIGNALING_ONLY).unknownRate()).isEqualTo(0.5);
        assertThat(s.groups().get(WITH_CLIENT_LOGS).unknownRate()).isZero();
        assertThat(onlySignaling.groups().get(WITH_CLIENT_LOGS).calls()).isZero();
        assertThat(onlySignaling.groups().get(WITH_CLIENT_LOGS).unknownRate()).isZero();
        assertThat(onlySignaling.groups().get(WITH_CLIENT_LOGS).verdicts()).containsKeys(Verdict.values());
    }

    @Test
    @DisplayName("map theo thứ tự khai báo enum — thứ tự dòng của báo cáo cố định giữa các lượt")
    void deterministicOrder() {
        BatchSummary s = BatchSummary.of(SAMPLE);

        assertThat(s.groups().keySet()).containsExactly(SourceGroup.values());
        assertThat(s.all().verdicts().keySet()).containsExactly(Verdict.values());
        assertThat(s.all().categories().keySet()).containsExactly(IssueCategory.values());
    }
}
