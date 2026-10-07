package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.report.ReportBuilder;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.RelativeTrack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Input Sanitizer → Safe AI Context (MVP mục 6.1 T5 + T7, sơ đồ mục 6.2).
 *
 * Dựng context từ kết quả pipeline Sprint 1 rồi cho MỌI chuỗi tự do đi qua Sanitizer. Đây là nơi
 * duy nhất tạo được {@link AiContext}.
 *
 * Minimum necessary context (MVP mục 3.3) — cái gì gửi và cái gì KHÔNG:
 * - Gửi: kết luận của rule, evidence (sự kiện chính của timeline, tối đa 40), bộ chỉ số đã tính,
 *   danh sách log có mặt, giới hạn dữ liệu, taxonomy (định nghĩa + triệu chứng).
 * - Không gửi: raw log, thuộc tính thô của event, timeline đầy đủ (tới ~7 000 sự kiện mỗi cuộc),
 *   Call-ID (AI không cần để phân tích), phần hiệu chỉnh của taxonomy (nêu Call-ID của data mẫu),
 *   đề xuất và tóm tắt viết sẵn của rule (AI sẽ chép lại thay vì phân tích).
 *
 * Intent không gửi: câu OUT_OF_SCOPE dừng ở Request Parser, nên mọi câu tới đây đều là phân tích
 * cuộc gọi; chỉ còn focus là thông tin thêm.
 *
 * Thuần domain: không I/O, cùng input ra cùng context.
 */
public class AiContextBuilder {

    private final SensitiveDataSanitizer sanitizer;

    public AiContextBuilder(SensitiveDataSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    public AiContext build(String requestId, String question, String focus, CallTimeline timeline,
                           CallMetrics metrics, List<Evidence> evidence, RuleVerdict rule,
                           IssueTaxonomy taxonomy) {
        Cleaner clean = new Cleaner(sanitizer.newSession());
        // Nhớ trước mọi định danh có cấu trúc của cuộc gọi (appUserId, csid… trong thuộc tính event),
        // để chúng bị thay cả khi lọt vào mô tả evidence, câu lý do hay chính câu hỏi.
        timeline.allEvents().forEach(e -> e.attributes().forEach(clean.session::remember));

        AiContext.Payload payload = new AiContext.Payload(
                ruleSummary(rule, clean),
                evidence.stream().map(e -> new AiContext.EvidenceItem(e.id(), e.source().name(),
                        e.event().leg().name(), e.timeLabel(), clean.text(e.description()),
                        clean.text(e.event().sourceRef().citation()))).toList(),
                metrics.metrics().stream().map(m -> new AiContext.MetricItem(clean.text(m.label()),
                        clean.text(display(m)), m.key().source().name())).toList(),
                logs(timeline, clean),
                ReportBuilder.dataLimitations(timeline, metrics, rule, taxonomy).stream().map(clean::text).toList(),
                taxonomy.definitions().stream().map(d -> new AiContext.CategoryItem(d.id().name(),
                        clean.text(d.definition().strip()), d.symptoms().stream().map(clean::text).toList(),
                        d.calibration().isValidated())).toList());

        return new AiContext(requestId, clean.text(question), clean.text(focus), payload, clean.findings);
    }

    private static AiContext.RuleSummary ruleSummary(RuleVerdict rule, Cleaner clean) {
        return new AiContext.RuleSummary(rule.verdict().name(), rule.qualityFlag(),
                rule.issueCategory() == null ? AiAnalysis.NO_ISSUE : rule.issueCategory().name(),
                rule.confidence().name(), clean.text(rule.reasoning()), clean.text(rule.causeBasis()));
    }

    /** Như cột "Giá trị" của report: N/A kèm lý do, và chỉ số proxy ghi rõ là proxy (MVP mục 4.3). */
    private static String display(CallMetric metric) {
        return metric.value().display() + (metric.key().isProxy() ? " [proxy]" : "");
    }

    /**
     * Mỗi file log một dòng, xếp theo nguồn rồi tên file. Leg chỉ ghi khi cả file thuộc một leg —
     * signaling mang sự kiện của cả hai bên.
     */
    private static List<AiContext.LogFile> logs(CallTimeline timeline, Cleaner clean) {
        Map<String, List<CanonicalEvent>> mainByFile = timeline.mainTrack().stream()
                .sorted(Comparator.comparing((CanonicalEvent e) -> e.source().ordinal())
                        .thenComparing(e -> e.sourceRef().fileName()))
                .collect(Collectors.groupingBy(e -> e.sourceRef().fileName(), LinkedHashMap::new, Collectors.toList()));

        List<AiContext.LogFile> logs = new ArrayList<>();
        mainByFile.forEach((file, events) -> {
            Set<Leg> legs = events.stream().map(CanonicalEvent::leg).collect(Collectors.toSet());
            logs.add(new AiContext.LogFile(clean.text(file), events.getFirst().source().name(),
                    legs.size() == 1 ? legs.iterator().next().name() : null, "ABSOLUTE"));
        });
        timeline.relativeTracks().stream()
                .sorted(Comparator.comparing(RelativeTrack::fileName))
                .forEach(t -> logs.add(new AiContext.LogFile(clean.text(t.fileName()),
                        LogSource.WEBRTC.name(), t.leg().name(), "RELATIVE")));
        return logs;
    }

    /** Một phiên Sanitizer cho cả context, kèm bộ đếm số chỗ đã thay. */
    private static final class Cleaner {

        private final SensitiveDataSanitizer.Session session;
        private final Map<String, Integer> findings = new LinkedHashMap<>();

        private Cleaner(SensitiveDataSanitizer.Session session) {
            this.session = session;
        }

        private String text(String text) {
            if (text == null) {
                return null;
            }
            SensitiveDataSanitizer.Result result = session.sanitize(text);
            result.findings().forEach((id, n) -> findings.merge(id, n, Integer::sum));
            return result.text();
        }
    }
}
