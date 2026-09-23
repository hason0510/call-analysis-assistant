package io.hason.callanalysis.domain.event;

/**
 * signaling.json KHÔNG có trường leg. Phải suy ra:
 *  - Ưu tiên 1: cột role của bản ghi #H1 trong end call log (chắc chắn).
 *  - Ưu tiên 2: appUserId của INIT_CALL đầu tiên là caller (độ tin cậy thấp hơn).
 */
public enum Leg {
    CALLER,
    CALLEE,
    SERVER,
    UNKNOWN
}
