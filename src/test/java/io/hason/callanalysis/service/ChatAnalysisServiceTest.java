package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.service.ChatAnalysisService.Response;
import io.hason.callanalysis.service.ChatAnalysisService.Type;
import io.hason.callanalysis.service.port.AiAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.hason.callanalysis.service.ChatPipelineFixture.CALL_ID;
import static org.assertj.core.api.Assertions.assertThat;

/** Cả luồng Chat API (MVP mục 6.1 T2) với AI giả: mọi nhánh đều ra kết quả có cấu trúc, report hợp lệ. */
class ChatAnalysisServiceTest {

    private final ChatPipelineFixture.RecordingSource signaling = new ChatPipelineFixture.RecordingSource();
    private final List<AiContext> sentToAi = new ArrayList<>();

    /** AI "đồng ý với rule": chép kết luận của rule trong context, trích evidence đầu tiên. */
    private final AiAnalyzer agreeingAi = ctx -> {
        sentToAi.add(ctx);
        AiContext.RuleSummary rule = ctx.payload().ruleVerdict();
        return new AiAnalysis(rule.verdict(), rule.qualityFlag(), rule.issueCategory(), "MEDIUM",
                "Tóm tắt do AI viết.", List.of(ctx.evidenceIds().getFirst()), "Phân tích do AI viết.",
                List.of("Đề xuất do AI viết."));
    };

    private static List<AttachedFile> bothEndCallLogs() {
        return List.of(AttachedFile.of("caller_endcall.log", ChatPipelineFixture.endCallLog("caller")),
                AttachedFile.of("callee_endcall.log", ChatPipelineFixture.endCallLog("callee")));
    }

    private Response ask(String question, List<AttachedFile> files, AiAnalyzer ai) {
        return ChatPipelineFixture.chat(signaling, ai).handle(question, files);
    }

    @Test
    @DisplayName("OUT_OF_SCOPE -> câu từ chối cố định, KHÔNG chạy pipeline dù có đính kèm log")
    void outOfScopeStopsBeforePipeline() {
        Response r = ask("Viết giúp mình một email", bothEndCallLogs(), agreeingAi);

        assertThat(r.type()).isEqualTo(Type.OUT_OF_SCOPE);
        assertThat(r.message()).isEqualTo(ParsedRequest.OUT_OF_SCOPE_REPLY);
        assertThat(r.report()).isNull();
        assertThat(signaling.queried).isEmpty();
        assertThat(sentToAi).isEmpty();
    }

    @Test
    @DisplayName("không file, không Call-ID -> INVALID_REQUEST, nói rõ cần gì")
    void nothingToAnalyze() {
        Response r = ask("Phân tích cuộc gọi này giúp mình", List.of(), agreeingAi);

        assertThat(r.type()).isEqualTo(Type.INVALID_REQUEST);
        assertThat(r.message()).isEqualTo(ChatAnalysisService.NOTHING_TO_ANALYZE);
    }

    @Test
    @DisplayName("AI dùng được -> report có chữ của AI, render đủ sáu mục theo mẫu 4.5")
    void aiReport() {
        Response r = ask("Phân tích cuộc gọi này giúp mình", bothEndCallLogs(), agreeingAi);

        assertThat(r.type()).isEqualTo(Type.REPORT);
        CallReport report = r.report();
        assertThat(report.callId()).isEqualTo(CALL_ID);                       // lấy từ end call log
        assertThat(report.analysisSource()).isEqualTo(CallReport.AnalysisSource.AI);
        assertThat(report.summary()).isEqualTo("Tóm tắt do AI viết.");
        assertThat(report.degraded()).isFalse();
        assertThat(r.rendered()).startsWith("# Báo cáo phân tích cuộc gọi\nCall-ID: " + CALL_ID)
                .contains("- Phân tích: Phân tích do AI viết.");
        assertThat(r.rendered().lines().filter(l -> l.startsWith("## ")).toList())
                .containsExactlyElementsOf(ReportRenderer.SECTIONS);
    }

