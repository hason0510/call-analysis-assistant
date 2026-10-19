package io.hason.callanalysis.domain.request;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trích Call-ID người dùng ghi trong câu hỏi (MVP mục 6.1 T4). Làm bằng code, không giao AI
 * (MVP mục 3.2): Call-ID có dạng UUID cố định, regex cho kết quả đúng và giống nhau mọi lần.
 */
public final class CallIdExtractor {

    /** Không dính chữ/số hai đầu: "fromTag" dạng `C8CF631E-0` không phải Call-ID. */
    static final Pattern UUID = Pattern.compile(
            "(?<![0-9A-Za-z])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}(?![0-9A-Za-z])");

    private CallIdExtractor() {
    }

    /**
     * @param callId Call-ID viết hoa như tên thư mục data mẫu; null khi không có hoặc mơ hồ
     * @param note   lý do không dùng Call-ID trong câu hỏi, để nêu ở "Giới hạn dữ liệu"; null nếu không có gì để nêu
     */
    public record Result(String callId, String note) {}

    /**
     * Dạng chuẩn của Call-ID: bỏ khoảng trắng hai đầu, viết HOA — như signaling trong Elasticsearch
     * (trường keyword, so đúng từng ký tự) và end call log. Tên thư mục của `ai20k_new_dataset` viết
     * thường, nên Call-ID lấy từ tên thư mục hay file case `held-out` không chuẩn hoá thì truy vấn ES
     * ra 0 event và mọi end call log bị loại vì "lệch Call-ID".
     *
     * @return null khi callId null hoặc rỗng
     */
    public static String normalize(String callId) {
        if (callId == null || callId.isBlank()) {
            return null;
        }
        return callId.trim().toUpperCase(Locale.ROOT);
    }

    public static Result extract(String question) {
        if (question == null) {
            return new Result(null, null);
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = UUID.matcher(question);
        while (m.find()) {
            found.add(normalize(m.group()));
        }
        if (found.size() > 1) {
            // Đoán một trong hai là chọn bừa cuộc gọi để phân tích; để File Validator lấy Call-ID theo file.
            return new Result(null, "Câu hỏi nêu " + found.size() + " Call-ID khác nhau, không dùng Call-ID"
                    + " trong câu hỏi mà xác định theo file đính kèm");
        }
        return new Result(found.stream().findFirst().orElse(null), null);
    }
}
