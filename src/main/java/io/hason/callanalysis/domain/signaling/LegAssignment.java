package io.hason.callanalysis.domain.signaling;

import io.hason.callanalysis.domain.event.Leg;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Anh xa appUserId sang leg.
 *
 * signaling.json KHONG co truong leg — chi co appUserId. Trong data mau moi cuoc goi
 * co dung hai appUserId khac nhau, va ben gui INIT_CALL dau tien la caller.
 *
 * Nguon suy ra duoc ghi lai vi do tin cay khac nhau: cot `role` cua ban ghi #H1 trong
 * end call log la chac chan, con suy tu INIT_CALL thi la suy luan — Confidence Design
 * o Sprint 3 can phan biet hai truong hop nay.
 */
public record LegAssignment(Map<String, Leg> byAppUserId, Source derivedFrom) {

    public enum Source {
        /** Cot `role` cua ban ghi #H1 trong end call log — chac chan. */
        ENDCALL_ROLE,
        /** appUserId cua INIT_CALL dau tien la caller — suy luan. */
        FIRST_INIT_CALL,
        /** Khong du du lieu de suy ra. */
        NONE
    }

    public LegAssignment {
        byAppUserId = byAppUserId == null ? Map.of() : Map.copyOf(byAppUserId);
        derivedFrom = derivedFrom == null ? Source.NONE : derivedFrom;
    }

    public static LegAssignment unknown() {
        return new LegAssignment(Map.of(), Source.NONE);
    }

    /**
     * Suy ra tu chinh signaling: ben gui INIT_CALL dau tien la caller, ben con lai la callee.
     * Cuoc goi chet som (chi co INIT_CALL cua mot ben) se chi xac dinh duoc caller.
     */
    public static LegAssignment fromFirstInitCall(List<RawSignalingRecord> records) {
        if (records == null || records.isEmpty()) {
            return unknown();
        }
        Optional<String> caller = records.stream()
                .filter(r -> "INIT_CALL".equals(r.cmd()))
                .map(RawSignalingRecord::appUserId)
                .filter(u -> u != null && !u.isBlank())
                .findFirst();
        if (caller.isEmpty()) {
            return unknown();
        }

        java.util.Map<String, Leg> mapping = new java.util.LinkedHashMap<>();
        mapping.put(caller.get(), Leg.CALLER);
        records.stream()
                .map(RawSignalingRecord::appUserId)
                .filter(u -> u != null && !u.isBlank())
                .filter(u -> !u.equals(caller.get()))
                .distinct()
                .forEach(u -> mapping.put(u, Leg.CALLEE));

        return new LegAssignment(mapping, Source.FIRST_INIT_CALL);
    }

    public Leg legOf(String appUserId) {
        if (appUserId == null) {
            return Leg.UNKNOWN;
        }
        return byAppUserId.getOrDefault(appUserId, Leg.UNKNOWN);
    }
}
