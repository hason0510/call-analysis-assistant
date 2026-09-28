package io.hason.callanalysis.domain.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model chung mà cả ba nguồn log đều đổ vào. Mọi thành phần sau (Timeline, Metrics,
 * Evidence, Rule) chỉ làm việc với kiểu này.
 *
 * {@code sourceRef} là BẮT BUỘC: MVP mục 8.2 yêu cầu mọi evidence trace được về dòng
 * log gốc, và mẫu report mục 4.5 in ra dạng [EV05][callee_endcall.log 10:00:41.000].
 */
public record CanonicalEvent(
        String eventId,
        String callId,
        Leg leg,
        LogSource source,
        EventTime time,
        EventType type,
        String name,
        Map<String, String> attributes,
        Severity severity,
        SourceRef sourceRef
) {

    public CanonicalEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(sourceRef, "sourceRef");
        leg = leg == null ? Leg.UNKNOWN : leg;
        type = type == null ? EventType.LOG_MESSAGE : type;
        severity = severity == null ? Severity.INFO : severity;
        // LinkedHashMap giữ thứ tự chèn: thứ tự đổi giữa các lần chạy là mất tính tất định,
        // và Consistency >= 95% (MVP mục 6.5) sẽ fail mà không rõ lý do.
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public String attribute(String key) {
        return attributes.get(key);
    }

    /**
     * Gán lại leg sau khi bước correlate xác định được chủ sở hữu thật của file.
     * Cần thiết vì tên file không đáng tin: data mẫu có `calleer_webrtc.log` thực chất
     * là log của caller.
     *
     * Đồng hồ client đi theo máy ghi log, nên đổi leg thì đổi luôn ClockDomain. Trước đây
     * clock giữ nguyên giá trị suy từ tên file: event nói leg CALLER nhưng timestamp lại
     * ghi là đồng hồ của callee.
     */
    public CanonicalEvent withLeg(Leg newLeg) {
        return newLeg == leg ? this : new CanonicalEvent(
                eventId, callId, newLeg, source, clockFollowing(newLeg), type, name, attributes, severity, sourceRef);
    }

    private EventTime clockFollowing(Leg newLeg) {
        // Mọi giờ tuyệt đối không phải của server đều là đồng hồ của máy ghi log, kể cả file
        // không có tiền tố caller_/callee_ lúc parse (ParseContext gán tạm LOG_RELATIVE).
        if (!(time instanceof EventTime.Absolute a) || a.clock() == ClockDomain.SERVER) {
            return time;
        }
        return switch (newLeg) {
            case CALLER -> EventTime.absolute(a.instant(), ClockDomain.CLIENT_CALLER);
            case CALLEE -> EventTime.absolute(a.instant(), ClockDomain.CLIENT_CALLEE);
            case SERVER, UNKNOWN -> time;
        };
    }
}
