package io.hason.callanalysis.domain.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Trích Call-ID bằng code và kiểm đầu ra AI của bước phân loại câu hỏi. */
class RequestModelTest {

    @Test
    @DisplayName("Call-ID trong câu hỏi: lấy dạng UUID, viết hoa như tên thư mục data mẫu")
    void extractsCallId() {
        assertThat(CallIdExtractor.extract("cuộc gọi de7dd314-f432-45cb-bcb4-ae9103cc0919 lỗi gì?").callId())
                .isEqualTo("DE7DD314-F432-45CB-BCB4-AE9103CC0919");
        assertThat(CallIdExtractor.extract("Phân tích giúp mình").callId()).isNull();
        // fromTag dạng "C8CF631E-0" không phải Call-ID
        assertThat(CallIdExtractor.extract("fromTag C8CF631E-0").callId()).isNull();
    }

    @Test
    @DisplayName("hai Call-ID khác nhau -> không đoán, nêu lý do ở giới hạn dữ liệu")
    void twoCallIdsAreAmbiguous() {
        CallIdExtractor.Result r = CallIdExtractor.extract(
                "So sánh DE7DD314-F432-45CB-BCB4-AE9103CC0919 với EE129C8F-EAD0-4302-AB68-920D32F8B8B7");

        assertThat(r.callId()).isNull();
        assertThat(r.note()).contains("2 Call-ID khác nhau");
    }

    @Test
    @DisplayName("cùng một Call-ID ghi hai lần (khác hoa thường) không phải mơ hồ")
    void sameCallIdTwiceIsFine() {
        assertThat(CallIdExtractor.extract("DE7DD314-F432-45CB-BCB4-AE9103CC0919 / de7dd314-f432-45cb-bcb4-ae9103cc0919")
                .callId()).isEqualTo("DE7DD314-F432-45CB-BCB4-AE9103CC0919");
    }

    @Test
    @DisplayName("AI: intent ngoài danh sách (kể cả viết thường) -> loại")
    void unknownIntentIsRejected() {
        assertThat(IntentClassification.fromAi(new AiIntent("WRITE_EMAIL", ""))).isEmpty();
        assertThat(IntentClassification.fromAi(new AiIntent("analyze_call", ""))).isEmpty();
        assertThat(IntentClassification.fromAi(new AiIntent(null, ""))).isEmpty();
    }

    @Test
    @DisplayName("AI: ANALYZE_WITH_FOCUS thiếu trọng tâm hoặc trọng tâm dài bất thường -> loại")
    void focusIsRequiredAndBounded() {
        assertThat(IntentClassification.fromAi(new AiIntent("ANALYZE_WITH_FOCUS", " "))).isEmpty();
        assertThat(IntentClassification.fromAi(new AiIntent("ANALYZE_WITH_FOCUS", "x".repeat(201)))).isEmpty();
        assertThat(IntentClassification.fromAi(new AiIntent("ANALYZE_WITH_FOCUS", " bên nhận không nghe được ")))
                .hasValue(new IntentClassification(Intent.ANALYZE_WITH_FOCUS, "bên nhận không nghe được"));
    }

    @Test
    @DisplayName("AI: intent khác ANALYZE_WITH_FOCUS mà kèm trọng tâm -> giữ intent, bỏ trọng tâm")
    void focusIsDroppedForOtherIntents() {
        assertThat(IntentClassification.fromAi(new AiIntent("OUT_OF_SCOPE", "email")))
                .hasValue(new IntentClassification(Intent.OUT_OF_SCOPE, null));
    }
}
