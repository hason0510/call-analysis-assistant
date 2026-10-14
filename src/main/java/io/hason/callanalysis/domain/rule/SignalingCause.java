package io.hason.callanalysis.domain.rule;

/**
 * Nguyên nhân con của SIGNALING_FAILURE, để report chọn đề xuất đúng hướng (MVP 7.1 T2: "bộ đề xuất chuẩn theo issue
 * category"). Trước Sprint 3 T2 mọi cuộc SIGNALING_FAILURE nhận cùng ba câu đề xuất — kể cả ba cuộc bị server từ chối
 * theo chính sách (mã 428), nơi "kiểm tra cấu hình timeout" không dẫn tới đâu.
 */
public enum SignalingCause {
    /** Server từ chối INIT_CALL kèm mã (ví dụ 428 privacy_restricted): chính sách, không phải lỗi mạng. */
    INIT_CALL_REJECTED,
    /** App hết thời gian chờ ICE candidate trước khi gửi được INVITE. */
    CANDIDATE_TIMEOUT,
    /** Không có INVITE, không thấy nguyên nhân dương tính nào. */
    NO_INVITE,
    /** Signaling có FAIL_HARD trước khi thiết lập xong. */
    FAIL_HARD,
    /** Có CANCEL và không đạt OK_ACK_OK — chưa phân biệt người dùng huỷ hay hệ thống hết giờ. */
    CANCELLED,
    /** Không đạt OK_ACK_OK, không thấy nguyên nhân cụ thể. */
    NOT_CONFIRMED
}
