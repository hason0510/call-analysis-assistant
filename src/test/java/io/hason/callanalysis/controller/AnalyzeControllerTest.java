package io.hason.callanalysis.controller;

import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.ChatAnalysisService;
import io.hason.callanalysis.service.ChatPipelineFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Chat API qua HTTP multipart (MVP mục 6.1 T2), AI giả "chưa cấu hình" → report degraded. */
class AnalyzeControllerTest {

    private MockMvc mvc(DataSize maxFileSize) {
        ChatAnalysisService chat = ChatPipelineFixture.chat(new ChatPipelineFixture.RecordingSource(), ctx -> {
            throw new AiUnavailableException(AiUnavailableException.Reason.NOT_CONFIGURED, "test");
        }, maxFileSize);
        return MockMvcBuilders.standaloneSetup(new AnalyzeController(chat, new CallFolderReader(maxFileSize))).build();
    }

    private static MockMultipartFile endCallLog(String name, String role) {
        return new MockMultipartFile("files", name, "text/plain",
                String.join("\n", ChatPipelineFixture.endCallLog(role)).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("câu hỏi + nhiều file -> 200, JSON report đã qua schema + bản render theo mẫu 4.5")
    void analyzesUploadedFiles() throws Exception {
        mvc(DataSize.ofMegabytes(20)).perform(multipart("/api/analyze")
                        .file(endCallLog("caller_endcall.log", "caller"))
                        .file(endCallLog("callee_endcall.log", "callee"))
                        .param("message", "Phân tích cuộc gọi này giúp mình"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REPORT"))
                .andExpect(jsonPath("$.report.callId").value(ChatPipelineFixture.CALL_ID))
                .andExpect(jsonPath("$.report.degraded").value(true))
                .andExpect(jsonPath("$.report.fallbackReason").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.rendered").value(org.hamcrest.Matchers.startsWith("# Báo cáo phân tích cuộc gọi")));
    }

    @Test
    @DisplayName("câu hỏi ngoài phạm vi -> 200, câu từ chối cố định, không có report")
    void outOfScope() throws Exception {
        mvc(DataSize.ofMegabytes(20)).perform(multipart("/api/analyze")
                        .file(endCallLog("caller_endcall.log", "caller"))
                        .param("message", "Thời tiết hôm nay thế nào?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OUT_OF_SCOPE"))
                .andExpect(jsonPath("$.report").doesNotExist());
    }

    @Test
    @DisplayName("không file, không Call-ID -> 400 kèm lời giải thích")
    void nothingToAnalyze() throws Exception {
        mvc(DataSize.ofMegabytes(20)).perform(multipart("/api/analyze").param("message", "Phân tích giúp mình"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(ChatAnalysisService.NOTHING_TO_ANALYZE));
    }

    @Test
    @DisplayName("F04 qua HTTP — file quá cỡ bị loại riêng và nêu tên, các file khác vẫn được phân tích")
    void oversizedFileIsRejectedAlone() throws Exception {
        MockMultipartFile big = new MockMultipartFile("files", "qua_lon.log", "text/plain", new byte[2048]);
        mvc(DataSize.ofKilobytes(1)).perform(multipart("/api/analyze")
                        .file(endCallLog("caller_endcall.log", "caller"))
                        .file(big)
                        .param("message", "Phân tích cuộc gọi này giúp mình"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REPORT"))
                .andExpect(jsonPath("$.report.dataLimitations[*]").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("qua_lon.log: dung lượng"))));
    }

    @Test
    @DisplayName("bố cục cho Web UI lấy từ chính ReportRenderer: đúng nhãn và thứ tự mẫu 4.5")
    void reportLayoutComesFromRenderer() throws Exception {
        mvc(DataSize.ofMegabytes(20)).perform(get("/api/report-layout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Báo cáo phân tích cuộc gọi"))
                .andExpect(jsonPath("$.header[0]").value("Call-ID"))
                .andExpect(jsonPath("$.header[4]").value("Tóm tắt"))
                .andExpect(jsonPath("$.sections[0]").value("Evidence chính"))
                .andExpect(jsonPath("$.sections[4]").value("Giới hạn dữ liệu"))
                .andExpect(jsonPath("$.maxBytesPerFile").value(20 * 1024 * 1024));
    }

    @Test
    @DisplayName("tên file kèm đường dẫn máy người dùng -> chỉ giữ tên file")
    void stripsClientPath() {
        assertThat(AnalyzeController.baseName("C:\\Users\\An\\Desktop\\caller_endcall.log")).isEqualTo("caller_endcall.log");
        assertThat(AnalyzeController.baseName("/home/an/callee_webrtc.log")).isEqualTo("callee_webrtc.log");
        assertThat(AnalyzeController.baseName(null)).isEqualTo("(không tên)");
    }
}
