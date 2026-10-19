package io.hason.callanalysis.domain.signaling;

/**
 * Đọc trường {@code message} (chữ tự do) của bản export signaling THÔ thành cờ có kiểu.
 *
 * {@code message} không được nạp vào Elasticsearch: nó mang User-ID, uid và Call-ID dạng gốc
 * (`No sessions found for Call-ID: …, User-ID C…`) mà index signaling không qua Sanitizer
 * (MVP mục 6.2). Vì vậy lúc import chỉ giữ lại CỜ, còn chữ bị bỏ. Nghĩa của từng thông báo nằm
 * ở đây — tầng domain — để adapter import chỉ việc gọi, không tự quyết chuỗi nào có nghĩa gì.
 *
 * Bản export GỌN (`ai20k_sample`, 4/7 file `ai20k_new_dataset`) không có {@code message} nào,
 * nên cờ ở đó là null — "không biết", khác với "biết và không có".
 */
public final class SignalingMessages {

    /**
     * Server tìm session của callee để đẩy INVITE mà không thấy — callee chưa online, phải
     * đánh thức qua push. Dữ liệu (2026-10-19): 3/3 cuộc bản thô có dòng này, mỗi lần gửi INVITE
     * một dòng WARN, và cả 3 cuộc callee vẫn trả TRYING ngay sau đó — đếm làm chỉ số (MVP mục 4.3),
     * KHÔNG phải tín hiệu lỗi.
     */
    static final String NO_SESSIONS_FOUND = "No sessions found";

    private SignalingMessages() {
    }

    /** @return null khi event không có {@code message} (bản export gọn) */
    public static Boolean noSessionsFound(String message) {
        return message == null ? null : message.contains(NO_SESSIONS_FOUND);
    }
}
