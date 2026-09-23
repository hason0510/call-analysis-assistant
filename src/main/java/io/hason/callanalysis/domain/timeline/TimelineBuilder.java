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
 * Dung timeline cua mot cuoc goi tu cac canonical event da chuan hoa.
 *
 * Bon viec, theo dung thu tu:
 *   1. Correlate — xac dinh file thuoc ben nao bang NOI DUNG, roi gan lai leg.
 *   2. Deduplicate — loai ban ghi trung lap trong cung mot nguon.
 *   3. Sort — sap xep tat dinh.
 *   4. Do lech dong ho — do va ghi lai, KHONG viet lai timestamp.
 */
public class TimelineBuilder {

    private final LegCorrelator legCorrelator = new LegCorrelator();
    private final ClockOffsetEstimator clockOffsetEstimator = new ClockOffsetEstimator();

    public CallTimeline build(String callId, List<CanonicalEvent> events, LegAssignment legs) {
        if (events == null || events.isEmpty()) {
            return new CallTimeline(callId, List.of(), List.of(), legs, List.of(),
                    List.of(TimelineNote.of(TimelineNote.Kind.DATA_LIMITATION,
                            "Khong co su kien nao de dung timeline")));
        }

        List<TimelineNote> notes = new ArrayList<>();

        Map<String, LegCorrelator.FileLeg> correlation = legCorrelator.correlate(events);
        List<CanonicalEvent> retagged = applyCorrelation(events, correlation, notes);

        Deduplicated deduplicated = deduplicate(retagged);
        if (deduplicated.removed() > 0) {
            notes.add(TimelineNote.of(TimelineNote.Kind.DEDUPED,
                    "File dinh kem trung noi dung, da bo qua: "
                            + String.join(", ", deduplicated.duplicateFiles())
                            + " (" + deduplicated.removed() + " su kien)"));
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
                "Lech dong ho " + o.leg() + " so voi server: " + o.medianMillis()
                        + " ms (trung vi tren " + o.sampleCount() + " cap"
                        + (o.isNegligible() ? ", khong dang ke" : ", DANG KE") + ")")));

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
                        "File " + e.sourceRef().fileName() + " duoc gan lai tu " + e.leg()
                                + " sang " + fileLeg.leg() + " (doi chieu theo noi dung, khong theo ten file)"));
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
                    fileName + ": " + events.size() + " su kien dung moc thoi gian tuong doi,"
                            + " chua dong bo duoc voi timeline signaling"));
            if (confidence != RelativeTrack.LegConfidence.MATCHED_BY_PLATFORM) {
                notes.add(TimelineNote.of(TimelineNote.Kind.LEG_UNCERTAIN,
                        fileName + ": chua doi chieu duoc chu so huu theo noi dung ("
                                + confidence + ")"));
            }
        });
        return List.copyOf(tracks);
    }

    private record Deduplicated(List<CanonicalEvent> events, int removed, List<String> duplicateFiles) {}

    /**
     * Loai trung lap o muc FILE, khong phai o muc dong.
     *
     * Hai thu KHONG duoc coi la trung lap:
     *
     *   - Cung mot su kien xuat hien o signaling VA o end call log (vi du INVITE):
     *     do la hai goc nhin cua cung mot viec, phai giu ca hai.
     *   - Dong log lap lai trong cung mot file: libwebrtc that su ghi
     *     "openssl_adapter.cc ... TLS server done" ba lan trong cung mot mili giay.
     *     Do la ba ban ghi that; xoa bot se lam sai moi chi so dem.
     *
     * Trung lap that su trong bai toan nay la nguoi dung dinh kem CUNG MOT FILE hai lan
     * duoi hai ten khac nhau. Vi vay so sanh toan bo chuoi su kien cua tung file.
     */
    private Deduplicated deduplicate(List<CanonicalEvent> events) {
        Map<String, List<String>> fingerprintByFile = new LinkedHashMap<>();
        for (CanonicalEvent e : events) {
            fingerprintByFile
                    .computeIfAbsent(e.sourceRef().fileName(), k -> new ArrayList<>())
                    .add(e.time().sortKeyNanos() + "|" + e.type() + "|" + e.name()
                            + "|" + e.sourceRef().rawLine());
        }

        // Giu file co ten DUNG QUY UOC nhat, khong phai file dung truoc theo thu tu chu cai.
        // Neu khong, evidence se trich dan ten file vo nghia (vi du "ban_sao.log")
        // thay vi "callee_webrtc.log", lam report kho doc.
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

    /** Ten file dung quy uong dat ten cua data mau: caller_/callee_ + _endcall/_webrtc.log */
    private static final java.util.regex.Pattern CONVENTIONAL_NAME =
            java.util.regex.Pattern.compile("^(caller|callee)_(endcall|webrtc)\\.log$");

    /** Chon ten file de giu lai giua hai file trung noi dung. */
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
     * Thu tu tat dinh. Tie-break den tan so dong de ket qua khong doi giua cac lan chay —
     * thu tu doi se lam Evidence ID nhay va pha tinh nhat quan do o Sprint 2.
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
