package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.Leg;

/**
 * Do lech do duoc giua dong ho client va dong ho server cua mot leg.
 *
 * Gia tri nay la TONG cua do tre mang va do lech dong ho that su — hai thanh phan
 * khong tach duoc neu chi co log mot chieu. Vi vay no duoc dung lam GIOI HAN TREN
 * cua do lech, khong dung de viet lai timestamp.
 */
public record ClockOffset(Leg leg, long medianMillis, int sampleCount) {

    /** Duoi nguong nay thi do lech khong du lam doi thu tu su kien (su kien cach nhau hang giay). */
    public static final long NEGLIGIBLE_MILLIS = 1_000;

    public boolean isNegligible() {
        return Math.abs(medianMillis) < NEGLIGIBLE_MILLIS;
    }
}
