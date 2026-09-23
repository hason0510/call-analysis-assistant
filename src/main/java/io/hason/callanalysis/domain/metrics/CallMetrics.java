package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.Leg;

import java.util.List;
import java.util.Optional;

public record CallMetrics(String callId, List<CallMetric> metrics) {

    public CallMetrics {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
    }

    public Optional<CallMetric> find(MetricKey key) {
        return metrics.stream().filter(m -> m.key() == key).findFirst();
    }

    public Optional<CallMetric> find(MetricKey key, Leg leg) {
        return metrics.stream().filter(m -> m.key() == key && m.leg() == leg).findFirst();
    }

    public MetricValue valueOf(MetricKey key) {
        return find(key).map(CallMetric::value)
                .orElseGet(() -> MetricValue.unavailable("chua tinh chi so nay"));
    }

    public long availableCount() {
        return metrics.stream().filter(m -> m.value().isPresent()).count();
    }

    public long unavailableCount() {
        return metrics.size() - availableCount();
    }
}
