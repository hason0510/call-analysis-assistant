package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiContextBuilder;
import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.evidence.EvidenceEngine;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.guardrail.GuardrailResult;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportDecision;
import io.hason.callanalysis.domain.report.ReportBuilder;
import io.hason.callanalysis.domain.rule.RuleSignals;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.rule.RuleVerdictEngine;
import io.hason.callanalysis.domain.rule.SignalExtractor;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Chạy toàn bộ pipeline phân tích của Sprint 1: chuẩn hoá -> timeline -> chỉ số
 * -> tín hiệu -> verdict theo rule -> evidence -> report.
 *
 * Sprint 2 sẽ chèn thêm Sanitizer, AI Analysis và Guardrails vào giữa bước evidence
 * và bước dựng report; bố cục report giữ nguyên.
 */
@Service
public class AnalyzeCallService {

    private final CallLogNormalizationService normalizationService;
    private final TaxonomyLoader taxonomyLoader;
    private final SensitiveDataSanitizer sanitizer;

    private final MetricsCalculator metricsCalculator = new MetricsCalculator();
    private final SignalExtractor signalExtractor = new SignalExtractor();
    private final RuleVerdictEngine verdictEngine = new RuleVerdictEngine();
    private final EvidenceEngine evidenceEngine = new EvidenceEngine();
    private final ReportBuilder reportBuilder = new ReportBuilder();
    private final AiContextBuilder aiContextBuilder;

    public AnalyzeCallService(CallLogNormalizationService normalizationService,
                              TaxonomyLoader taxonomyLoader,
                              SensitiveDataSanitizer sanitizer) {
        this.normalizationService = normalizationService;
        this.taxonomyLoader = taxonomyLoader;
        this.sanitizer = sanitizer;
        this.aiContextBuilder = new AiContextBuilder(sanitizer);
    }

    public record Analysis(CallTimeline timeline, CallMetrics metrics, RuleSignals signals,
                           RuleVerdict verdict, List<Evidence> evidence, CallReport report) {}

    public Analysis analyze(String callId, Map<String, List<String>> attachedFiles) {
        return analyze(normalizationService.buildTimeline(callId, attachedFiles));
    }

    public Analysis analyze(String callId, List<AttachedFile> attachedFiles) {
        return analyze(normalizationService.buildTimeline(callId, attachedFiles));
    }

    private Analysis analyze(CallTimeline timeline) {
        CallMetrics metrics = metricsCalculator.calculate(timeline);
        RuleSignals signals = signalExtractor.extract(timeline);
        RuleVerdict verdict = verdictEngine.decide(signals);
        List<Evidence> evidence = evidenceEngine.collect(timeline, signalExtractor.basis(timeline));
        CallReport report = sanitize(reportBuilder.build(timeline, metrics, evidence, verdict,
                taxonomyLoader.load()), timeline);

        return new Analysis(timeline, metrics, signals, verdict, evidence, report);
    }

    /**
     * Safe AI Context cho một lần phân tích (Input Sanitizer, MVP mục 6.2) — đầu vào của
     * {@link AiVerdictService#decide}.
     *
     * @param focus trọng tâm câu hỏi do Request Parser trích; null khi không có
     */
    public AiContext aiContext(Analysis analysis, String requestId, String question, String focus) {
        return aiContextBuilder.build(requestId, question, focus, analysis.timeline(), analysis.metrics(),
                analysis.evidence(), analysis.verdict(), taxonomyLoader.load());
    }

    /**
     * Report cuối của luồng có AI: kết luận sau AI + Guardrails (hoặc fallback), qua Output Sanitizer.
     *
     * @param extraLimitations ghi chú của Request Parser (ví dụ câu hỏi nêu hai Call-ID)
     */
    public CallReport finalReport(Analysis analysis, AiVerdictService.Outcome outcome, List<String> extraLimitations) {
        AiAnalysis ai = outcome.ai();
        boolean aiUsed = outcome.source() == AiVerdictService.Source.AI;
        ReportDecision decision = new ReportDecision(
                aiUsed ? CallReport.AnalysisSource.AI : CallReport.AnalysisSource.RULE,
                outcome.verdict(), outcome.qualityFlag(), outcome.issueCategory(), outcome.needsReview(),
                outcome.fallbackReason(),
                aiUsed ? ai.summary() : null, aiUsed ? ai.analysis() : null,
                aiUsed ? ai.suggestions() : List.of(),
                outcome.needsReview() ? outcome.guardrail().violations().stream()
                        .map(GuardrailResult.Violation::detail).toList() : List.of(),
                aiUsed ? ai.evidenceIds() : List.of());
        return sanitize(reportBuilder.build(analysis.timeline(), analysis.metrics(), analysis.evidence(),
                analysis.verdict(), taxonomyLoader.load(), decision, extraLimitations), analysis.timeline());
    }

    /**
     * Output Sanitizer cho report (MVP mục 6.2): mọi chuỗi tự do trong report đi qua sanitizer.
     *
     * Trước tiên nhớ mọi định danh có cấu trúc của cuộc gọi (appUserId, csid, service… trong
     * thuộc tính event), để chúng bị thay cả khi xuất hiện trong mô tả evidence hay giới hạn dữ liệu.
     * Verdict, category, Call-ID và trích dẫn dòng log không đổi — chỉ chữ tự do bị làm sạch.
     */
    private CallReport sanitize(CallReport r, CallTimeline timeline) {
        SensitiveDataSanitizer.Session session = sanitizer.newSession();
        timeline.allEvents().forEach(e -> e.attributes().forEach(session::remember));
        java.util.function.UnaryOperator<String> clean = s -> s == null ? null : session.sanitize(s).text();
        return new CallReport(r.callId(), r.verdict(), r.qualityFlag(), r.issueCategory(), r.confidenceLevel(),
                clean.apply(r.summary()),
                r.evidence().stream().map(e -> new CallReport.EvidenceEntry(e.id(), e.source(), e.timestamp(),
                        clean.apply(e.description()), e.sourceRef())).toList(),
                r.metrics().stream().map(m -> new CallReport.MetricEntry(m.name(), clean.apply(m.value()), m.unit(),
                        clean.apply(m.naReason()), m.source())).toList(),
                r.possibleCauses() == null ? null : new CallReport.PossibleCauses(
                        clean.apply(r.possibleCauses().primary()),
                        r.possibleCauses().alternatives().stream().map(clean).toList()),
                r.suggestions().stream().map(clean).toList(),
                r.dataLimitations().stream().map(clean).toList(),
                r.degraded(), clean.apply(r.analysis()), r.analysisSource(), r.needsReview(), r.fallbackReason(),
                r.citedEvidenceIds());
    }
}
