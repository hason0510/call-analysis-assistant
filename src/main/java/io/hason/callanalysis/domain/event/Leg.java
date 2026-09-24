package io.hason.callanalysis.domain.event;

/**
 * signaling.json KHÔNG có trường leg nên leg phải suy ra:
 *  - File end call log: cột role của bản ghi `info` (LegCorrelator) — chắc chắn.
 *  - Sự kiện signaling: appUserId của INIT_CALL đầu tiên là caller (LegAssignment).
 *    Trên data mẫu, cách suy này khớp cột role ở 17/17 file end call log.
 */
public enum Leg {
    CALLER,
    CALLEE,
    SERVER,
    UNKNOWN
}
