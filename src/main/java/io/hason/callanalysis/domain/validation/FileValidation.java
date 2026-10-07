package io.hason.callanalysis.domain.validation;

import io.hason.callanalysis.domain.parse.DetectedLogType;

import java.util.List;

/**
 * Kết quả kiểm các file đính kèm: file nào được đưa vào phân tích, file nào bị loại và vì sao.
 *
 * @param callId Call-ID dùng cho phân tích: Call-ID được truyền vào, hoặc — khi không có — Call-ID
 *               khai báo trong end call log; null nếu không xác định được
 */
public record FileValidation(String callId, List<ValidFile> accepted, List<RejectedFile> rejected) {

    /**
     * Nhãn dùng thay Call-ID khi không xác định được (chỉ có WebRTC log, không ai nói Call-ID):
     * mọi event bắt buộc mang Call-ID, và report in nhãn này thay vì bịa ra một Call-ID.
     */
    public static final String UNKNOWN_CALL_ID = "(không xác định)";

    /** Call-ID để gắn vào event và report — không bao giờ null. */
    public String callIdOrPlaceholder() {
        return callId == null ? UNKNOWN_CALL_ID : callId;
    }

    public FileValidation {
        accepted = List.copyOf(accepted);
        rejected = List.copyOf(rejected);
    }

    public record ValidFile(String name, DetectedLogType type, List<String> lines) {}

    public enum Reason { TOO_LARGE, EMPTY, CORRUPT, UNRECOGNIZED, CALL_ID_MISMATCH }

    public record RejectedFile(String name, Reason reason, String detail) {

        /** Câu đưa vào mục "Giới hạn dữ liệu" của report. */
        public String message() {
            return name + ": " + detail + ", đã loại khỏi phân tích";
        }
    }
}
