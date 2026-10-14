package io.hason.callanalysis.infrastructure.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiPromptFingerprintTest {

    @Test
    @DisplayName("đủ 4 file quyết định cách AI trả lời, theo thứ tự cố định, mỗi dấu 12 ký tự hex")
    void coversPromptsAndSchemas() {
        assertThat(AiPromptFingerprint.current().keySet()).containsExactly(
                "analyze-call.system.txt", "ai-analysis.schema.json",
                "parse-request.system.txt", "request-intent.schema.json");
        assertThat(AiPromptFingerprint.current().values()).allMatch(h -> h.matches("[0-9a-f]{12}"));
    }

    @Test
    @DisplayName("SHA-256 chuẩn: cùng nội dung cùng dấu, đổi một ký tự là đổi dấu")
    void hashIsSha256Prefix() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(AiPromptFingerprint.of("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo("ba7816bf8f01");
        assertThat(AiPromptFingerprint.of("abd".getBytes(StandardCharsets.UTF_8))).isNotEqualTo("ba7816bf8f01");
    }

    @Test
    @DisplayName("dòng tóm tắt cho báo cáo liệt kê đủ 4 file")
    void summaryListsAllFiles() {
        assertThat(AiPromptFingerprint.summary())
                .startsWith("analyze-call.system.txt ")
                .contains(", ai-analysis.schema.json ", ", parse-request.system.txt ", ", request-intent.schema.json ");
    }
}
