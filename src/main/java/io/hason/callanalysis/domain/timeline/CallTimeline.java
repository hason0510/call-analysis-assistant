package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.signaling.LegAssignment;

import java.util.List;
import java.util.Optional;

/**
 * Timeline cua mot cuoc goi, tach thanh hai phan vi ba nguon log khong cung goc thoi gian:
 *
 *   mainTrack      — signaling + end call log, deu co gio tuyet doi, da sap xep
 *   relativeTracks — WebRTC log, moc thoi gian tuong doi, moi file mot track
 *
 * Tron hai phan vao nhau se tao ra thu tu gia. Giu rieng la cach trung thuc, va
 * viec khong ghep duoc duoc ghi lai trong notes de bao vao muc "Gioi han du lieu".
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

    /** Toan bo su kien, ke ca track tuong doi — dung khi can tim evidence o moi nguon. */
    public List<CanonicalEvent> allEvents() {
        List<CanonicalEvent> all = new java.util.ArrayList<>(mainTrack);
        relativeTracks.forEach(t -> all.addAll(t.events()));
        return List.copyOf(all);
    }

    public List<CanonicalEvent> from(LogSource source) {
        return allEvents().stream().filter(e -> e.source() == source).toList();
    }

    /** Su kien signaling dau tien mang lenh nay — dung cho cac chi so o T6. */
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

    /** Ban ghi call summary (#H9) — chi ton tai o 2/16 file trong data mau. */
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
