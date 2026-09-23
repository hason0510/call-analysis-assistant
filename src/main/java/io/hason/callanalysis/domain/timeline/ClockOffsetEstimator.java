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
 * Đo độ lệch giữa đồng hồ client và đồng hồ server.
 *
 * Cách neo: end call log ghi lại lệnh signaling mà client GỬI ĐI (`send_cmd`), và cùng
 * lệnh đó xuất hiện trong signaling với giờ server. Ghép từng lần gửi với sự kiện server
 * gần nhất cùng lệnh rồi lấy TRUNG VỊ của hiệu số.
 *
 * Dùng trung vị chứ không dùng trung bình vì các lệnh có gửi lại (TRYING, INVITE, BYE)
 * dễ bị ghép nhầm giữa các lần gửi — trên data mẫu việc ghép nhầm đẩy giá trị lên tới
 * hơn 4 giây, trong khi trung vị thực tế chỉ khoảng 70 ms.
 *
 * Giá trị đo được là TỔNG của độ trễ mạng và độ lệch đồng hồ thật. Hai thành phần này
 * không tách được nếu chỉ có log một chiều, nên kết quả được dùng làm GIỚI HẠN TRÊN của
 * độ lệch — để cảnh báo, không để viết lại timestamp.
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
