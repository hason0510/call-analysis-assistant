package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.Leg;

import java.util.List;

/**
 * Chuoi su kien dung moc thoi gian TUONG DOI — luon la WebRTC log.
 *
 * Duoc giu rieng thay vi tron vao timeline chinh vi log nay khong co goc thoi gian
 * tuyet doi: doan goc de ep ve Instant la bia so lieu.
 */
public record RelativeTrack(String fileName, Leg leg, String platform,
                            LegConfidence legConfidence, List<CanonicalEvent> events) {

    public RelativeTrack {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public enum LegConfidence {
        /** Doi chieu platform cua log voi cot platform/role cua ban ghi #H1 — chac chan. */
        MATCHED_BY_PLATFORM,
        /** Chi suy tu ten file — data mau co file dat ten sai. */
        FILE_NAME_ONLY,
        /** Khong xac dinh duoc. */
        UNRESOLVED
    }
}
