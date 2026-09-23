package io.hason.callanalysis.domain.event;

/**
 * signaling.json KHONG co truong leg. Phai suy ra:
 *  - Uu tien 1: cot role cua ban ghi #H1 trong end call log (chac chan).
 *  - Uu tien 2: appUserId cua INIT_CALL dau tien la caller (do tin cay thap hon).
 */
public enum Leg {
    CALLER,
    CALLEE,
    SERVER,
    UNKNOWN
}
