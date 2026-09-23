package io.hason.callanalysis.domain.security;

/**
 * Cách xử lý trước khi gửi sang AI hoặc ghi ra log.
 *
 * Sprint 2 (T7 Sanitizer) sẽ thực thi các policy này; Sprint 1 chỉ lập danh mục.
 */
public enum HandlingPolicy {
    /** Giữ nguyên. */
    ALLOW,
    /** Giữ đủ để debug, bỏ chi tiết không cần thiết. */
    MINIMIZE,
    /** Thay bằng nhãn, ví dụ [IP_REDACTED]. */
    MASK,
    /** Thay bằng mã ổn định để vẫn correlate được, ví dụ user_id=USER_a81f2c. */
    PSEUDONYMIZE,
    /** Xoá hẳn, không được xuất hiện ở bất cứ đâu. */
    DROP
}
