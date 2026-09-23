package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.evidence.EvidenceEngine;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportBuilder;
import io.hason.callanalysis.domain.rule.RuleSignals;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.rule.RuleVerdictEngine;
import io.hason.callanalysis.domain.rule.SignalExtractor;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Chay toan bo pipeline phan tich cua Sprint 1: chuan hoa -> timeline -> chi so
 * -> tin hieu -> verdict theo rule -> evidence -> report.
 *
 * Sprint 2 se chen them Sanitizer, AI Analysis va Guardrails vao giua buoc evidence
 * va buoc dung report; bo cuc report giu nguyen.
 */
@Service
public class AnalyzeCallService {

    private final CallLogNormalizationService normalizationService;
    private final TaxonomyLoader taxonomyLoader;

    private final MetricsCalculator metricsCalculator = new MetricsCalculator();
    private final SignalExtractor signalExtractor = new SignalExtractor();
    private final RuleVerdictEngine verdictEngine = new RuleVerdictEngine();
    private final EvidenceEngine evidenceEngine = new EvidenceEngine();
    private final ReportBuilder reportBuilder = new ReportBuilder();

    public AnalyzeCallService(CallLogNormalizationService normalizationService,
                              TaxonomyLoader taxonomyLoader) {
        this.normalizationService = normalizationService;
        this.taxonomyLoader = taxonomyLoader;
    }

    public record Analysis(CallTimeline timeline, CallMetrics metrics, RuleSignals signals,
                           RuleVerdict verdict, List<Evidence> evidence, CallReport report) {}

    public Analysis analyze(String callId, Map<String, List<String>> attachedFiles) {
        CallTimeline timeline = normalizationService.buildTimeline(callId, attachedFiles);
        CallMetrics metrics = metricsCalculator.calculate(timeline);
        RuleSignals signals = signalExtractor.extract(timeline);
        RuleVerdict verdict = verdictEngine.decide(signals);
        List<Evidence> evidence = evidenceEngine.collect(timeline);
        CallReport report = reportBuilder.build(timeline, metrics, evidence, verdict,
                taxonomyLoader.load());

        return new Analysis(timeline, metrics, signals, verdict, evidence, report);
    }
}
