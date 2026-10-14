package io.hason.callanalysis.domain.request;

import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.infrastructure.evaluation.BenchmarkCaseLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GarbledTextTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
                        "Vì sao bên nh?n không nghe ???c?",           // Windows-1252 không có ậ, đ, ư, ợ -> "?"
            "Vi sao b?n nh?n kh?ng nghe d??c",            // --question của CLI
            "VÃ¬ sao bÃªn nháº­n khÃ´ng nghe",             // UTF-8 đọc như Windows-1252
            "Ä‘Æ°á»£c khÃ´ng"})
    @DisplayName("câu hỏng mã hoá -> phát hiện")
    void garbled(String text) {
        assertThat(GarbledText.isGarbled(text)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "Vì sao bên nhận không nghe được?",
            "Tại sao??? Cuộc gọi lỗi à??",               // dấu hỏi thật đứng cuối từ
            "Cuộc gọi này?Có lỗi không",                  // một "?" dính chữ: dưới ngưỡng
            "Mẹ gọi “Má” mà không được",                  // á trước dấu nháy đóng
            "Cuoc goi nay co van de gi khong",
            "MOS hai bên được bao nhiêu?",
            "Kiểm tra DE7DD314-F432-45CB-BCB4-AE9103CC0919 giúp mình",
            ""})
    @DisplayName("câu gõ đúng, có dấu hay không dấu -> không báo nhầm")
    void clean(String text) {
        assertThat(GarbledText.isGarbled(text)).isFalse();
    }

    @Test
    @DisplayName("ký tự thay thế U+FFFD (byte không phải UTF-8) -> phát hiện, dù chỉ một chỗ")
    void replacementCharacter() {
        String r = Character.toString(0xFFFD);       // viết bằng mã số, không viết ký tự vào mã nguồn
        assertThat(GarbledText.isGarbled("M" + r + "y b" + r + "n kia g?i l?i")).isTrue();   // ca demo 2026-10-06
        assertThat(GarbledText.isGarbled("Ph" + r + "n tích cuộc gọi")).isTrue();
    }

    @Test
    @DisplayName("chữ dạng tổ hợp NFD (macOS: chữ + dấu rời) -> kết quả như dạng dựng sẵn NFC")
    void decomposedTextBehavesLikeComposed() {
        String garbled = Normalizer.normalize("Vì sao bên nh?n không nghe ???c?", Normalizer.Form.NFD);
        String clean = Normalizer.normalize("Vì sao bên nhận không nghe được? Mẹ gọi “Má” mà", Normalizer.Form.NFD);

        assertThat(garbled).isNotEqualTo("Vì sao bên nh?n không nghe ???c?");   // đúng là đã tách dấu
        assertThat(GarbledText.isGarbled(garbled)).isTrue();
        assertThat(GarbledText.isGarbled(clean)).isFalse();
    }

    @Test
    @DisplayName("không câu nào trong bộ case của repo bị coi là hỏng mã hoá")
    void repoQuestionsAreClean() {
        BenchmarkCaseLoader loader = new BenchmarkCaseLoader();
        Stream<String> questions = Stream.of("benchmark/dev-cases.yaml", "benchmark/intent-check.yaml")
                .flatMap(f -> loader.load(Path.of(f)).cases().stream())
                .flatMap(c -> c.questions().stream())
                .map(BenchmarkCase.Question::text);

        assertThat(questions).isNotEmpty().noneMatch(GarbledText::isGarbled);
    }
}
