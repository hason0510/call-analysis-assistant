package io.hason.callanalysis.domain.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Ba nguon log dung ba he thoi gian khac nhau, khong cung goc:
 *   signaling.json   -> ISO-8601 UTC, nano giay          -> Absolute(SERVER)
 *   *_endcall.log    -> epoch millis, dong ho client     -> Absolute(CLIENT_*)
 *   *_webrtc.log     -> [giay:mili] tu luc log khoi tao  -> Relative
 *
 * WebRTC log KHONG co gio tuyet doi. Ep no ve Instant bang cach doan goc thoi gian
 * la bia so lieu, nen kieu duoc tach tuong minh de compiler bat phai xu ly ca hai nhanh.
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

    /** Khoa sap xep on dinh: event tuyet doi luon dung truoc event tuong doi. */
    default long sortKeyNanos() {
        return switch (this) {
            case Absolute a -> a.instant().getEpochSecond() * 1_000_000_000L + a.instant().getNano();
            case Relative r -> Long.MIN_VALUE + r.sinceLogStart().toNanos();
        };
    }
}
