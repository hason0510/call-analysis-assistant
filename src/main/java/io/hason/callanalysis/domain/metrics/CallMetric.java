package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.Leg;

public record CallMetric(MetricKey key, Leg leg, MetricValue value) {

    public static CallMetric of(MetricKey key, MetricValue value) {
        return new CallMetric(key, Leg.UNKNOWN, value);
    }

    public static CallMetric of(MetricKey key, Leg leg, MetricValue value) {
        return new CallMetric(key, leg, value);
    }

    public String label() {
        return key.isPerLeg() && leg != Leg.UNKNOWN
                ? key.displayName() + " (" + leg.name().toLowerCase() + ")"
                : key.displayName();
    }
}
