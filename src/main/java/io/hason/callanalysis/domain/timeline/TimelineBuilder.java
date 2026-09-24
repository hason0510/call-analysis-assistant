package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.signaling.LegAssignment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dựng timeline của một cuộc gọi từ các canonical event đã chuẩn hoá.
 *
 * Bốn việc, theo đúng thứ tự:
 *   1. Correlate — xác định file thuộc bên nào bằng NỘI DUNG, rồi gán lại leg.
 *   2. Deduplicate — loại bản ghi trùng lặp trong cùng một nguồn.
 *   3. Sort — sắp xếp tất định.
 *   4. Đo lệch đồng hồ — đo và ghi lại, KHÔNG viết lại timestamp.
 */
public class TimelineBuilder {

    private final LegCorrelator legCorrelator = new LegCorrelator();
    private final ClockOffsetEstimator clockOffsetEstimator = new ClockOffsetEstimator();

    public CallTimeline build(String callId, List<CanonicalEvent> events, LegAssignment legs) {
        return build(callId, events, legs, List.of());
    }

    /**
     * @param seedNotes ghi chú đã biết TRƯỚC khi dựng timeline, ví dụ bản export
     *                  signaling bị cắt bớt. Những dữ kiện này nằm ở metadata của
     *                  nguồn chứ không nằm trong chuỗi sự kiện, nên nếu không truyền
     *                  vào đây thì chúng biến mất khỏi pipeline.
     */
    public CallTimeline build(String callId, List<CanonicalEvent> events, LegAssignment legs,
                              List<TimelineNote> seedNotes) {
        List<TimelineNote> notes = new ArrayList<>(seedNotes == null ? List.of() : seedNotes);

        if (events == null || events.isEmpty()) {
            notes.add(TimelineNote.of(TimelineNote.Kind.DATA_LIMITATION,
                    "Không có sự kiện nào để dựng timeline"));
            return new CallTimeline(callId, List.of(), List.of(), legs, List.of(), notes);
        }

        Map<String, LegCorrelator.FileLeg> correlation = legCorrelator.correlate(events);
        List<CanonicalEvent> retagged = applyCorrelation(events, correlation, notes);

        Deduplicated deduplicated = deduplicate(retagged);
        if (deduplicated.removed() > 0) {
            notes.add(TimelineNote.of(TimelineNote.Kind.DEDUPED,
                    "File đính kèm trùng nội dung, đã bỏ qua: "
                            + String.join(", ", deduplicated.duplicateFiles())
                            + " (" + deduplicated.removed() + " sự kiện)"));
        }

        List<CanonicalEvent> mainTrack = new ArrayList<>();
        Map<String, List<CanonicalEvent>> relativeByFile = new LinkedHashMap<>();
        for (CanonicalEvent e : deduplicated.events()) {
            if (e.time() instanceof EventTime.Absolute) {
                mainTrack.add(e);
            } else {
                relativeByFile.computeIfAbsent(e.sourceRef().fileName(), k -> new ArrayList<>()).add(e);
            }
        }
        mainTrack.sort(MAIN_TRACK_ORDER);

        List<RelativeTrack> relativeTracks = buildRelativeTracks(relativeByFile, correlation, notes);

        List<ClockOffset> offsets = clockOffsetEstimator.estimate(mainTrack);
        offsets.forEach(o -> notes.add(TimelineNote.of(TimelineNote.Kind.CLOCK_OFFSET,
                "Lệch đồng hồ " + o.leg() + " so với server: " + o.medianMillis()
                        + " ms (trung vị trên " + o.sampleCount() + " cặp"
                        + (o.isNegligible() ? ", không đáng kể" : ", ĐÁNG KỂ") + ")")));

        return new CallTimeline(callId, mainTrack, relativeTracks, legs, offsets, notes);
    }

    private List<CanonicalEvent> applyCorrelation(List<CanonicalEvent> events,
                                                  Map<String, LegCorrelator.FileLeg> correlation,
                                                  List<TimelineNote> notes) {
        Set<String> reported = new LinkedHashSet<>();
        List<CanonicalEvent> result = new ArrayList<>(events.size());

        for (CanonicalEvent e : events) {
            LegCorrelator.FileLeg fileLeg = correlation.get(e.sourceRef().fileName());
            if (fileLeg == null || fileLeg.leg() == Leg.UNKNOWN) {
                result.add(e);
                continue;
            }
            if (fileLeg.leg() != e.leg() && reported.add(e.sourceRef().fileName())) {
                notes.add(TimelineNote.of(TimelineNote.Kind.LEG_UNCERTAIN,
                        "File " + e.sourceRef().fileName() + " được gán lại từ " + e.leg()
                                + " sang " + fileLeg.leg() + " (đối chiếu theo nội dung, không theo tên file)"));
            }
            result.add(e.withLeg(fileLeg.leg()));
        }
        return result;
    }

