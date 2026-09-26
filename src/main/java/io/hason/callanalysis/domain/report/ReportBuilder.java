package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricKey;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.metrics.MetricValue;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.rule.TurnFailure;
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
                dataLimitations(timeline, metrics, verdict, taxonomy),
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
        // Có căn cứ đo được của chính cuộc gọi thì in căn cứ; không thì dùng định nghĩa của taxonomy
        String explanation = verdict.causeBasis() != null ? verdict.causeBasis()
                : taxonomy.find(verdict.issueCategory()).map(d -> d.definition().strip()).orElse(null);
        String primary = explanation == null ? verdict.issueCategory().name()
                : verdict.issueCategory().name() + " — " + explanation;

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
            case TURN_FAILURE -> turnSuggestions(verdict.turnFailure());
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

    /**
     * Đề xuất theo KIỂU TURN hỏng, không theo category chung.
     *
     * Trước đây mọi ca TURN_FAILURE nhận cùng hai câu "kiểm tra credential" và "cổng UDP
     * 3478 không bị chặn". Hai câu đó chỉ đúng khi request đã rời máy: với 703100CF (không
     * tạo nổi socket, 0 request) và 7B56D7AD (lỗi gửi ngay trên máy) chúng chỉ sai hướng.
     * Cổng và giao thức đọc từ log, không viết cứng.
     */
    private static List<String> turnSuggestions(TurnFailure turn) {
        List<String> suggestions = new ArrayList<>();
        String port = turn.transport() != null ? "cổng " + turn.transport() : "cổng TURN";
        switch (turn.kind()) {
            case SOCKET_NOT_CREATED -> {
                suggestions.add("Chưa có request nào tới được TURN server: WebRTC không tạo được socket"
                        + " TURN (Failed to create TURN client socket), nên chưa thể kết luận gì về"
                        + " TURN server hay tường lửa.");
                suggestions.add("Kiểm tra trạng thái mạng trên thiết bị lúc gọi: ứng dụng có được phép"
                        + " dùng mạng không, và giao diện mạng nào đang hoạt động.");
            }
            case SEND_FAILED_ON_DEVICE -> {
                suggestions.add("Request tới TURN server lỗi ngay khi gửi trên thiết bị (Failed to send"
                        + " TURN message, " + turn.sendError() + ") và không có phản hồi nào: kiểm tra"
                        + " kết nối mạng của thiết bị lúc gọi.");
                suggestions.add("Chưa có phản hồi nào từ TURN server nên chưa thể kết luận gì về phía server.");
            }
            case NO_RESPONSE -> {
                suggestions.add("Đã gửi " + turn.requestsSent() + " request allocate tới TURN server ("
                        + port + ") nhưng không nhận được phản hồi nào, kể cả phản hồi 401 của bước"
                        + " xác thực.");
                suggestions.add("Log không phân biệt được mạng đang chặn đường tới TURN server hay TURN"
                        + " server không trả lời: kiểm tra cả hai — " + port + " có bị chặn trên mạng của"
                        + " người dùng không, và TURN server có hoạt động tại thời điểm đó không.");
            }
            case NOT_ALLOCATED_AFTER_RESPONSE -> suggestions.add("TURN server có phản hồi ("
                    + turn.responses() + " lần) nhưng không lần nào cấp phát thành công: kiểm tra"
                    + " credential TURN mà app gửi đi và cấu hình của TURN server.");
            case UNCLASSIFIED, NONE -> suggestions.add("Log không đủ để biết request TURN có rời thiết bị"
                    + " hay không: kiểm tra kết nối mạng của thiết bị và tình trạng TURN server.");
        }
        if (turn.vpnInterface() != null) {
            suggestions.add("Log ghi nhận giao diện VPN " + turn.vpnInterface() + " đang hoạt động trên"
                    + " thiết bị: nên thử lại khi tắt VPN. Đây là dữ kiện đi kèm, chưa đủ để kết luận"
                    + " VPN là nguyên nhân.");
        }
        return List.copyOf(suggestions);
    }

    private static List<String> dataLimitations(CallTimeline timeline, CallMetrics metrics,
                                                RuleVerdict verdict, IssueTaxonomy taxonomy) {
        Set<String> limitations = new LinkedHashSet<>(verdict.dataLimitations());

        limitations.addAll(missingClientLogs(timeline));
        limitations.addAll(zeroFilledLegs(metrics));
        endOfCallSnapshotNote(metrics).ifPresent(limitations::add);

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

    /**
     * Giải thích vì sao chỉ số chất lượng của một leg là N/A dù log có ghi số 0.
     *
     * Bảng chỉ số chỉ ghi ngắn `N/A (audio.packetsReceived = 0)`. Câu giải thích đặt ở đây,
     * mỗi leg một dòng — trước đây nó nằm trong từng ô của bảng, nên một report lặp lại
     * cùng một câu dài tới 6 lần và làm vỡ bảng.
     */
    private static List<String> zeroFilledLegs(CallMetrics metrics) {
        List<String> notes = new ArrayList<>();
        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            boolean noAudio = hasReason(metrics, MetricKey.MOS, leg, MetricsCalculator.NO_AUDIO_PACKETS);
            boolean noStun = hasReason(metrics, MetricKey.RTT, leg, MetricsCalculator.NO_STUN_RESPONSE);
            if (!noAudio && !noStun) {
                continue;
            }
            List<String> causes = new ArrayList<>();
            List<String> affected = new ArrayList<>();
            if (noAudio) {
                causes.add("chưa nhận được gói audio nào (" + MetricsCalculator.NO_AUDIO_PACKETS + ")");
                affected.add("MOS, packet loss, jitter");
            }
            if (noStun) {
                causes.add("chưa có phản hồi STUN nào (" + MetricsCalculator.NO_STUN_RESPONSE + ")");
                affected.add("RTT");
            }
            notes.add("Leg " + leg.name().toLowerCase() + " " + String.join(" và ", causes) + ": "
                    + String.join(", ", affected)
                    + " trong log đều là 0 điền vào chỗ trống, không phải kết quả đo");
        }
        return notes;
    }

    /**
     * MOS, RTT, jitter trong bảng là bản chụp mẫu stats CUỐI (summary trùng mẫu cuối ở 8/8
     * leg có media), không phải giá trị của cả cuộc. Nhãn trong bảng giữ đúng như mẫu MVP 4.5
     * (`MOS (callee)`), nên điều này được nói MỘT lần ở đây. Chỉ nêu khi có ít nhất một giá trị
     * đo được — toàn N/A thì câu này không có đối tượng.
     */
    private static java.util.Optional<String> endOfCallSnapshotNote(CallMetrics metrics) {
        boolean anyMeasured = metrics.metrics().stream()
                .filter(m -> m.key() == MetricKey.MOS || m.key() == MetricKey.RTT || m.key() == MetricKey.JITTER)
                .anyMatch(m -> m.value() instanceof MetricValue.Present);
        return anyMeasured
                ? java.util.Optional.of("MOS, RTT, jitter là giá trị lúc kết thúc cuộc gọi (mẫu stats cuối),"
                        + " không phải của cả cuộc")
                : java.util.Optional.empty();
    }

    private static boolean hasReason(CallMetrics metrics, MetricKey key, Leg leg, String reason) {
        return metrics.find(key, leg)
                .map(CallMetric::value)
                .filter(v -> v instanceof MetricValue.NotAvailable n && n.reason().equals(reason))
                .isPresent();
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
