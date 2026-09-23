package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Do do lech giua dong ho client va dong ho server.
 *
 * Cach neo: end call log ghi lai lenh signaling ma client GUI DI (`send_cmd`), va cung
 * lenh do xuat hien trong signaling voi gio server. Ghep tung lan gui voi su kien server
 * gan nhat cung lenh roi lay TRUNG VI cua hieu so.
 *
 * Dung trung vi chu khong dung trung binh vi cac lenh co gui lai (TRYING, INVITE, BYE)
 * de bi ghep nham giua cac lan gui — tren data mau viec ghep nham day gia tri len toi
 * hon 4 giay, trong khi trung vi thuc te chi khoang 70 ms.
 *
 * Gia tri do duoc la TONG cua do tre mang va do lech dong ho that. Hai thanh phan nay
 * khong tach duoc neu chi co log mot chieu, nen ket qua duoc dung lam GIOI HAN TREN cua
 * do lech — de canh bao, khong de viet lai timestamp.
 */
public class ClockOffsetEstimator {

    private static final String TAG_COLUMN = "#tag";
    private static final String SEND_CMD = "send_cmd";
    private static final int MIN_SAMPLES = 3;

    public List<ClockOffset> estimate(List<CanonicalEvent> events) {
        Map<String, List<Instant>> serverByCommand = new java.util.HashMap<>();
        for (CanonicalEvent e : events) {
            if (e.source() == LogSource.SIGNALING) {
                instantOf(e).ifPresent(i ->
                        serverByCommand.computeIfAbsent(e.name(), k -> new ArrayList<>()).add(i));
            }
        }
        if (serverByCommand.isEmpty()) {
            return List.of();
        }

        Map<Leg, List<Long>> diffsByLeg = new EnumMap<>(Leg.class);
        for (CanonicalEvent e : events) {
            if (e.source() != LogSource.ENDCALL
                    || e.type() != EventType.SIGNALING_COMMAND
                    || !SEND_CMD.equals(e.attribute(TAG_COLUMN))) {
                continue;
            }
            List<Instant> serverTimes = serverByCommand.get(e.name());
            if (serverTimes == null) {
                continue;
            }
            instantOf(e).ifPresent(clientTime -> {
                long nearest = nearestDifferenceMillis(serverTimes, clientTime);
                diffsByLeg.computeIfAbsent(e.leg(), k -> new ArrayList<>()).add(nearest);
            });
        }

        List<ClockOffset> offsets = new ArrayList<>();
        diffsByLeg.forEach((leg, diffs) -> {
            if (diffs.size() >= MIN_SAMPLES) {
                offsets.add(new ClockOffset(leg, median(diffs), diffs.size()));
            }
        });
        offsets.sort(java.util.Comparator.comparing(ClockOffset::leg));
        return List.copyOf(offsets);
    }

    private static long nearestDifferenceMillis(List<Instant> serverTimes, Instant clientTime) {
        long best = Long.MAX_VALUE;
        long bestAbs = Long.MAX_VALUE;
        for (Instant server : serverTimes) {
            long diff = server.toEpochMilli() - clientTime.toEpochMilli();
            long abs = Math.abs(diff);
            if (abs < bestAbs) {
                bestAbs = abs;
                best = diff;
            }
        }
        return best;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static java.util.Optional<Instant> instantOf(CanonicalEvent event) {
        return event.time() instanceof EventTime.Absolute absolute
                ? java.util.Optional.of(absolute.instant())
                : java.util.Optional.empty();
    }
}
