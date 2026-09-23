package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.Leg;

import java.util.List;

/**
 * Chuỗi sự kiện dùng mốc thời gian TƯƠNG ĐỐI — luôn là WebRTC log.
 *
 * Được giữ riêng thay vì trộn vào timeline chính vì log này không có gốc thời gian
 * tuyệt đối: đoán gốc để ép về Instant là bịa số liệu.
 */
public record RelativeTrack(String fileName, Leg leg, String platform,
                            LegConfidence legConfidence, List<CanonicalEvent> events) {

    public RelativeTrack {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public enum LegConfidence {
        /** Đối chiếu platform của log với cột platform/role của bản ghi #H1 — chắc chắn. */
        MATCHED_BY_PLATFORM,
        /** Chỉ suy từ tên file — data mẫu có file đặt tên sai. */
        FILE_NAME_ONLY,
        /** Không xác định được. */
        UNRESOLVED
    }
}
