package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricValue;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineNote;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Rap report tu ket qua cac buoc truoc. Sprint 1 dung hoan toan rule; Sprint 2 se thay
 * phan summary/analysis/suggestions bang dau ra cua AI nhung giu nguyen bo cuc nay.
 */
public class ReportBuilder {

    public CallReport build(CallTimeline timeline, CallMetrics metrics,
                            List<Evidence> evidence, RuleVerdict verdict,
                            IssueTaxonomy taxonomy) {

        List<CallReport.EvidenceEntry> evidenceEntries = evidence.stream()
                .map(e -> new CallReport.EvidenceEntry(
                        e.id(), e.source().name(), e.timeLabel(),
                        e.description(), e.event().sourceRef().citation()))
                .toList();

        List<CallReport.MetricEntry> metricEntries = metrics.metrics().stream()
                .map(ReportBuilder::toEntry)
                .toList();

        return new CallReport(
                timeline.callId(),
                verdict.verdict(),
                verdict.qualityFlag(),
                verdict.issueCategory(),
                verdict.confidence(),
                summarise(timeline, verdict),
                evidenceEntries,
                metricEntries,
                possibleCauses(verdict, taxonomy),
                suggestions(verdict),
                dataLimitations(timeline, verdict, taxonomy),
                false);
    }

    private static CallReport.MetricEntry toEntry(CallMetric metric) {
        return switch (metric.value()) {
            case MetricValue.Present p -> new CallReport.MetricEntry(metric.label(),
                    p.value().stripTrailingZeros().toPlainString(), p.unit(), null,
                    metric.key().source().name());
            case MetricValue.Text t -> new CallReport.MetricEntry(metric.label(),
                    t.value(), null, null, metric.key().source().name());
            case MetricValue.NotAvailable n -> new CallReport.MetricEntry(metric.label(),
                    null, null, n.reason(), metric.key().source().name());
        };
    }

    private static String summarise(CallTimeline timeline, RuleVerdict verdict) {
        String base = switch (verdict.verdict()) {
            case SUCCESS -> verdict.qualityFlag()
                    ? "Cuoc goi thiet lap thanh cong nhung co dau hieu suy giam chat luong."
                    : "Cuoc goi thiet lap va ket thuc binh thuong.";
            case FAIL -> "Cuoc goi khong thanh cong.";
            case UNKNOWN -> "Khong du bang chung de ket luan.";
        };
        return base + " " + verdict.reasoning() + ".";
    }

    private static CallReport.PossibleCauses possibleCauses(RuleVerdict verdict,
                                                           IssueTaxonomy taxonomy) {
        if (verdict.issueCategory() == null) {
            return new CallReport.PossibleCauses(null, List.of());
        }
        String primary = taxonomy.find(verdict.issueCategory())
                .map(d -> verdict.issueCategory().name() + " — " + d.definition().strip())
                .orElse(verdict.issueCategory().name());

        List<String> alternatives = new ArrayList<>();
        taxonomy.find(verdict.issueCategory()).ifPresent(d -> {
            if (!d.knownAmbiguity().isBlank()) {
                alternatives.add("Diem mo ho: " + d.knownAmbiguity().strip());
            }
        });
        return new CallReport.PossibleCauses(primary, alternatives);
    }

    /** Bo de xuat chuan theo tung issue category. */
    private static List<String> suggestions(RuleVerdict verdict) {
        if (verdict.issueCategory() == null) {
            return List.of("Khong can hanh dong them.");
        }
        return switch (verdict.issueCategory()) {
            case SIGNALING_FAILURE -> List.of(
                    "Kiem tra log cua service signaling quanh thoi diem INIT_CALL.",
                    "Doi chieu so lan gui lai INVITE voi cau hinh timeout phia server.",
                    "Bo sung end call log cua ca hai ben de xac nhan phia nao khong phan hoi.");
            case ICE_FAILURE -> List.of(
                    "Kiem tra ket noi mang cua ben bi loi tai thoi diem cuoc goi.",
                    "Doi chieu danh sach ICE candidate hai ben xem co cap nao kha di khong.",
                    "Xac nhan TURN server co cap phat duoc relay khong.");
            case TURN_FAILURE -> List.of(
                    "Kiem tra tinh trang va credential cua TURN server.",
                    "Xac nhan cong UDP 3478 khong bi chan tu phia mang nguoi dung.");
            case NETWORK_PACKET_LOSS -> List.of(
                    "Kiem tra chat luong mang cua ben bi anh huong trong thoi gian cuoc goi.",
                    "Doi chieu chuoi ban ghi #H7 de xem mat goi keo dai hay chi thoang qua.");
            case NETWORK_DELAY_JITTER -> List.of(
                    "Kiem tra do tre va do rung mang cua ben bi anh huong.",
                    "Doi chieu transport.currentRttMs theo thoi gian, khong dung transport.rttMs.");
            case UNKNOWN -> List.of(
                    "Bo sung cac file log con thieu roi phan tich lai.");
        };
    }

    private static List<String> dataLimitations(CallTimeline timeline, RuleVerdict verdict,
                                                IssueTaxonomy taxonomy) {
        Set<String> limitations = new LinkedHashSet<>(verdict.dataLimitations());

        timeline.notes().stream()
                .filter(n -> n.kind() == TimelineNote.Kind.RELATIVE_TRACK
                        || n.kind() == TimelineNote.Kind.DATA_LIMITATION
                        || n.kind() == TimelineNote.Kind.LEG_UNCERTAIN)
                .map(TimelineNote::message)
                .forEach(limitations::add);

        timeline.clockOffsets().stream()
                .filter(o -> !o.isNegligible())
                .forEach(o -> limitations.add("Lech dong ho " + o.leg() + " so voi server la "
                        + o.medianMillis() + " ms, du lon de anh huong thu tu su kien"));

        IssueCategory category = verdict.issueCategory();
        if (category != null) {
            taxonomy.find(category)
                    .filter(d -> !d.calibration().isValidated())
                    .ifPresent(d -> limitations.add("Dieu kien phat hien " + category
                            + " chua kiem chung duoc tren data mau: " + d.calibration().evidence().strip()));
        }
        return List.copyOf(limitations);
    }

    /** Ket luan co can danh dau la chua chac chan khong. */
    public static boolean isUncertain(RuleVerdict verdict) {
        return verdict.verdict() == Verdict.UNKNOWN
                || verdict.confidence() == io.hason.callanalysis.domain.taxonomy.ConfidenceLevel.LOW;
    }
}
