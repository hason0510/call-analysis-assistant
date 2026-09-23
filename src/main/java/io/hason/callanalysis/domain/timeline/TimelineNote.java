package io.hason.callanalysis.domain.timeline;

/**
 * Ghi chu ve qua trinh dung timeline. Nhung note loai DATA_LIMITATION se di thang
 * vao muc "Gioi han du lieu" cua report.
 */
public record TimelineNote(Kind kind, String message) {

    public enum Kind {
        /** Da loai bo ban ghi trung lap. */
        DEDUPED,
        /** Do lech dong ho giua client va server do duoc. */
        CLOCK_OFFSET,
        /** Track dung moc thoi gian tuong doi, khong ghep duoc vao timeline chinh. */
        RELATIVE_TRACK,
        /** Khong xac dinh chac chan duoc leg cua mot file. */
        LEG_UNCERTAIN,
        /** Thieu du lieu, anh huong den ket luan. */
        DATA_LIMITATION
    }

    public static TimelineNote of(Kind kind, String message) {
        return new TimelineNote(kind, message);
    }
}
