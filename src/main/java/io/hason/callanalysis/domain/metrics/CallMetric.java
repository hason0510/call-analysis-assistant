package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.Leg;

public record CallMetric(MetricKey key, Leg leg, MetricValue value) {

    public static CallMetric of(MetricKey key, MetricValue value) {
        return new CallMetric(key, Leg.UNKNOWN, value);
    }

    public static CallMetric of(MetricKey key, Leg leg, MetricValue value) {
        return new CallMetric(key, leg, value);
    }

    /** Tên trong report, đúng mẫu MVP mục 4.5: {@code "MOS (callee)"}, {@code "Packet loss (caller)"}. */
    public String label() {
        return key.isPerLeg() && leg != Leg.UNKNOWN
                ? key.displayName() + " (" + leg.name().toLowerCase() + ")"
                : key.displayName();
    }

    /**
     * Tên gửi AI: như {@link #label()}, thêm chiều cho chỉ số của luồng âm thanh nhận —
     * {@code "Packet loss (caller nhận)"}. Chỉ nằm trong context AI, report vẫn theo mẫu MVP 4.5.
     */
    public String aiLabel() {
        return key.isReceiveSide() && key.isPerLeg() && leg != Leg.UNKNOWN
                ? key.displayName() + " (" + leg.name().toLowerCase() + " nhận)"
                : label();
    }
}
