package io.hason.callanalysis.domain.security;

/**
 * Cach xu ly truoc khi gui sang AI hoac ghi ra log.
 *
 * Sprint 2 (T7 Sanitizer) se thuc thi cac policy nay; Sprint 1 chi lap danh muc.
 */
public enum HandlingPolicy {
    /** Giu nguyen. */
    ALLOW,
    /** Giu du de debug, bo chi tiet khong can thiet. */
    MINIMIZE,
    /** Thay bang nhan, vi du [IP_REDACTED]. */
    MASK,
    /** Thay bang ma on dinh de van correlate duoc, vi du user_id=USER_a81f2c. */
    PSEUDONYMIZE,
    /** Xoa han, khong duoc xuat hien o bat cu dau. */
    DROP
}
