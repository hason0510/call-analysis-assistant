package io.hason.callanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.evaluation.EvaluationRun;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.service.port.AiAnalyzer;
import io.hason.callanalysis.service.port.SignalingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.hason.callanalysis.service.ChatPipelineFixture.CALL_ID;
import static org.assertj.core.api.Assertions.assertThat;

/** Evaluation Runner chạy qua đúng luồng Chat API, với AI giả và signaling giả. */
class EvaluationServiceTest {

    private final ChatPipelineFixture.RecordingSource signaling = new ChatPipelineFixture.RecordingSource();

    private static final AiAnalyzer AGREEING_AI = ctx -> {
        AiContext.RuleSummary rule = ctx.payload().ruleVerdict();
        return new AiAnalysis(rule.verdict(), rule.qualityFlag(), rule.issueCategory(), "MEDIUM",
                "Tóm tắt.", List.of(ctx.evidenceIds().getFirst()), "Phân tích.", List.of("Đề xuất."));
    };

    private static List<AttachedFile> bothEndCallLogs() {
        return List.of(AttachedFile.of("caller_endcall.log", ChatPipelineFixture.endCallLog("caller")),
                AttachedFile.of("callee_endcall.log", ChatPipelineFixture.endCallLog("callee")));
    }

    private EvaluationService service(SignalingSource source, AiAnalyzer ai) {
        return new EvaluationService(ChatPipelineFixture.chat(source, ai), new ObjectMapper());
    }

    private static BenchmarkCase caseOf(BenchmarkCase.Question... questions) {
        return new BenchmarkCase("c1", CALL_ID, List.of("caller_endcall.log", "callee_endcall.log"),
                List.of(questions), Verdict.SUCCESS, null, null, null, "dev");
    }

    @Test
    @DisplayName("mỗi câu chạy đủ số lần lặp, thứ tự tất định; ghi rule, AI, template, bảng chỉ số")
    void runsEveryQuestionRepeatTimes() {
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", Intent.ANALYZE_CALL),
                new BenchmarkCase.Question("Cuộc gọi này có lỗi gì không?", null));

        List<EvaluationRun> runs = service(signaling, AGREEING_AI).runCase(c, bothEndCallLogs(), List.of(), 3);

