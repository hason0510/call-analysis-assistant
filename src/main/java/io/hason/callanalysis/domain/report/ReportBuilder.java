package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
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
 * Ráp report từ kết quả các bước trước. Sprint 1 dùng hoàn toàn rule; Sprint 2 sẽ thay
 * phần summary/analysis/suggestions bằng đầu ra của AI nhưng giữ nguyên bố cục này.
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
                    p.value().stripTrailingZeros().toPlainString(),
                    metric.key().isProxy() ? p.unit() + " [proxy]" : p.unit(), null,
                    metric.key().source().name());
            // Chỉ số PROXY phải ghi rõ trong report (MVP mục 4.3)
            case MetricValue.Text t -> new CallReport.MetricEntry(metric.label(),
                    metric.key().isProxy() ? t.value() + " [proxy]" : t.value(),
                    null, null, metric.key().source().name());
            case MetricValue.NotAvailable n -> new CallReport.MetricEntry(metric.label(),
                    null, null, n.reason(), metric.key().source().name());
        };
    }

    private static String summarise(CallTimeline timeline, RuleVerdict verdict) {
        String base = switch (verdict.verdict()) {
            case SUCCESS -> verdict.qualityFlag()
                    ? "Cuộc gọi thiết lập thành công nhưng có dấu hiệu suy giảm chất lượng."
                    : "Cuộc gọi thiết lập và kết thúc bình thường.";
            case FAIL -> "Cuộc gọi không thành công.";
            case UNKNOWN -> "Không đủ bằng chứng để kết luận.";
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
                alternatives.add("Điểm mơ hồ: " + d.knownAmbiguity().strip());
            }
        });
        return new CallReport.PossibleCauses(primary, alternatives);
    }

    /** Bộ đề xuất chuẩn theo từng issue category. */
    private static List<String> suggestions(RuleVerdict verdict) {
        if (verdict.issueCategory() == null) {
            return List.of("Không cần hành động thêm.");
        }
        return switch (verdict.issueCategory()) {
            case SIGNALING_FAILURE -> List.of(
                    "Kiểm tra log của service signaling quanh thời điểm INIT_CALL.",
                    "Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.",
                    "Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.");
            case ICE_FAILURE -> List.of(
                    "Kiểm tra kết nối mạng của bên bị lỗi tại thời điểm cuộc gọi.",
                    "Đối chiếu danh sách ICE candidate hai bên xem có cặp nào khả dĩ không.",
                    "Xác nhận TURN server có cấp phát được relay không.");
            case TURN_FAILURE -> List.of(
                    "Kiểm tra tình trạng và credential của TURN server.",
                    "Xác nhận cổng UDP 3478 không bị chặn từ phía mạng người dùng.");
            case NETWORK_PACKET_LOSS -> List.of(
                    "Kiểm tra chất lượng mạng của bên bị ảnh hưởng trong thời gian cuộc gọi.",
                    "Đối chiếu chuỗi bản ghi stats theo từng giây để xem mất gói kéo dài hay chỉ thoáng qua.");
            case NETWORK_DELAY_JITTER -> List.of(
                    "Kiểm tra độ trễ và độ rung mạng của bên bị ảnh hưởng.",
                    "Đối chiếu transport.currentRttMs theo thời gian, không dùng transport.rttMs.");
            case UNKNOWN -> List.of(
                    "Bổ sung các file log còn thiếu rồi phân tích lại.");
        };
    }

    private static List<String> dataLimitations(CallTimeline timeline, RuleVerdict verdict,
                                                IssueTaxonomy taxonomy) {
        Set<String> limitations = new LinkedHashSet<>(verdict.dataLimitations());

        limitations.addAll(missingClientLogs(timeline));

        timeline.notes().stream()
                .filter(n -> n.kind() == TimelineNote.Kind.RELATIVE_TRACK
                        || n.kind() == TimelineNote.Kind.DATA_LIMITATION
                        || n.kind() == TimelineNote.Kind.LEG_UNCERTAIN)
                .map(TimelineNote::message)
                .forEach(limitations::add);

        timeline.clockOffsets().stream()
                .filter(o -> !o.isNegligible())
                .forEach(o -> limitations.add("Lệch đồng hồ " + o.leg() + " so với server là "
                        + o.medianMillis() + " ms, đủ lớn để ảnh hưởng thứ tự sự kiện"));

        IssueCategory category = verdict.issueCategory();
        if (category != null) {
            taxonomy.find(category)
                    .filter(d -> !d.calibration().isValidated())
                    .ifPresent(d -> limitations.add("Điều kiện phát hiện " + category
                            + " chưa kiểm chứng được trên data mẫu: " + d.calibration().evidence().strip()));
        }
        return List.copyOf(limitations);
    }

    /**
     * Nêu tên những log phía client KHÔNG có mặt.
     *
     * Mẫu report ở MVP mục 4.5 có sẵn ví dụ {@code "- Thiếu callee_webrtc.log."}, nghĩa là
     * người đọc phải biết mình đang thiếu gì. Không có mục này thì một cuộc gọi chỉ đính kèm
     * 1/4 file vẫn cho ra report trông đầy đủ như cuộc gọi đính kèm cả 4.
     *
     * Xác định theo NỘI DUNG chứ không theo tên file: data mẫu có `calleer_webrtc.log` thực
     * chất là log của caller, và có file tên `caller_webrtc.log` thực chất là end call log.
     * Vì vậy câu chữ nói "WebRTC log của callee" chứ không khẳng định một tên file cụ thể.
     *
     * Trường hợp KHÔNG có end call log của cả hai bên đã được RuleVerdictEngine nêu rồi,
     * nên ở đây bỏ qua để report không nói hai lần cùng một điều.
     */
    private static List<String> missingClientLogs(CallTimeline timeline) {
        List<String> missing = new ArrayList<>();
        for (LogSource source : List.of(LogSource.ENDCALL, LogSource.WEBRTC)) {
            boolean caller = hasEvents(timeline, source, Leg.CALLER);
            boolean callee = hasEvents(timeline, source, Leg.CALLEE);

            if (caller && callee) {
                continue;
            }
            if (!caller && !callee) {
                if (source == LogSource.ENDCALL) {
                    continue;   // RuleVerdictEngine đã nêu
                }
                missing.add("Thiếu " + label(source) + " của cả hai bên — "
                        + consequence(source, true));
                continue;
            }
            Leg absent = caller ? Leg.CALLEE : Leg.CALLER;
            missing.add("Thiếu " + label(source) + " của " + absent.name().toLowerCase()
                    + " — " + consequence(source, false));
        }
        return missing;
    }

    private static boolean hasEvents(CallTimeline timeline, LogSource source, Leg leg) {
        return timeline.allEvents().stream()
                .anyMatch(e -> e.source() == source && e.leg() == leg);
    }

    private static String label(LogSource source) {
        return source == LogSource.ENDCALL ? "end call log" : "WebRTC log";
    }

    private static String consequence(LogSource source, boolean bothLegs) {
        String what = source == LogSource.ENDCALL
                ? "không kiểm chứng được chỉ số chất lượng"
                : "không kiểm chứng được sự kiện ICE / TURN";
        return bothLegs ? what : what + " phía đó";
    }

    /** Kết luận có cần đánh dấu là chưa chắc chắn không. */
    public static boolean isUncertain(RuleVerdict verdict) {
        return verdict.verdict() == Verdict.UNKNOWN
                || verdict.confidence() == io.hason.callanalysis.domain.taxonomy.ConfidenceLevel.LOW;
    }
}
