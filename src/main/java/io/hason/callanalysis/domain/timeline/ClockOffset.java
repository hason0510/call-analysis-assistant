package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.Leg;

/**
 * Độ lệch đo được giữa đồng hồ client và đồng hồ server của một leg.
 *
 * Giá trị này là TỔNG của độ trễ mạng và độ lệch đồng hồ thật sự — hai thành phần
 * không tách được nếu chỉ có log một chiều. Vì vậy nó được dùng làm GIỚI HẠN TRÊN
 * của độ lệch, không dùng để viết lại timestamp.
 */
public record ClockOffset(Leg leg, long medianMillis, int sampleCount) {

    /** Dưới ngưỡng này thì độ lệch không đủ làm đổi thứ tự sự kiện (sự kiện cách nhau hàng giây). */
    public static final long NEGLIGIBLE_MILLIS = 1_000;

    public boolean isNegligible() {
        return Math.abs(medianMillis) < NEGLIGIBLE_MILLIS;
    }
}