    private List<RelativeTrack> buildRelativeTracks(Map<String, List<CanonicalEvent>> byFile,
                                                    Map<String, LegCorrelator.FileLeg> correlation,
                                                    List<TimelineNote> notes) {
        List<RelativeTrack> tracks = new ArrayList<>();
        byFile.keySet().stream().sorted().forEach(fileName -> {
            List<CanonicalEvent> events = new ArrayList<>(byFile.get(fileName));
            events.sort(RELATIVE_TRACK_ORDER);

            LegCorrelator.FileLeg fileLeg = correlation.get(fileName);
            Leg leg = fileLeg == null ? Leg.UNKNOWN : fileLeg.leg();
            String platform = fileLeg == null ? null : fileLeg.platform();
            RelativeTrack.LegConfidence confidence = fileLeg == null
                    ? RelativeTrack.LegConfidence.UNRESOLVED
                    : fileLeg.confidence();

            tracks.add(new RelativeTrack(fileName, leg, platform, confidence, events));

            notes.add(TimelineNote.of(TimelineNote.Kind.RELATIVE_TRACK,
                    fileName + ": " + events.size() + " sự kiện dùng mốc thời gian tương đối,"
                            + " chưa đồng bộ được với timeline signaling"));
            if (!confidence.resolvedByContent()) {
                notes.add(TimelineNote.of(TimelineNote.Kind.LEG_UNCERTAIN,
                        fileName + ": chưa đối chiếu được chủ sở hữu theo nội dung ("
                                + confidence + ")"));
            }
        });
        return List.copyOf(tracks);
    }

    private record Deduplicated(List<CanonicalEvent> events, int removed, List<String> duplicateFiles) {}

    /**
     * Loại trùng lặp ở mức FILE, không phải ở mức dòng.
     *
     * Hai thứ KHÔNG được coi là trùng lặp:
     *
     *   - Cùng một sự kiện xuất hiện ở signaling VÀ ở end call log (ví dụ INVITE):
     *     đó là hai góc nhìn của cùng một việc, phải giữ cả hai.
     *   - Dòng log lặp lại trong cùng một file: libwebrtc thật sự ghi
     *     "openssl_adapter.cc ... TLS server done" ba lần trong cùng một mili giây.
     *     Đó là ba bản ghi thật; xoá bớt sẽ làm sai mọi chỉ số đếm.
     *
     * Trùng lặp thật sự trong bài toán này là người dùng đính kèm CÙNG MỘT FILE hai lần
     * dưới hai tên khác nhau. Vì vậy so sánh toàn bộ chuỗi sự kiện của từng file.
     */
    private Deduplicated deduplicate(List<CanonicalEvent> events) {
        Map<String, List<String>> fingerprintByFile = new LinkedHashMap<>();
        for (CanonicalEvent e : events) {
            fingerprintByFile
                    .computeIfAbsent(e.sourceRef().fileName(), k -> new ArrayList<>())
                    .add(e.time().sortKeyNanos() + "|" + e.type() + "|" + e.name()
                            + "|" + e.sourceRef().rawLine());
        }

        // Giữ file có tên ĐÚNG QUY ƯỚC nhất, không phải file đứng trước theo thứ tự chữ cái.
        // Nếu không, evidence sẽ trích dẫn tên file vô nghĩa (ví dụ "ban_sao.log")
        // thay vì "callee_webrtc.log", làm report khó đọc.
        Map<List<String>, String> keptByFingerprint = new LinkedHashMap<>();
        fingerprintByFile.forEach((fileName, fingerprint) ->
                keptByFingerprint.merge(fingerprint, fileName, TimelineBuilder::preferredFileName));

        Set<String> kept = new LinkedHashSet<>(keptByFingerprint.values());
        Set<String> duplicateFiles = new LinkedHashSet<>(fingerprintByFile.keySet());
        duplicateFiles.removeAll(kept);

        if (duplicateFiles.isEmpty()) {
            return new Deduplicated(events, 0, List.of());
        }

        List<CanonicalEvent> remaining = events.stream()
                .filter(e -> !duplicateFiles.contains(e.sourceRef().fileName()))
                .toList();
        return new Deduplicated(remaining, events.size() - remaining.size(), List.copyOf(duplicateFiles));
    }

    /** Tên file đúng quy ước đặt tên của data mẫu: caller_/callee_ + _endcall/_webrtc.log */
    private static final java.util.regex.Pattern CONVENTIONAL_NAME =
            java.util.regex.Pattern.compile("^(caller|callee)_(endcall|webrtc)\\.log$");

    /** Chọn tên file để giữ lại giữa hai file trùng nội dung. */
    private static String preferredFileName(String a, String b) {
        int scoreA = nameScore(a);
        int scoreB = nameScore(b);
        if (scoreA != scoreB) {
            return scoreA > scoreB ? a : b;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static int nameScore(String fileName) {
        String lower = fileName.toLowerCase();
        if (CONVENTIONAL_NAME.matcher(lower).matches()) {
            return 2;
        }
        return lower.startsWith("caller") || lower.startsWith("callee") ? 1 : 0;
    }

    /**
     * Thứ tự tất định. Tie-break đến tận số dòng để kết quả không đổi giữa các lần chạy —
     * thứ tự đổi sẽ làm Evidence ID nhảy và phá tính nhất quán đo ở Sprint 2.
     */
    private static final Comparator<CanonicalEvent> MAIN_TRACK_ORDER =
            Comparator.comparingLong((CanonicalEvent e) -> e.time().sortKeyNanos())
                    .thenComparing(e -> e.source().ordinal())
                    .thenComparing(e -> e.sourceRef().fileName())
                    .thenComparingInt(e -> e.sourceRef().lineNumber());

    private static final Comparator<CanonicalEvent> RELATIVE_TRACK_ORDER =
            Comparator.comparingLong((CanonicalEvent e) -> e.time().sortKeyNanos())
                    .thenComparingInt(e -> e.sourceRef().lineNumber());
}
