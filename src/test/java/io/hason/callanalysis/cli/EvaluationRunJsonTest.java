package io.hason.callanalysis.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.evaluation.EvaluationRun;
import io.hason.callanalysis.domain.evaluation.EvidenceCitation;
import io.hason.callanalysis.domain.evaluation.MetricMentions;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** --rescore đọc lại đúng thứ --evaluate đã ghi, bằng ObjectMapper cấu hình như Spring Boot. */
class EvaluationRunJsonTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("lần chạy ghi ra .json đọc lại được nguyên vẹn")
    void roundTrip() throws Exception {
        EvaluationRun run = new EvaluationRun("case-001", 2, "Vì sao [CONTACT_REDACTED] gọi lỗi?", 3,
                Intent.ANALYZE_WITH_FOCUS, null, "REPORT", Intent.ANALYZE_WITH_FOCUS, "AI", Verdict.FAIL, false,
                IssueCategory.TURN_FAILURE, "MEDIUM", Verdict.FAIL, IssueCategory.TURN_FAILURE, "FAIL", "TURN_FAILURE",
                "RULE", true, "GUARDRAIL_REJECTED", false, List.of("G05: số liệu không có trong input: 0.112"),
                true, true, 42, Map.of("ip", 0), Map.of("ip", 0), Map.of(), new TokenUsage(600, 12),
                new TokenUsage(3012, 171), new MetricMentions(3, 1),
                new EvidenceCitation(List.of("EV02", "EV05"), 14, List.of("EV05")), 3810);

        String json = mapper.writeValueAsString(List.of(run));
        List<EvaluationRun> back = mapper.readValue(json, new TypeReference<>() {});

        assertThat(back).containsExactly(run);
    }

    @Test
    @DisplayName("file của Sprint 2 (chưa có trường token, có trường thừa) vẫn đọc được")
    void readsOlderFiles() throws Exception {
        String sprint2 = """
                [{"caseId":"case-001","questionIndex":1,"question":"q","repeat":1,"expectedIntent":null,
                  "error":null,"type":"REPORT","intent":"ANALYZE_CALL","intentSource":"AI","verdict":"FAIL",
                  "qualityFlag":false,"issueCategory":"ICE_FAILURE","confidenceLevel":"MEDIUM","ruleVerdict":"FAIL",
                  "ruleIssueCategory":"ICE_FAILURE","aiVerdict":"FAIL","aiIssueCategory":"ICE_FAILURE",
                  "analysisSource":"AI","degraded":false,"fallbackReason":null,"needsReview":false,"violations":[],
                  "templateCompliant":true,"metricsMatchCalculator":true,"sensitiveValues":7,
                  "aiInputLeaks":{},"outputLeaks":{},"latencyMs":3846,"report":true}]
                """;

        List<EvaluationRun> back = mapper.readValue(sprint2, new TypeReference<>() {});

        assertThat(back).singleElement().satisfies(r -> {
            assertThat(r.outcomeKey()).isEqualTo("FAIL/ICE_FAILURE");
            assertThat(r.analysisTokens()).isNull();
            assertThat(r.metricMentions()).isNull();                // lượt trước T1a: chưa đo
            assertThat(r.evidenceCitation()).isNull();
            assertThat(r.latencyMs()).isEqualTo(3846);
        });
    }
}
