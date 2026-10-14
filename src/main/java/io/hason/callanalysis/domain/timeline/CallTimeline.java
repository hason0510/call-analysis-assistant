package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.signaling.LegAssignment;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Timeline của một cuộc gọi, tách thành hai phần vì ba nguồn log không cùng gốc thời gian:
 *
 *   mainTrack      — signaling + end call log, đều có giờ tuyệt đối, đã sắp xếp
 *   relativeTracks — WebRTC log, mốc thời gian tương đối, mỗi file một track
 *
 * Trộn hai phần vào nhau sẽ tạo ra thứ tự giả. Giữ riêng là cách trung thực, và
 * việc không ghép được được ghi lại trong notes để báo vào mục "Giới hạn dữ liệu".
 */
public record CallTimeline(
        String callId,
        List<CanonicalEvent> mainTrack,
        List<RelativeTrack> relativeTracks,
        LegAssignment legs,
        List<ClockOffset> clockOffsets,
        List<TimelineNote> notes
) {

    public CallTimeline {
        mainTrack = mainTrack == null ? List.of() : List.copyOf(mainTrack);
        relativeTracks = relativeTracks == null ? List.of() : List.copyOf(relativeTracks);
        clockOffsets = clockOffsets == null ? List.of() : List.copyOf(clockOffsets);
        notes = notes == null ? List.of() : List.copyOf(notes);
        legs = legs == null ? LegAssignment.unknown() : legs;
    }

    public int totalEvents() {
        return mainTrack.size() + relativeTracks.stream().mapToInt(t -> t.events().size()).sum();
    }

    /** Toàn bộ sự kiện, kể cả track tương đối — dùng khi cần tìm evidence ở mọi nguồn. */
    public List<CanonicalEvent> allEvents() {
        List<CanonicalEvent> all = new java.util.ArrayList<>(mainTrack);
        relativeTracks.forEach(t -> all.addAll(t.events()));
        return List.copyOf(all);
    }

    public List<CanonicalEvent> from(LogSource source) {
        return allEvents().stream().filter(e -> e.source() == source).toList();
    }

    /** Sự kiện signaling đầu tiên mang lệnh này — dùng cho các chỉ số ở T6. */
    public Optional<CanonicalEvent> firstSignaling(String command) {
        return mainTrack.stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> command.equals(e.name()))
                .findFirst();
    }

    public Optional<CanonicalEvent> lastSignaling(String command) {
        return mainTrack.stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> command.equals(e.name()))
                .reduce((first, second) -> second);
    }

    public long countSignaling(String command) {
        return mainTrack.stream()
                .filter(e -> e.source() == LogSource.SIGNALING)
                .filter(e -> command.equals(e.name()))
                .count();
    }

    /**
     * Bản ghi call summary (tag `endcall`) của một leg, nếu leg đó có end call log.
     *
     * Một leg có thể có NHIỀU end call log: người nhận đăng nhập nhiều thiết bị, mọi máy cùng đổ chuông, một
     * máy bắt máy, máy kia dừng với `_emitFailed … another_device.accepted … code: 490` (fc3187b4,
     * ai20k_new_dataset). Khi đó lấy summary của máy CÓ âm thanh (bytesReceived lớn nhất). Lấy summary đầu tiên
     * theo thời gian thì ra máy không bắt máy (dừng sớm hơn): chỉ số callee N/A "packetsReceived = 0" dù bên
     * nhận nghe bình thường. Không máy nào có âm thanh thì giữ summary đầu tiên như trước.
     */
    public Optional<CanonicalEvent> callSummary(Leg leg) {
        List<CanonicalEvent> summaries = allEvents().stream()
                .filter(e -> e.type() == EventType.CALL_SUMMARY)
                .filter(e -> e.leg() == leg)
                .toList();
        return summaries.stream()
                .filter(e -> bytesReceived(e) > 0)
                .max(Comparator.comparingLong(CallTimeline::bytesReceived))
                .or(() -> summaries.stream().findFirst());
    }

    /** End call log của máy có âm thanh mà {@link #callSummary} chọn; rỗng khi không máy nào có âm thanh. */
    public Optional<String> audioFile(Leg leg) {
        return callSummary(leg).filter(s -> bytesReceived(s) > 0).map(s -> s.sourceRef().fileName());
    }

    /** End call log của một leg, theo thứ tự trên timeline. Nhiều hơn một nghĩa là bên đó dùng nhiều thiết bị. */
    public List<String> endCallFiles(Leg leg) {
        return mainTrack.stream()
                .filter(e -> e.source() == LogSource.ENDCALL && e.leg() == leg)
                .map(e -> e.sourceRef().fileName())
                .distinct()
                .toList();
    }

    /** Số byte âm thanh đã nhận; thiếu hoặc không phải số thì coi là 0 (không có âm thanh để chọn). */
    public static long bytesReceived(CanonicalEvent event) {
        String value = event.attribute("audio.bytesReceived");
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean hasSource(LogSource source) {
        return allEvents().stream().anyMatch(e -> e.source() == source);
    }
}
