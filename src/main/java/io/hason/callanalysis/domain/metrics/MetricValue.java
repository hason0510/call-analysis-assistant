package io.hason.callanalysis.domain.metrics;

import java.math.BigDecimal;

/**
 * Gia tri mot chi so.
 *
 * MVP muc 4.3: "Chi so khong co trong du lieu phai hien thi N/A kem ly do,
 * KHONG duoc mac dinh ve 0."
 *
 * Vi vay khong dung double voi 0 hay -1 lam sentinel: kieu tong duoc tach tuong minh
 * de compiler bat buoc xu ly nhanh thieu du lieu, va de "do duoc va bang 0" khong bao gio
 * bi nham voi "khong do duoc". Tren data mau dieu nay rat quan trong: 18/20 cuoc goi
 * khong co ban ghi call summary nen moi chi so chat luong deu la N/A.
 */
public sealed interface MetricValue {

    record Present(BigDecimal value, String unit) implements MetricValue {}

    /** Gia tri dang chu, vi du ben ket thuc cuoc goi hay trang thai ICE cuoi cung. */
    record Text(String value) implements MetricValue {}

    record NotAvailable(String reason) implements MetricValue {}

    static MetricValue millis(long value) {
        return new Present(BigDecimal.valueOf(value), "ms");
    }

    static MetricValue count(long value) {
        return new Present(BigDecimal.valueOf(value), "lan");
    }

    static MetricValue of(BigDecimal value, String unit) {
        return new Present(value, unit);
    }

    static MetricValue text(String value) {
        return new Text(value);
    }

    static MetricValue unavailable(String reason) {
        return new NotAvailable(reason);
    }

    default boolean isPresent() {
        return this instanceof Present || this instanceof Text;
    }

    /** Dang hien thi trong report, theo mau muc 4.5: "N/A (khong co trong file log)". */
    default String display() {
        return switch (this) {
            case Present p -> p.value().stripTrailingZeros().toPlainString()
                    + (p.unit() == null || p.unit().isBlank() ? "" : " " + p.unit());
            case Text t -> t.value();
            case NotAvailable n -> "N/A (" + n.reason() + ")";
        };
    }
}
