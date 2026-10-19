package io.hason.callanalysis.domain.report;

import io.hason.callanalysis.domain.confidence.ConfidenceAssessment;
import io.hason.callanalysis.domain.confidence.ConfidencePolicy;
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
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
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
 * Ráp report từ kết quả các bước trước. Sprint 1 dùng hoàn toàn rule; Sprint 2 thay phần
 * tóm tắt / phân tích / đề xuất bằng chữ của AI nhưng giữ nguyên bố cục này.
 */
public class ReportBuilder {

    /**
     * Report cuối của luồng có AI (MVP mục 3.2: AI chỉ điền field, code dựng bố cục).
     *
     * Ai viết phần nào:
     * - AI dùng được và khớp rule: tóm tắt, phân tích, đề xuất của AI; kết luận, chỉ số, evidence của code.
     * - AI lệch rule (G04): tóm tắt và đề xuất do CODE viết — chữ của AI bênh vực kết luận đã bị bác,
     *   in ra sẽ mâu thuẫn với dòng "Kết luận". Phần phân tích của AI vẫn giữ để người kiểm tra đọc.
     * - Fallback: toàn bộ từ rule, kèm lý do ở "Giới hạn dữ liệu".
     *
     * Độ tin cậy: {@link ConfidencePolicy#forReport} — AI khớp rule giữ mức của rule (đã xét evidence và file
     * thiếu); AI lệch rule → LOW; không có AI để đối chiếu (degraded) → tối đa MEDIUM.
     *
     * @param extraLimitations ghi chú từ bước trước pipeline (ví dụ câu hỏi nêu hai Call-ID)
     */
    public CallReport build(CallTimeline timeline, CallMetrics metrics, List<Evidence> evidence,
                            RuleVerdict rule, IssueTaxonomy taxonomy, ReportDecision decision,
                            List<String> extraLimitations) {
        boolean verdictChanged = decision.verdict() != rule.verdict();
        boolean sameConclusion = !verdictChanged && decision.issueCategory() == rule.issueCategory()
                && decision.qualityFlag() == rule.qualityFlag();
        // Kết luận đổi (lệch verdict → UNKNOWN) thì căn cứ riêng của rule (kiểu TURN hỏng, causeBasis)
        // không còn đúng với kết luận mới.
        // Kết luận bị đổi thì "vì sao kết luận" là vì AI và rule không thống nhất, không phải lý do của rule.
        String effectiveReasoning = verdictChanged
                ? "AI và rule không thống nhất (" + String.join("; ", decision.reviewNotes()) + "). Lý do của rule: "
                        + rule.reasoning()
                : rule.reasoning();
        RuleVerdict effective = sameConclusion ? rule : new RuleVerdict(decision.verdict(), decision.qualityFlag(),
                decision.issueCategory(), rule.confidence(), effectiveReasoning, rule.dataLimitations(),
                TurnFailure.NONE, null, rule.confidenceReasons());
        CallReport base = build(timeline, metrics, evidence, effective, taxonomy);

        List<String> limitations = new ArrayList<>(base.dataLimitations());
        limitations.addAll(extraLimitations == null ? List.of() : extraLimitations);
        ConfidenceAssessment confidence = ConfidencePolicy.forReport(rule.confidenceAssessment(), aiCheck(decision),
                decision.fallbackReason());
        String summary = base.summary();
        List<String> suggestions = base.suggestions();
        List<String> aiSuggestions = List.of();
        String analysis = null;

        if (decision.needsReview()) {
            limitations.add("Cần kiểm tra: " + String.join("; ", decision.reviewNotes()));
            if (verdictChanged) {
                summary = "Không đủ căn cứ để kết luận: " + String.join("; ", decision.reviewNotes())
                        + ". Lý do của rule: " + rule.reasoning() + ".";
                suggestions = new ArrayList<>(standardSuggestions(rule));
                suggestions.add("Đối chiếu evidence bên dưới với kết luận của rule và của AI để xác định kết luận đúng.");
            }
            analysis = blankToNull(decision.analysis());
        } else if (decision.source() == CallReport.AnalysisSource.AI) {
            // Ca FAIL: tóm tắt do CODE viết từ lý do của rule, AI chỉ viết phần phân tích. gpt-4o-mini mở đầu tóm tắt
            // ca FAIL bằng "Cuộc gọi không thiết lập được…" 10/10 lần ở 70C388AD và FE4EF5E7 — hai cuộc ĐÃ thiết lập
            // rồi mới mất dữ liệu — dù prompt (9a) và định nghĩa taxonomy đều nói ngược lại. MVP 3.2: phần code tính
            // được thì không giao cho AI; câu "vì sao hỏng" chính là lý do của rule.
            summary = base.verdict() == Verdict.FAIL || isBlank(decision.summary()) ? summary : decision.summary();
            // MVP 7.1 T2 "bộ đề xuất chuẩn theo issue category": đề xuất của rule luôn giữ, AI chỉ BỔ SUNG.
            // Trước Sprint 3 T2 đề xuất AI thay hẳn — với 703100CF (chưa request TURN nào rời máy) AI vẫn khuyên
            // "kiểm tra cấu hình TURN server".
            aiSuggestions = additionalSuggestions(decision.suggestions(), suggestions);
            analysis = blankToNull(decision.analysis());
        } else if (decision.degraded()) {
            limitations.add("Phân tích bằng AI không dùng được (" + decision.fallbackReason()
                    + "): report dựng từ rule");
        }

        return new CallReport(base.callId(), base.verdict(), base.qualityFlag(), base.issueCategory(),
                confidence.level(), confidence.reasons(), summary, base.evidence(), base.metrics(), base.possibleCauses(), suggestions,
                List.copyOf(new LinkedHashSet<>(limitations)), decision.degraded(), analysis,
                decision.source(), decision.needsReview(),
                decision.degraded() ? decision.fallbackReason() : null,
                analysis == null ? List.of() : decision.citedEvidenceIds(), aiSuggestions);
    }

