package io.hason.callanalysis.controller;

import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.ChatAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Chat API (MVP mục 6.1 T2): câu hỏi + nhiều file log (multipart) → report.
 *
 *   POST /api/analyze   message=<câu hỏi>   files=<file 1>   files=<file 2> …
 *
 * Adapter vào, chỉ làm việc cơ học: đọc file tải lên thành {@link AttachedFile} rồi giao cho
 * {@link ChatAnalysisService}. Loại file, Call-ID, file hỏng… là việc của File Validator.
 */
@RestController
public class AnalyzeController {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeController.class);

    static final String TOO_MANY_FILES = "TOO_MANY_FILES";

    private final ChatAnalysisService chat;
    private final long maxBytesPerFile;
    private final int maxFilesPerRequest;

    public AnalyzeController(ChatAnalysisService chat, CallFolderReader reader,
                             @Value("${call-analysis.files.max-files-per-request:10}") int maxFilesPerRequest) {
        this.chat = chat;
        this.maxBytesPerFile = reader.maxBytesPerFile();
        this.maxFilesPerRequest = maxFilesPerRequest;
    }

    @PostMapping(path = "/api/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyze(
            @RequestParam(name = "message", required = false) String message,
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        List<MultipartFile> real = files == null ? List.of()
                : files.stream().filter(AnalyzeController::isRealFile).toList();
        if (real.size() > maxFilesPerRequest) {
            return tooManyFiles(real.size());
        }
        List<AttachedFile> attached = real.stream().map(this::toAttached).toList();
        ChatAnalysisService.Response response = chat.handle(message, attached);
        HttpStatus status = response.type() == ChatAnalysisService.Type.INVALID_REQUEST
                ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Quá số file cho phép: từ chối cả request TRƯỚC khi đọc nội dung file và trước lời gọi AI phân loại câu hỏi, nên
     * request kiểu này không tốn bộ nhớ hay tiền AI. Một cuộc gọi thường có 4 file; leg có nhiều thiết bị thì thêm 2 file
     * mỗi máy (dataset mới: 6 file), nên giới hạn mặc định 10 vẫn dư. Không có report nên không ghi bản ghi Kibana; dòng
     * log dưới đây là dấu vết duy nhất.
     */
    private ResponseEntity<Map<String, String>> tooManyFiles(int count) {
        log.atWarn().addKeyValue("result_status", TOO_MANY_FILES).addKeyValue("files", count)
                .addKeyValue("max_files", maxFilesPerRequest).log("Request rejected");
        return ResponseEntity.badRequest().body(Map.of("type", TOO_MANY_FILES, "message",
                "Mỗi lần phân tích nhận tối đa " + maxFilesPerRequest + " file, yêu cầu này có " + count
                        + " file. Một cuộc gọi thường có 4 file (end call log và WebRTC log của caller và callee):"
                        + " bỏ bớt file rồi gửi lại."));
    }

    /**
     * Form không chọn file vẫn gửi một phần rỗng không tên — bỏ qua. File RỖNG có tên thì giữ lại:
     * File Validator phải loại và nêu tên nó (F04).
     */
    private static boolean isRealFile(MultipartFile f) {
        return !(f.isEmpty() && (f.getOriginalFilename() == null || f.getOriginalFilename().isBlank()));
    }

    /**
     * Bố cục report (mẫu MVP 4.5) cho Web UI: tiêu đề, nhãn các dòng đầu, các mục theo thứ tự. Lấy từ
     * chính {@link ReportRenderer} — nếu JS tự viết lại danh sách này, hai nơi sẽ có lúc lệch nhau và
     * bản web không còn đúng mẫu như bản text.
     */
    @GetMapping(path = "/api/report-layout", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> reportLayout() {
        return Map.of(
                "title", ReportRenderer.TITLE.substring(2),
                "header", ReportRenderer.HEADER,
                "sections", ReportRenderer.SECTIONS.stream().map(s -> s.substring(3)).toList(),
                "maxBytesPerFile", maxBytesPerFile,
                "maxFilesPerRequest", maxFilesPerRequest);
    }

    /** File quá giới hạn KHÔNG được đọc nội dung: File Validator loại và nêu tên (MVP mục 6.4, F04). */
    private AttachedFile toAttached(MultipartFile file) {
        String name = baseName(file.getOriginalFilename());
        if (file.getSize() > maxBytesPerFile) {
            return AttachedFile.notLoaded(name, file.getSize());
        }
        try {
            return new AttachedFile(name, file.getSize(), CallFolderReader.decode(file.getBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file tải lên", e);
        }
    }

    /** Một số trình duyệt gửi kèm đường dẫn máy người dùng ("C:\\Users\\…\\caller_endcall.log"). */
    static String baseName(String original) {
        if (original == null || original.isBlank()) {
            return "(không tên)";
        }
        String name = original.replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }

    /** Cả request vượt giới hạn multipart — trả lời có cấu trúc thay cho trang lỗi HTML. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("type", "ERROR",
                "message", "Tổng dung lượng file đính kèm vượt giới hạn cho phép."));
    }

    /** Lỗi không lường trước: không trả stack trace hay nội dung file ra ngoài (MVP mục 6.2). */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
        log.error("Lỗi không lường trước khi phân tích", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("type", "ERROR",
                "message", "Hệ thống gặp lỗi khi phân tích. Vui lòng thử lại."));
    }
}
