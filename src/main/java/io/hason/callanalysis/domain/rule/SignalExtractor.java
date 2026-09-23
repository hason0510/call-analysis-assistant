package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineNote;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Rut tin hieu tho tu timeline. Khong ket luan gi — viec do thuoc RuleVerdictEngine. */
public class SignalExtractor {

    /**
     * Duong nen do tren 399 mau stats cua cac cuoc goi khoe manh trong data mau:
     * loss cao nhat 3,704%, MOS thap nhat 4,335, RTT cao nhat 181 ms, jitter cao nhat 26 ms.
     * Nguong canh bao dat CAO HON duong nen de khong gan co nham cho cuoc goi tot.
     * Day van la PHONG DOAN vi khong co ca mau chat luong kem — xem taxonomy.yaml.
     */
    private static final BigDecimal LOSS_THRESHOLD_PERCENT = new BigDecimal("5.0");
    private static final BigDecimal MOS_THRESHOLD = new BigDecimal("3.5");

    public RuleSignals extract(CallTimeline timeline) {
        Set<LogSource> sources = EnumSet.noneOf(LogSource.class);
        timeline.allEvents().forEach(e -> sources.add(e.source()));

        List<String> iceStates = timeline.allEvents().stream()
                .map(e -> e.attribute("iceStateTo"))
                .filter(java.util.Objects::nonNull)
                .toList();

        return new RuleSignals(
                timeline.countSignaling("INVITE") > 0,
                timeline.countSignaling("OK_ACK_OK") > 0,
                timeline.countSignaling("BYE") > 0,
                timeline.countSignaling("CANCEL") > 0,
                timeline.countSignaling("FAIL_HARD") > 0,
                iceStates.stream().anyMatch(s -> s.equalsIgnoreCase("connected")
                        || s.equalsIgnoreCase("completed")),
                iceStates.stream().anyMatch(s -> s.equalsIgnoreCase("failed")),
                hasFlag(timeline, "transport.hasMediaFail"),
                noMediaBytes(timeline),
                qualityDegraded(timeline),
                timeline.notes().stream().anyMatch(n ->
                        n.kind() == TimelineNote.Kind.DATA_LIMITATION
                                || n.message().contains("cat bot")),
                sources);
    }

    private boolean hasFlag(CallTimeline timeline, String field) {
        return qualityRecords(timeline).anyMatch(e -> "1".equals(e.attribute(field)));
    }

    /**
     * Tin hieu hong manh nhat va re nhat de kiem: khong mot byte audio nao di qua.
     * Manh hon MOS, vi MOS bang 0 mo ho giua "do duoc va bang 0" voi "khong do duoc".
     * Chi xet khi da co ban ghi chi so — khong co ban ghi thi khong ket luan.
     */
    private boolean noMediaBytes(CallTimeline timeline) {
        List<CanonicalEvent> records = qualityRecords(timeline).toList();
        if (records.isEmpty()) {
            return false;
        }
        return records.stream().allMatch(e ->
                isZero(e.attribute("audio.bytesReceived")) && isZero(e.attribute("audio.bytesSent")));
    }

    private boolean qualityDegraded(CallTimeline timeline) {
        return qualityRecords(timeline).anyMatch(e -> {
            boolean lossHigh = decimal(e.attribute("audio.packetLostPercent"))
                    .filter(v -> v.compareTo(LOSS_THRESHOLD_PERCENT) > 0).isPresent();
            boolean mosLow = decimal(e.attribute("audio.audioMos"))
                    .filter(v -> v.signum() > 0 && v.compareTo(MOS_THRESHOLD) < 0).isPresent();
            boolean poorFlag = "1".equals(e.attribute("transport.hasMediaPoor"));
            return lossHigh || mosLow || poorFlag;
        });
    }

    private java.util.stream.Stream<CanonicalEvent> qualityRecords(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> e.type() == EventType.CALL_SUMMARY || e.type() == EventType.MEDIA_STATS)
                .filter(e -> e.leg() == Leg.CALLER || e.leg() == Leg.CALLEE);
    }

    private static boolean isZero(String raw) {
        return decimal(raw).map(v -> v.signum() == 0).orElse(false);
    }

    private static Optional<BigDecimal> decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
