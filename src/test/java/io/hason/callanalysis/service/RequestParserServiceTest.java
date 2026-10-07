package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.request.AiIntent;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.domain.request.ParsedRequest.Source;
import io.hason.callanalysis.domain.request.SafeQuestion;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import io.hason.callanalysis.service.port.IntentClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Request Parser (MVP mục 6.1 T4): AI trên câu hỏi đã làm sạch, lùi về từ khoá khi AI lỗi. */
class RequestParserServiceTest {

    private static final SensitiveDataSanitizer SANITIZER = new SensitiveDataSanitizer(
            new SensitiveDataInventoryLoader().load(), Pseudonymizer.withRandomKey());

    /** Ghi lại câu hỏi AI nhận được, để kiểm nó đã sạch. */
    private final List<String> sentToAi = new ArrayList<>();

    private ParsedRequest parse(String question, IntentClassifier ai) {
        IntentClassifier recording = (id, q) -> {
            sentToAi.add(q.text());
            return ai.classify(id, q);
        };
        return new RequestParserService(recording, SANITIZER).parse("req-1", question);
    }

    @Test
    @DisplayName("token của lời gọi phân loại tới ParsedRequest — kể cả khi câu trả lời AI bị loại")
    void usageIsKept() {
        TokenUsage tokens = new TokenUsage(654, 13);

        ParsedRequest ok = parse("Vì sao bên nhận không nghe được?",
                (id, q) -> new AiIntent("ANALYZE_WITH_FOCUS", "bên nhận không nghe được", tokens));
        ParsedRequest invalid = parse("Vì sao bên nhận không nghe được?",
                (id, q) -> new AiIntent("CHITCHAT", "", tokens));

        assertThat(ok.usage()).isEqualTo(tokens);
        assertThat(invalid.source()).isEqualTo(ParsedRequest.Source.KEYWORD);
        assertThat(invalid.usage()).isEqualTo(tokens);
    }

    @Test
    @DisplayName("AI hợp lệ -> dùng intent và trọng tâm của AI")
    void validAiAnswerIsUsed() {
        ParsedRequest r = parse("Vì sao bên nhận không nghe được?",
                (id, q) -> new AiIntent("ANALYZE_WITH_FOCUS", "bên nhận không nghe được"));

        assertThat(r.source()).isEqualTo(Source.AI);
        assertThat(r.intent()).isEqualTo(Intent.ANALYZE_WITH_FOCUS);
        assertThat(r.focus()).isEqualTo("bên nhận không nghe được");
        assertThat(r.fallbackReason()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Reason.class)
    @DisplayName("AI không dùng được -> lùi về từ khoá, ghi lý do, pipeline vẫn chạy")
    void aiUnavailableFallsBackToKeywords(Reason reason) {
        ParsedRequest r = parse("Viết giúp mình một email", (id, q) -> { throw new AiUnavailableException(reason, "x"); });

        assertThat(r.source()).isEqualTo(Source.KEYWORD);
        assertThat(r.fallbackReason()).isEqualTo(reason.name());
        assertThat(r.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("adapter ném lỗi không lường trước -> vẫn lùi về từ khoá")
    void unexpectedExceptionFallsBack() {
        ParsedRequest r = parse("Phân tích cuộc gọi này giúp mình", (id, q) -> { throw new IllegalStateException("bug"); });

        assertThat(r.fallbackReason()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(r.intent()).isEqualTo(Intent.ANALYZE_CALL);
    }

    @Test
    @DisplayName("AI trả intent ngoài danh sách / thiếu trọng tâm -> INVALID_INTENT, lùi về từ khoá")
    void invalidAiAnswerFallsBack() {
        assertThat(parse("Vì sao bên nhận không nghe được?", (id, q) -> new AiIntent("WRITE_EMAIL", "")).fallbackReason())
                .isEqualTo("INVALID_INTENT");
        ParsedRequest r = parse("Vì sao bên nhận không nghe được?", (id, q) -> new AiIntent("ANALYZE_WITH_FOCUS", ""));
        assertThat(r.fallbackReason()).isEqualTo("INVALID_INTENT");
        assertThat(r.intent()).isEqualTo(Intent.ANALYZE_WITH_FOCUS);       // từ khoá vẫn nhận ra trọng tâm
        assertThat(r.focus()).isEqualTo("bên nhận không nghe được");
    }

    @Test
    @DisplayName("S04/S01 — câu hỏi chứa SĐT, JWT, IP: AI chỉ nhận bản đã che; bản chuyển tiếp cũng đã che")
    void questionIsSanitizedBeforeAi() {
        String question = "Máy 0912345678 (IP 203.0.113.7) gọi lỗi, token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln";
        ParsedRequest r = parse(question, (id, q) -> new AiIntent("ANALYZE_CALL", ""));

        assertThat(sentToAi).singleElement().satisfies(sent -> assertThat(sent)
                .doesNotContain("0912345678").doesNotContain("203.0.113.7").doesNotContain("eyJ"));
        assertThat(r.question()).doesNotContain("0912345678").doesNotContain("203.0.113.7").doesNotContain("eyJ");
    }

    @Test
    @DisplayName("S07 — trọng tâm AI trả về chứa giá trị nhạy cảm -> được che trước khi dùng")
    void aiFocusIsSanitized() {
        ParsedRequest r = parse("Vì sao bên nhận không nghe được?",
                (id, q) -> new AiIntent("ANALYZE_WITH_FOCUS", "bên nhận 0912345678 không nghe được"));

        assertThat(r.focus()).doesNotContain("0912345678").contains("không nghe được");
    }

    @Test
    @DisplayName("Call-ID trích bằng code, không phụ thuộc AI — có cả khi AI lỗi")
    void callIdIsExtractedByCode() {
        ParsedRequest r = parse("Cuộc gọi de7dd314-f432-45cb-bcb4-ae9103cc0919 có lỗi gì?",
                (id, q) -> { throw new AiUnavailableException(Reason.TIMEOUT, "x"); });

        assertThat(r.callId()).isEqualTo("DE7DD314-F432-45CB-BCB4-AE9103CC0919");
        assertThat(r.intent()).isEqualTo(Intent.ANALYZE_CALL);
    }

    @Test
    @DisplayName("câu hỏi trống (chỉ đính kèm log) -> ANALYZE_CALL, không gọi AI")
    void blankQuestionSkipsAi() {
        ParsedRequest r = parse("   ", (id, q) -> { throw new AssertionError("không được gọi AI"); });

        assertThat(r.intent()).isEqualTo(Intent.ANALYZE_CALL);
        assertThat(r.fallbackReason()).isNull();
        assertThat(sentToAi).isEmpty();
    }

}
