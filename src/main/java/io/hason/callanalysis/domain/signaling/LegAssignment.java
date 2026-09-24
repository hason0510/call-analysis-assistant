package io.hason.callanalysis.domain.signaling;

import io.hason.callanalysis.domain.event.Leg;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ánh xạ appUserId sang leg.
 *
 * signaling.json KHÔNG có trường leg — chỉ có appUserId. Trong data mẫu mỗi cuộc gọi
 * có đúng hai appUserId khác nhau, và bên gửi INIT_CALL đầu tiên là caller.
 *
 * Nguồn suy ra được ghi lại vì độ tin cậy khác nhau: cột `role` của bản ghi #H1 trong
 * end call log là chắc chắn, còn suy từ INIT_CALL thì là suy luận — Confidence Design
 * ở Sprint 3 cần phân biệt hai trường hợp này.
 */
public record LegAssignment(Map<String, Leg> byAppUserId, Source derivedFrom) {

    public enum Source {
        /**
         * Cột `role` của bản ghi `info` trong end call log — chắc chắn. Khai báo cho
         * Confidence Design (Sprint 3); hiện chưa nơi nào tạo LegAssignment theo nguồn này.
         */
        ENDCALL_ROLE,
        /** appUserId của INIT_CALL đầu tiên là caller — suy luận. */
        FIRST_INIT_CALL,
        /** Không đủ dữ liệu để suy ra. */
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
     * Suy ra từ chính signaling: bên gửi INIT_CALL đầu tiên là caller, bên còn lại là callee.
     * Cuộc gọi chết sớm (chỉ có INIT_CALL của một bên) sẽ chỉ xác định được caller.
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
