package io.hason.callanalysis.infrastructure.evaluation;

import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkCaseLoaderTest {

    private final BenchmarkCaseLoader loader = new BenchmarkCaseLoader();

    @Test
    @DisplayName("đúng nguyên văn mẫu MVP mục 6.3 — file held-out của mentor đọc được không cần sửa")
    void mvpSampleVerbatim() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                case_id: case-001
                call_id: CALL-EXAMPLE-001
                files: [caller_endcall.log, callee_endcall.log]
                questions:
                - "Phân tích cuộc gọi này giúp mình"
                - "Cuộc gọi này có lỗi gì không?"
                - "Vì sao bên nhận nghe bị rè?"
                expected_verdict: SUCCESS
                expected_quality_flag: true
                expected_issue_category: NETWORK_PACKET_LOSS
                expected_evidence: [EV002, EV005]
                split: dev
                """);

        assertThat(loaded.errors()).isEmpty();
        BenchmarkCase c = loaded.cases().getFirst();
        assertThat(c.caseId()).isEqualTo("case-001");
        assertThat(c.callId()).isEqualTo("CALL-EXAMPLE-001");
        assertThat(c.files()).containsExactly("caller_endcall.log", "callee_endcall.log");
        assertThat(c.questions()).extracting(BenchmarkCase.Question::text).hasSize(3)
                .first().isEqualTo("Phân tích cuộc gọi này giúp mình");
        assertThat(c.questions()).allMatch(q -> q.expectedIntent() == null);
        assertThat(c.expectedVerdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(c.expectedQualityFlag()).isTrue();
        assertThat(c.expectedIssueCategory()).isEqualTo(IssueCategory.NETWORK_PACKET_LOSS);
        assertThat(c.expectedEvidence()).containsExactly("EV002", "EV005");
        assertThat(c.split()).isEqualTo("dev");
        assertThat(c.categoryScored()).isTrue();
    }

    @Test
    @DisplayName("bộ case dev của repo: đọc hết, có ca đúng nguyên mẫu 6.3, đủ ≥ 5 câu OUT_OF_SCOPE (MVP mục 6.3)")
    void repoDevCases() {
        BenchmarkCaseLoader.Loaded loaded = loader.load(Path.of("benchmark/dev-cases.yaml"));

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.cases()).hasSizeGreaterThanOrEqualTo(13);
        // case-001 viết đúng nguyên mẫu: câu hỏi là chuỗi trần
        assertThat(loaded.cases().getFirst().questions()).allMatch(q -> q.expectedIntent() == null);
        assertThat(loaded.cases()).allMatch(c -> c.questions().size() >= 3 && c.questions().size() <= 5);
        assertThat(loaded.cases().stream().flatMap(c -> c.questions().stream())
                .filter(q -> q.expectedIntent() == Intent.OUT_OF_SCOPE)).hasSizeGreaterThanOrEqualTo(5);
        // Ground truth thiếu thì để trống, không tự đặt nhãn
        assertThat(loaded.cases()).allMatch(c -> c.expectedIssueCategory() == null && c.expectedEvidence() == null);
        assertThat(loaded.cases()).extracting(BenchmarkCase::expectedVerdict)
                .contains(Verdict.SUCCESS, Verdict.FAIL, Verdict.UNKNOWN, null);
    }

    @Test
    @DisplayName("danh sách case ở gốc file cũng đọc được")
    void topLevelList() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                - case_id: a
                  call_id: X
                  files: [caller_endcall.log]
                  questions: ["q"]
                - case_id: b
                  call_id: Y
                  files: [callee_endcall.log]
                  questions: ["q"]
                """);

        assertThat(loaded.cases()).extracting(BenchmarkCase::caseId).containsExactly("a", "b");
    }

    @Test
    @DisplayName("case sai bị loại và nêu lý do, case khác vẫn chạy")
    void badCaseIsReportedOthersKept() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                case_id: ok
                call_id: X
                files: [caller_endcall.log]
                questions: ["q"]
                ---
                case_id: verdict-sai
                call_id: X
                files: [caller_endcall.log]
                questions: ["q"]
                expected_verdict: PASS
                ---
                case_id: ok
                call_id: Y
                files: [caller_endcall.log]
                questions: ["q"]
                ---
                case_id: thieu-file
                call_id: X
                questions: ["q"]
                ---
                case_id: intent-sai
                call_id: X
                files: [caller_endcall.log]
                questions:
                  - text: "q"
                    expected_intent: CHITCHAT
                """);

        assertThat(loaded.cases()).extracting(BenchmarkCase::caseId).containsExactly("ok");
        assertThat(loaded.errors()).hasSize(4);
        assertThat(loaded.errors().get(0)).contains("verdict-sai", "expected_verdict 'PASS'");
        assertThat(loaded.errors().get(1)).contains("trùng");
        assertThat(loaded.errors().get(2)).contains("thieu-file", "files");
        assertThat(loaded.errors().get(3)).contains("intent-sai", "CHITCHAT");
    }

    @Test
    @DisplayName("đáp án gõ tay khác kiểu mẫu vẫn đọc được: enum chữ thường, một file không trong [ ], cờ trong ngoặc kép")
    void lenientGroundTruth() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                case_id: go-tay
                call_id: X
                files: caller_endcall.log
                questions:
                  - text: "q"
                    expected_intent: analyze_with_focus
                expected_verdict: fail
                expected_quality_flag: "False"
                expected_issue_category: " ice_failure "
                expected_evidence: EV002
                """);

        assertThat(loaded.errors()).isEmpty();
        BenchmarkCase c = loaded.cases().getFirst();
        assertThat(c.files()).containsExactly("caller_endcall.log");
        assertThat(c.questions().getFirst().expectedIntent()).isEqualTo(Intent.ANALYZE_WITH_FOCUS);
        assertThat(c.expectedVerdict()).isEqualTo(Verdict.FAIL);
        assertThat(c.expectedQualityFlag()).isFalse();
        assertThat(c.expectedIssueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(c.expectedEvidence()).containsExactly("EV002");
    }

    @Test
    @DisplayName("cờ chất lượng không phải true / false vẫn là lỗi của case")
    void qualityFlagMustBeBoolean() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                case_id: co-sai
                call_id: X
                files: [caller_endcall.log]
                questions: ["q"]
                expected_quality_flag: maybe
                """);

        assertThat(loaded.cases()).isEmpty();
        assertThat(loaded.errors()).singleElement().asString().contains("co-sai", "expected_quality_flag", "maybe");
    }

    @Test
    @DisplayName("files: [] hợp lệ (ca chỉ có Call-ID, không đính kèm file)")
    void emptyFileListAllowed() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("""
                case_id: chi-call-id
                call_id: X
                files: []
                questions: ["q"]
                expected_verdict: UNKNOWN
                """);

        assertThat(loaded.errors()).isEmpty();
        assertThat(loaded.cases().getFirst().files()).isEmpty();
    }

    @Test
    @DisplayName("YAML hỏng -> một dòng lỗi, không ném exception")
    void brokenYaml() {
        BenchmarkCaseLoader.Loaded loaded = loader.parse("case_id: [a\nfiles: :");

        assertThat(loaded.cases()).isEmpty();
        assertThat(loaded.errors()).singleElement().asString().startsWith("File case không phải YAML hợp lệ");
    }
}
