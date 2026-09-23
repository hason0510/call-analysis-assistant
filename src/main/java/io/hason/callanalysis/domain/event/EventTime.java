package io.hason.callanalysis.domain.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Ba nguồn log dùng ba hệ thời gian khác nhau, không cùng gốc:
 *   signaling.json   -> ISO-8601 UTC, nano giây          -> Absolute(SERVER)
 *   *_endcall.log    -> epoch millis, đồng hồ client     -> Absolute(CLIENT_*)
 *   *_webrtc.log     -> [giây:mili] từ lúc log khởi tạo  -> Relative
 *
 * WebRTC log KHÔNG có giờ tuyệt đối. Ép nó về Instant bằng cách đoán gốc thời gian
 * là bịa số liệu, nên kiểu được tách tường minh để compiler bắt phải xử lý cả hai nhánh.
 */
public sealed interface EventTime {

    record Absolute(Instant instant, ClockDomain clock) implements EventTime {}

    record Relative(Duration sinceLogStart) implements EventTime {}

    static Absolute absolute(Instant instant, ClockDomain clock) {
        return new Absolute(instant, clock);
    }

    static Relative relative(Duration sinceLogStart) {
        return new Relative(sinceLogStart);
    }

    /** Khoá sắp xếp ổn định: event tuyệt đối luôn đứng trước event tương đối. */
    default long sortKeyNanos() {
        return switch (this) {
            case Absolute a -> a.instant().getEpochSecond() * 1_000_000_000L + a.instant().getNano();
            case Relative r -> Long.MIN_VALUE + r.sinceLogStart().toNanos();
        };
    }
}