    /** Đề xuất AI có thêm được gì: bỏ câu trùng bộ chuẩn (so sau khi bỏ dấu câu, hoa thường), tối đa ba câu. */
    static final int MAX_AI_SUGGESTIONS = 3;

    static List<String> additionalSuggestions(List<String> fromAi, List<String> standard) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        standard.forEach(s -> seen.add(normalized(s)));
        List<String> extra = new ArrayList<>();
        for (String s : fromAi) {
            if (s == null || s.isBlank() || !seen.add(normalized(s))) {
                continue;
            }
            extra.add(s.strip());
            if (extra.size() == MAX_AI_SUGGESTIONS) {
                break;
            }
        }
        return extra;
    }

    private static String normalized(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").strip();
    }

    /** Cùng thứ tự với nhánh ở trên: lệch rule trước, rồi AI dùng được, rồi fallback. */
    private static ConfidencePolicy.AiCheck aiCheck(ReportDecision decision) {
        if (decision.needsReview()) {
            return ConfidencePolicy.AiCheck.DISAGREES;
        }
        if (decision.source() == CallReport.AnalysisSource.AI) {
            return ConfidencePolicy.AiCheck.AGREES;
        }
        return decision.degraded() ? ConfidencePolicy.AiCheck.UNAVAILABLE : ConfidencePolicy.AiCheck.NOT_RUN;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static String blankToNull(String text) {
        return isBlank(text) ? null : text.strip();
    }

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
                verdict.confidenceReasons(),
                summarise(timeline, verdict),
                evidenceEntries,
                metricEntries,
                possibleCauses(verdict, taxonomy, evidence),
                standardSuggestions(verdict),
                dataLimitations(timeline, metrics, verdict, taxonomy),
                false, null, CallReport.AnalysisSource.RULE, false, null, List.of(), List.of());
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

    private static CallReport.PossibleCauses possibleCauses(RuleVerdict verdict, IssueTaxonomy taxonomy,
                                                           List<Evidence> evidence) {
        List<String> basis = evidence.stream().filter(Evidence::ruleBasis).map(Evidence::id).toList();
        if (verdict.issueCategory() == null) {
            return new CallReport.PossibleCauses(null, List.of(), verdict.reasoning(), basis);
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
        return new CallReport.PossibleCauses(primary, alternatives, verdict.reasoning(), basis);
    }

    /**
     * Bộ đề xuất chuẩn theo issue category (MVP 7.1 T2) — luôn có trong report, có AI hay không. TURN_FAILURE và
     * SIGNALING_FAILURE chọn theo nguyên nhân con: cùng một category mà nguyên nhân khác thì việc cần điều tra khác.
     * Công khai để context gửi AI mang bộ chuẩn này: AI chỉ viết phần BỔ SUNG, không lặp lại.
     */
    public static List<String> standardSuggestions(RuleVerdict verdict) {
        if (verdict.issueCategory() == null) {
            return List.of("Không cần hành động thêm.");
        }
        return switch (verdict.issueCategory()) {
            case SIGNALING_FAILURE -> signalingSuggestions(verdict.signalingCause());
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

    private static List<String> signalingSuggestions(io.hason.callanalysis.domain.rule.SignalingCause cause) {
        if (cause == null) {
            return List.of(
                    "Kiểm tra log của service signaling quanh thời điểm INIT_CALL.",
                    "Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.",
                    "Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.");
        }
        return switch (cause) {
            case INIT_CALL_REJECTED -> List.of(
                    "Đối chiếu mã từ chối ở phản hồi INIT_CALL với chính sách của server (ví dụ chặn theo quyền riêng"
                            + " tư của bên nhận): đây là quyết định của server, không phải lỗi mạng.",
                    "Không cần điều tra mạng, ICE hay TURN cho cuộc gọi này.");
            case CANDIDATE_TIMEOUT -> List.of(
                    "Kiểm tra bước thu thập ICE candidate và tạo offer phía bên gọi: mạng và quyền truy cập mạng của"
                            + " app tại thời điểm gọi.",
                    "Đối chiếu dòng hết giờ chờ candidate và dòng _emitFailed trong end call log để lấy mã lỗi phía"
                            + " client.");
            case NO_INVITE -> List.of(
                    "Kiểm tra log của service signaling quanh thời điểm INIT_CALL để biết vì sao không có INVITE.",
                    "Bổ sung end call log và WebRTC log của bên gọi: chưa có dấu hiệu nào khác giải thích việc thiếu"
                            + " INVITE.");
            case FAIL_HARD -> List.of(
                    "Tra ngữ cảnh FAIL_HARD trong log của service signaling: bên nào phát, ngay sau lệnh nào.",
                    "Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.");
            case CANCELLED -> List.of(
                    "Xác định bên huỷ: đối chiếu thao tác người dùng trong end call log với thời điểm CANCEL.",
                    "Không thấy thao tác huỷ của người dùng thì kiểm tra thời gian chờ đổ chuông và timeout phía"
                            + " server.");
            case NOT_CONFIRMED -> List.of(
                    "Kiểm tra log của service signaling quanh thời điểm INVITE và OK để biết bước bắt tay dừng ở đâu.",
                    "Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.");
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

    /**
     * Mục "Giới hạn dữ liệu". Public vì AI context gửi đúng danh sách này: AI kết luận UNKNOWN
     * hay hạ tin cậy dựa trên cùng những thiếu hụt mà report sẽ in ra.
     */
    public static List<String> dataLimitations(CallTimeline timeline, CallMetrics metrics,
                                               RuleVerdict verdict, IssueTaxonomy taxonomy) {
        Set<String> limitations = new LinkedHashSet<>(verdict.dataLimitations());

        limitations.addAll(missingClientLogs(timeline));
        limitations.addAll(multiDeviceLegs(timeline));
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
     * Leg có nhiều end call log (nhiều thiết bị cùng đổ chuông): nói rõ chỉ số lấy từ máy nào, và vì sao máy kia
     * không có âm thanh — đọc NGUYÊN VĂN dòng `_emitFailed` / `_emitBye` của máy đó, không viết cứng mã 490.
     * Thiếu dòng này thì report fc3187b4 chỉ nói "leg callee chưa nhận được gói audio nào", và AI kết luận ngược
     * lại với chính evidence (xem {@link CallTimeline#callSummary}).
     */
    static List<String> multiDeviceLegs(CallTimeline timeline) {
        List<String> notes = new ArrayList<>();
        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            List<String> files = timeline.endCallFiles(leg);
            if (files.size() < 2) {
                continue;
            }
            String name = leg.name().toLowerCase();
            java.util.Optional<String> chosen = timeline.audioFile(leg);
            StringBuilder note = new StringBuilder("Leg " + name + " có " + files.size()
                    + " end call log (nhiều thiết bị cùng đổ chuông): ");
            note.append(chosen.map(f -> "chỉ số " + name + " lấy từ " + f + " (thiết bị có âm thanh)")
                    .orElse("không thiết bị nào có âm thanh"));
            for (String file : files) {
                if (chosen.isPresent() && chosen.get().equals(file)) {
                    continue;
                }
                note.append("; ").append(file).append(" không có âm thanh");
                clientEnding(timeline, file).ifPresent(msg -> note.append(" (").append(msg).append(")"));
            }
            notes.add(note.toString());
        }
        return notes;
    }

    /** Dòng kết thúc phía client của một file: `_emitFailed` (thất bại / dừng) hoặc `_emitBye`. */
    private static java.util.Optional<String> clientEnding(CallTimeline timeline, String file) {
        return timeline.mainTrack().stream()
                .filter(e -> e.sourceRef().fileName().equals(file))
                .map(e -> e.attribute("msg"))
                .filter(m -> m != null && (m.startsWith("_emitFailed") || m.startsWith("_emitBye")))
                .findFirst();
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
