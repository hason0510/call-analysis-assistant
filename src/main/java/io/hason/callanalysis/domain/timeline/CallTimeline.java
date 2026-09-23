package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.signaling.LegAssignment;

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

    /** Bản ghi call summary (#H9) — chỉ tồn tại ở 2/16 file trong data mẫu. */
    public Optional<CanonicalEvent> callSummary(Leg leg) {
        return allEvents().stream()
                .filter(e -> e.type() == EventType.CALL_SUMMARY)
                .filter(e -> e.leg() == leg)
                .findFirst();
    }

    public boolean hasSource(LogSource source) {
        return allEvents().stream().anyMatch(e -> e.source() == source);
    }
}
