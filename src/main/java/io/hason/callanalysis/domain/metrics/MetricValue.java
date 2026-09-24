package io.hason.callanalysis.domain.metrics;

import java.math.BigDecimal;

/**
 * Giá trị một chỉ số.
 *
 * MVP mục 4.3: "Chỉ số không có trong dữ liệu phải hiển thị N/A kèm lý do,
 * KHÔNG được mặc định về 0."
 *
 * Vì vậy không dùng double với 0 hay -1 làm sentinel: kiểu tổng được tách tường minh
 * để compiler bắt buộc xử lý nhánh thiếu dữ liệu, và để "đo được và bằng 0" không bao giờ
 * bị nhầm với "không đo được". Trên data mẫu điều này rất quan trọng: summary `endcall`
 * có ở cả 16 file end call log, nhưng MOS/loss/RTT chỉ có giá trị thật ở 7 leg (5 cuộc
 * gọi). Các leg còn lại chưa từng có media, app ghi 0 vào chỗ trống, và 0 đó phải thành
 * N/A chứ không được in ra như một kết quả đo.
 */
public sealed interface MetricValue {

    record Present(BigDecimal value, String unit) implements MetricValue {}

    /** Giá trị dạng chữ, ví dụ bên kết thúc cuộc gọi hay trạng thái ICE cuối cùng. */
    record Text(String value) implements MetricValue {}

    record NotAvailable(String reason) implements MetricValue {}

    static MetricValue millis(long value) {
        return new Present(BigDecimal.valueOf(value), "ms");
    }

    static MetricValue count(long value) {
        return new Present(BigDecimal.valueOf(value), "lần");
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

    /** Dạng hiển thị trong report, theo mẫu mục 4.5: "N/A (không có trong file log)". */
    default String display() {
        return switch (this) {
            case Present p -> p.value().stripTrailingZeros().toPlainString()
                    + (p.unit() == null || p.unit().isBlank() ? "" : " " + p.unit());
            case Text t -> t.value();
            case NotAvailable n -> "N/A (" + n.reason() + ")";
        };
    }
}