        assertThat(runs).hasSize(6);
        assertThat(runs).extracting(r -> r.questionIndex() + "/" + r.repeat())
                .containsExactly("1/1", "1/2", "1/3", "2/1", "2/2", "2/3");
        EvaluationRun first = runs.getFirst();
        assertThat(first.succeeded()).isTrue();
        assertThat(first.type()).isEqualTo(EvaluationRun.REPORT);
        assertThat(first.intent()).isEqualTo(Intent.ANALYZE_CALL);
        assertThat(first.intentSource()).isEqualTo("KEYWORD");
        assertThat(first.ruleVerdict()).isNotNull();
        assertThat(first.aiVerdict()).isEqualTo(first.ruleVerdict().name());
        assertThat(first.analysisSource()).isEqualTo("AI");
        assertThat(first.templateCompliant()).isTrue();
        assertThat(first.metricsMatchCalculator()).isTrue();
    }

    @Test
    @DisplayName("câu OUT_OF_SCOPE: ghi câu từ chối, pipeline không chạy")
    void outOfScopeQuestion() {
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Viết giúp mình một email", Intent.OUT_OF_SCOPE));

        EvaluationRun run = service(signaling, AGREEING_AI).runCase(c, bothEndCallLogs(), List.of(), 1).getFirst();

        assertThat(run.type()).isEqualTo(EvaluationRun.OUT_OF_SCOPE);
        assertThat(run.verdict()).isNull();
        assertThat(run.templateCompliant()).isNull();
        assertThat(signaling.queried).isEmpty();
    }

    @Test
    @DisplayName("AI không có -> report degraded, verdict AI trống, vẫn ra report hợp lệ")
    void aiUnavailableIsDegraded() {
        AiAnalyzer down = ctx -> {
            throw new AiUnavailableException(AiUnavailableException.Reason.TIMEOUT, "test");
        };
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", null));

        EvaluationRun run = service(signaling, down).runCase(c, bothEndCallLogs(), List.of(), 1).getFirst();

        assertThat(run.degraded()).isTrue();
        assertThat(run.fallbackReason()).isEqualTo("TIMEOUT");
        assertThat(run.aiVerdict()).isNull();
        assertThat(run.templateCompliant()).isTrue();
    }

    @Test
    @DisplayName("call_id của case tới pipeline như Call-ID người dùng ghi: không file nào vẫn lấy được signaling")
    void caseCallIdReachesPipeline() {
        BenchmarkCase c = new BenchmarkCase("c1", CALL_ID, List.of(),
                List.of(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", null)),
                Verdict.UNKNOWN, null, null, null, "dev");

        EvaluationRun run = service(signaling, AGREEING_AI).runCase(c, List.of(), List.of(), 1).getFirst();

        assertThat(run.type()).isEqualTo(EvaluationRun.REPORT);
        assertThat(signaling.queried).containsExactly(CALL_ID);
        assertThat(run.verdict()).isEqualTo(Verdict.UNKNOWN);       // signaling tốt nhưng không có log client
    }

    @Test
    @DisplayName("Call-ID ghi trong câu hỏi được ưu tiên hơn call_id của case")
    void callIdInQuestionWins() {
        String other = "DE7DD314-F432-45CB-BCB4-AE9103CC0919";
        BenchmarkCase c = new BenchmarkCase("c1", CALL_ID, List.of(),
                List.of(new BenchmarkCase.Question("Cuộc gọi " + other + " thế nào?", null)),
                null, null, null, null, "dev");

        service(signaling, AGREEING_AI).runCase(c, List.of(), List.of(), 1);

        assertThat(signaling.queried).containsExactly(other);
    }

    @Test
    @DisplayName("Security Leakage: định danh gốc trong signaling không tới AI, không ra phản hồi")
    void leakageMeasuredPerRun() {
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", null));

        EvaluationRun run = service(signaling, AGREEING_AI)
                .runCase(c, bothEndCallLogs(), ChatPipelineFixture.healthySignaling(CALL_ID), 1).getFirst();

        assertThat(run.sensitiveValues()).isPositive();             // UCALLERUSER1, UCALLEEUSER2
        assertThat(run.aiInputLeaks()).isEmpty();
        assertThat(run.outputLeaks()).isEmpty();
    }

    @Test
    @DisplayName("token của lời gọi phân tích tới kết quả lần chạy, qua cả bước làm sạch đầu ra AI")
    void analysisTokensRecorded() {
        AiAnalyzer withUsage = ctx -> AGREEING_AI.analyze(ctx).withUsage(new TokenUsage(2962, 175));
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", null));

        EvaluationRun run = service(signaling, withUsage).runCase(c, bothEndCallLogs(), List.of(), 1).getFirst();

        assertThat(run.analysisTokens()).isEqualTo(new TokenUsage(2962, 175));
        assertThat(run.intentTokens()).isNull();                    // phân loại bằng từ khoá: không gọi AI
    }

    @Test
    @DisplayName("pipeline ném lỗi -> ghi tên lỗi, lượt đánh giá chạy tiếp")
    void pipelineErrorIsRecorded() {
        // AnalyzeCallService bắt lỗi signaling; lỗi lọt ra ngoài phải đến từ chỗ khác — ở đây là file null.
        BenchmarkCase c = caseOf(new BenchmarkCase.Question("Phân tích cuộc gọi này giúp mình", null),
                new BenchmarkCase.Question("Cuộc gọi này có lỗi gì không?", null));

        List<EvaluationRun> runs = service(signaling, AGREEING_AI).runCase(c, null, List.of(), 1);

        assertThat(runs).hasSize(2).allMatch(r -> !r.succeeded());
        assertThat(runs.getFirst().error()).isEqualTo("NullPointerException");
        assertThat(runs.getFirst().outcomeKey()).isEqualTo("ERROR");
    }
}
