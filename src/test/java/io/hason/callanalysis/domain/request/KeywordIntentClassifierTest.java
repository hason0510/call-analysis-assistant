package io.hason.callanalysis.domain.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Đường lui từ khoá của Request Parser: đúng intent cả khi gõ không dấu, từ chối câu ngoài phạm vi. */
class KeywordIntentClassifierTest {

    private final KeywordIntentClassifier classifier = new KeywordIntentClassifier();

    @ParameterizedTest(name = "{1} -> {0}")
    @CsvSource(delimiter = '|', textBlock = """
            ANALYZE_CALL       | Phân tích cuộc gọi này giúp mình
            ANALYZE_CALL       | Cuộc gọi này có lỗi gì không?
            ANALYZE_CALL       | Kiểm tra giúp mình log này với
            ANALYZE_CALL       | Vì sao cuộc gọi thất bại?
            ANALYZE_CALL       | phan tich giup minh cuoc goi nay
            ANALYZE_CALL       | Cuộc gọi DE7DD314-F432-45CB-BCB4-AE9103CC0919 thế nào?
            ANALYZE_CALL       | Có vấn đề gì không bạn?
            ANALYZE_WITH_FOCUS | Vì sao bên nhận không nghe được?
            ANALYZE_WITH_FOCUS | Vì sao bên nhận nghe bị rè?
            ANALYZE_WITH_FOCUS | vi sao ben nhan khong nghe duoc
            ANALYZE_WITH_FOCUS | Sao đổ chuông lâu vậy?
            ANALYZE_WITH_FOCUS | Sao gọi mãi không ai bắt máy?
            ANALYZE_WITH_FOCUS | Cuộc gọi có bị mất gói không?
            ANALYZE_WITH_FOCUS | Tại sao cuộc gọi tự ngắt giữa chừng?
            ANALYZE_WITH_FOCUS | Jitter của caller có cao không?
            ANALYZE_WITH_FOCUS | ICE có kết nối được không?
            ANALYZE_WITH_FOCUS | Tiếng bị giật, trễ là do mạng à?
            OUT_OF_SCOPE       | Viết giúp mình một email
            OUT_OF_SCOPE       | Viết email báo lỗi cuộc gọi này cho sếp
            OUT_OF_SCOPE       | Dịch đoạn này sang tiếng Anh
            OUT_OF_SCOPE       | Thời tiết hôm nay thế nào?
            OUT_OF_SCOPE       | Làm bài thơ về cuộc gọi đi
            OUT_OF_SCOPE       | Giá vàng hôm nay bao nhiêu
            OUT_OF_SCOPE       | Viết code Python sắp xếp mảng
            OUT_OF_SCOPE       | Xin chào
            OUT_OF_SCOPE       | thoi tiet ha noi
            """)
    void classifiesIntent(Intent expected, String question) {
        assertThat(classifier.classify(question).intent()).isEqualTo(expected);
    }

    @Test
    @DisplayName("trọng tâm giữ chữ người dùng, bỏ lời dẫn và tiểu từ cuối (MVP mục 4.4)")
    void focusKeepsUserWords() {
        assertThat(classifier.classify("Vì sao bên nhận không nghe được?").focus()).isEqualTo("bên nhận không nghe được");
        assertThat(classifier.classify("Cho mình hỏi tại sao bên gọi bị rớt vậy?").focus()).isEqualTo("bên gọi bị rớt");
        assertThat(classifier.classify("vi sao ben nhan khong nghe duoc").focus()).isEqualTo("ben nhan khong nghe duoc");
        // Call-ID đã trích riêng, không lẫn vào trọng tâm
        assertThat(classifier.classify("Cho mình hỏi vì sao bên nhận không nghe được? Call DE7DD314-F432-45CB-BCB4-AE9103CC0919")
                .focus()).isEqualTo("bên nhận không nghe được");
        assertThat(classifier.classify("Cuộc gọi DE7DD314-F432-45CB-BCB4-AE9103CC0919 bị rớt vì sao?").focus())
                .isEqualTo("bị rớt vì sao");
    }

    @Test
    @DisplayName("câu hỏi trống (chỉ đính kèm log) -> ANALYZE_CALL")
    void blankQuestionAnalyzesCall() {
        assertThat(classifier.classify("  ").intent()).isEqualTo(Intent.ANALYZE_CALL);
        assertThat(classifier.classify(null).intent()).isEqualTo(Intent.ANALYZE_CALL);
    }

    @Test
    @DisplayName("khớp nguyên từ: 'ice' trong 'service', 'dịch' trong 'dịch vụ' không đổi kết luận")
    void matchesWholeWordsOnly() {
        assertThat(classifier.classify("service có lỗi không").intent()).isEqualTo(Intent.ANALYZE_CALL);
        assertThat(classifier.classify("Dịch vụ gọi điện có vấn đề gì không").intent()).isEqualTo(Intent.ANALYZE_CALL);
    }
}