    @Test
    @DisplayName("AI timeout -> vẫn có report (degraded, từ rule), không lỗi request")
    void aiFailureIsDegraded() {
        Response r = ask("Phân tích cuộc gọi này giúp mình", bothEndCallLogs(), ctx -> {
            throw new AiUnavailableException(AiUnavailableException.Reason.TIMEOUT, "x");
        });

        assertThat(r.type()).isEqualTo(Type.REPORT);
        assertThat(r.report().degraded()).isTrue();
        assertThat(r.report().fallbackReason()).isEqualTo("TIMEOUT");
        assertThat(r.report().analysis()).isNull();
        assertThat(r.report().confidenceLevel()).isNotEqualTo(ConfidenceLevel.HIGH);
        assertThat(r.rendered()).contains("(TIMEOUT)");
    }

    @Test
    @DisplayName("AI lệch rule -> UNKNOWN, cần kiểm tra, độ tin cậy LOW")
    void disagreementIsFlagged() {
        Response r = ask("Phân tích cuộc gọi này giúp mình", bothEndCallLogs(), ctx -> {
            String opposite = "FAIL".equals(ctx.payload().ruleVerdict().verdict()) ? "SUCCESS" : "FAIL";
            return new AiAnalysis(opposite, false, "SUCCESS".equals(opposite) ? "NONE" : "ICE_FAILURE", "HIGH",
                    "Ngược với rule.", List.of(ctx.evidenceIds().getFirst()), "Lý do của AI.", List.of());
        });

        assertThat(r.report().verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(r.report().needsReview()).isTrue();
        assertThat(r.report().confidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("Call-ID ghi trong câu hỏi, không đính kèm file -> vẫn lấy signaling theo Call-ID đó")
    void callIdFromQuestion() {
        Response r = ask("Phân tích cuộc gọi " + CALL_ID.toLowerCase() + " giúp mình", List.of(), agreeingAi);

        assertThat(r.type()).isEqualTo(Type.REPORT);
        assertThat(signaling.queried).containsExactly(CALL_ID);
        assertThat(r.report().callId()).isEqualTo(CALL_ID);
    }

    @Test
    @DisplayName("scenario 6 — SĐT / JWT trong câu hỏi và trong chữ AI không tới AI, không ra report")
    void sensitiveValuesNeverLeak() {
        String phone = "0912345678";
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
        Response r = ask("Máy " + phone + " gọi lỗi, token " + jwt + ", phân tích giúp", bothEndCallLogs(), ctx -> {
            sentToAi.add(ctx);
            AiContext.RuleSummary rule = ctx.payload().ruleVerdict();
            return new AiAnalysis(rule.verdict(), rule.qualityFlag(), rule.issueCategory(), "MEDIUM",
                    "Gọi lại số " + phone + " để kiểm tra.", List.of(ctx.evidenceIds().getFirst()),
                    "Token " + jwt + " hết hạn.", List.of());
        });

        assertThat(sentToAi).singleElement().satisfies(ctx -> assertThat(ctx.question())
                .doesNotContain(phone).doesNotContain(jwt));
        assertThat(r.rendered()).doesNotContain(phone).doesNotContain("eyJ");
    }

    @Test
    @DisplayName("file hỏng / rỗng bị loại riêng, nêu tên ở giới hạn dữ liệu; phân tích vẫn chạy")
    void badFileIsNamedNotFatal() {
        List<AttachedFile> files = new ArrayList<>(bothEndCallLogs());
        files.add(AttachedFile.of("trong.log", List.of("", "  ")));
        Response r = ask("Phân tích cuộc gọi này giúp mình", files, agreeingAi);

        assertThat(r.type()).isEqualTo(Type.REPORT);
        assertThat(r.report().dataLimitations()).anyMatch(l -> l.startsWith("trong.log: file rỗng"));
    }
}
