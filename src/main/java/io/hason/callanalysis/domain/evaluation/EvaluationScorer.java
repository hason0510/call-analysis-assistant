package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.evaluation.EvaluationSummary.AiRuleRow;
import io.hason.callanalysis.domain.evaluation.EvaluationSummary.Metric;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.request.Intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Chấm các lần chạy theo metric MVP mục 6.5. Thuần tính toán: cùng đầu vào thì cùng kết quả, từng ký tự.
 *
 * Mọi metric đều tính theo LẦN CHẠY (một câu hỏi × một lần lặp), không theo case: một case 4 câu × 5 lần
 * có 20 lần trả lời, lần nào sai cũng là một lần người dùng nhận kết luận sai.
 *
 * Metric không đo được (thiếu nhãn, chưa có lần chạy phù hợp) ghi "N/A (lý do)", không ghi 0 % —
 * cùng nguyên tắc N/A của MVP mục 4.3.
 */
public final class EvaluationScorer {

    private static final String REQUIRED = "Bắt buộc";
    private static final String REPORTED = "Báo cáo";

    public EvaluationSummary score(List<BenchmarkCase> cases, List<EvaluationRun> runs, int repeat,
                                   List<String> caseErrors) {
        Map<String, BenchmarkCase> byId = new LinkedHashMap<>();
        cases.forEach(c -> byId.put(c.caseId(), c));

        // Lần chạy của câu hỏi phân tích — câu được đánh dấu OUT_OF_SCOPE không có verdict để chấm.
        List<EvaluationRun> analysis = runs.stream().filter(r -> question(byId, r).expectsAnalysis()).toList();
        List<EvaluationRun> reports = runs.stream().filter(EvaluationRun::isReport).toList();

        List<Metric> metrics = new ArrayList<>();
        metrics.add(verdictAccuracy(byId, analysis));
        metrics.add(ratio("Metric Correctness", reports, r -> Boolean.TRUE.equals(r.metricsMatchCalculator()),
                "100%", REQUIRED, "bảng chỉ số của report cuối so với bảng dựng thẳng từ Metrics Calculator;"
                        + " con số trong chữ AI kiểm ở Unsupported Claim Rate",
                "chưa có report nào"));
        metrics.add(inputLeakage(runs));
        metrics.add(outputLeakage(runs));
        metrics.add(categoryAccuracy(byId, analysis));
        metrics.add(consistency(analysis, repeat));
        metrics.add(ratio("Template Compliance", reports, r -> Boolean.TRUE.equals(r.templateCompliant()),
                "100%", REPORTED, "bản Markdown đủ 5 dòng đầu và 5 mục, đúng thứ tự mẫu 4.5", "chưa có report nào"));
        metrics.add(intentAccuracy(runs));
        metrics.add(unsupportedClaims(runs));
        metrics.add(ratio("Unsupported Claim Rate (report cuối)",
                reports.stream().filter(r -> "AI".equals(r.analysisSource())).toList(),
                EvaluationRun::hasUnsupportedClaim, "0%", REPORTED,
                "report có phần phân tích của AI mà vẫn chứa G01 / G05 — người dùng thấy số bịa",
                "không report nào dùng phần phân tích của AI"));
        metrics.add(ratio("Pipeline Success Rate", analysis, r -> r.succeeded() && r.isReport(), "≥ 95%", REPORTED,
                "câu hỏi phân tích nhận được report hợp lệ (đã qua schema, kể cả degraded); câu đánh dấu"
                        + " OUT_OF_SCOPE không tính; câu phân tích bị từ chối nhầm hay lỗi tính là thất bại",
                "không có câu hỏi phân tích nào"));
        metrics.add(latency(reports));
        metrics.add(tokens(runs));
        metrics.add(fallback(reports));

        List<EvaluationSummary.ViolationRow> violations = runs.stream()
                .flatMap(r -> r.violations().stream()
                        .map(v -> new EvaluationSummary.ViolationRow(r.caseId(), r.questionIndex(), r.repeat(), v)))
                .toList();
        return new EvaluationSummary(cases.size(), runs.size(), repeat, metrics, aiVsRule(byId, analysis),
                violations, caseErrors);
    }

    private static Metric verdictAccuracy(Map<String, BenchmarkCase> byId, List<EvaluationRun> analysis) {
        List<EvaluationRun> labelled = analysis.stream()
                .filter(r -> byId.get(r.caseId()).expectedVerdict() != null).toList();
        long cases = labelled.stream().map(EvaluationRun::caseId).distinct().count();
        return ratio("Verdict Accuracy", labelled,
                r -> r.isReport() && r.verdict() == byId.get(r.caseId()).expectedVerdict(), "≥ 85%", REQUIRED,
                cases + " case có expected_verdict; target của MVP đo trên held-out do mentor chạy",
                "không case nào có expected_verdict");
    }

    private static Metric categoryAccuracy(Map<String, BenchmarkCase> byId, List<EvaluationRun> analysis) {
        List<EvaluationRun> scored = analysis.stream().filter(r -> byId.get(r.caseId()).categoryScored()).toList();
        return ratio("Issue Category Accuracy", scored,
                r -> r.isReport() && r.issueCategory() == byId.get(r.caseId()).expectedIssueCategory(),
                "≥ 70%", REPORTED, "chỉ case FAIL và SUCCESS có cờ chất lượng, có expected_issue_category",
                "ground truth chưa có expected_issue_category cho case FAIL / SUCCESS có cờ");
    }

    /**
     * MVP mục 6.5: mỗi case chạy nhiều lần × nhiều cách hỏi, verdict và issue category phải giống nhau.
     * Tỉ lệ = số lần chạy trùng kết quả số đông của case ÷ tổng số lần chạy; chỉ xét case có ≥ 2 lần chạy.
     */
    private static Metric consistency(List<EvaluationRun> analysis, int repeat) {
        Map<String, List<EvaluationRun>> byCase = groupByCase(analysis);
        int agreeing = 0;
        int total = 0;
        int stableCases = 0;
        int scoredCases = 0;
        for (List<EvaluationRun> caseRuns : byCase.values()) {
            if (caseRuns.size() < 2) {
                continue;
            }
            Map<String, Long> counts = caseRuns.stream()
                    .collect(Collectors.groupingBy(EvaluationRun::outcomeKey, LinkedHashMap::new, Collectors.counting()));
            long modal = counts.values().stream().max(Long::compare).orElse(0L);
            agreeing += (int) modal;
            total += caseRuns.size();
            scoredCases++;
            if (counts.size() == 1) {
                stableCases++;
            }
        }
        if (total == 0) {
            return new Metric("Consistency", "N/A (cần ≥ 2 lần chạy mỗi case: thêm cách hỏi hoặc --repeat > 1)",
                    null, null, "≥ 95%", REPORTED, null);
        }
        return new Metric("Consistency", percent(agreeing, total), agreeing, total, "≥ 95%", REPORTED,
                "lần chạy trùng kết quả số đông (verdict + category) của case; " + stableCases + "/" + scoredCases
                        + " case giống hệt mọi lần; lặp " + repeat + " lần mỗi câu");
    }

    private static Metric intentAccuracy(List<EvaluationRun> runs) {
        List<EvaluationRun> labelled = runs.stream().filter(r -> r.expectedIntent() != null).toList();
        Predicate<EvaluationRun> correct = r -> r.succeeded() && r.intent() == r.expectedIntent();
        Metric m = ratio("Intent Accuracy", labelled, correct, "≥ 90%", REPORTED, null,
                "không câu hỏi nào có expected_intent");
        if (m.denominator() == null) {
            return m;
        }
        String bySource = labelled.stream()
                .collect(Collectors.groupingBy(r -> Objects.requireNonNullElse(r.intentSource(), "ERROR"),
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " " + e.getValue().stream().filter(correct).count() + "/" + e.getValue().size())
                .collect(Collectors.joining(", "));
        long outOfScope = labelled.stream().filter(r -> r.expectedIntent() == Intent.OUT_OF_SCOPE).count();
        return withNote(m, "theo nguồn phân loại: " + bySource + "; " + outOfScope + " lần chạy câu OUT_OF_SCOPE");
    }

    /** Lần AI trả lời mà trích evidence không tồn tại (G01) hoặc nêu số không có trong input (G05). */
    private static Metric unsupportedClaims(List<EvaluationRun> runs) {
        List<EvaluationRun> answered = runs.stream().filter(r -> r.aiVerdict() != null).toList();
        return ratio("Unsupported Claim Rate (AI trước Guardrails)", answered,
                EvaluationRun::hasUnsupportedClaim, "0%", REPORTED,
                "đầu ra thô của AI; lần vi phạm bị Guardrails chặn, report cuối lùi về rule — chi tiết ở mục"
                        + " \"Vi phạm Guardrails\"",
                "AI chưa trả lời lần nào (chưa có key, hoặc mọi lần lỗi)");
    }

    private static Metric latency(List<EvaluationRun> reports) {
        if (reports.isEmpty()) {
            return new Metric("Latency", "N/A (chưa có report nào)", null, null, "—", REPORTED, null);
        }
        List<Long> sorted = reports.stream().map(EvaluationRun::latencyMs).sorted().toList();
        return new Metric("Latency", "P50 " + percentile(sorted, 50) + " ms, P95 " + percentile(sorted, 95) + " ms",
                null, sorted.size(), "—", REPORTED,
                "end-to-end trên " + sorted.size() + " lần ra report (không tính câu bị từ chối); max "
                        + sorted.getLast() + " ms");
    }

    /**
     * Token thật provider báo, tách hai lời gọi: phân loại câu hỏi và phân tích. Không có trong bảng 6.5, nhưng
     * MVP 6.1 T11 so "accuracy, consistency, token, latency" giữa các cách dựng context — và để biết chi phí lượt chạy.
     */
    private static Metric tokens(List<EvaluationRun> runs) {
        List<TokenUsage> analysis = runs.stream().map(EvaluationRun::analysisTokens).filter(Objects::nonNull).toList();
        List<TokenUsage> intent = runs.stream().map(EvaluationRun::intentTokens).filter(Objects::nonNull).toList();
        if (analysis.isEmpty() && intent.isEmpty()) {
            return new Metric("Token", "N/A (AI chưa trả lời lần nào)", null, null, "—", "Thêm", null);
        }
        long promptTotal = sum(analysis, TokenUsage::promptTokens) + sum(intent, TokenUsage::promptTokens);
        long completionTotal = sum(analysis, TokenUsage::completionTokens) + sum(intent, TokenUsage::completionTokens);
        int calls = analysis.size() + intent.size();
        return new Metric("Token", "phân tích: " + average(analysis) + "; phân loại: " + average(intent),
                null, calls, "—", "Thêm",
                "trung bình mỗi lời gọi, số provider báo; tổng " + promptTotal + " vào + " + completionTotal
                        + " ra trên " + calls + " lời gọi; lời gọi lỗi / quá giờ không có số");
    }

    private static String average(List<TokenUsage> calls) {
        if (calls.isEmpty()) {
            return "N/A";
        }
        return Math.round((double) sum(calls, TokenUsage::promptTokens) / calls.size()) + " vào + "
                + Math.round((double) sum(calls, TokenUsage::completionTokens) / calls.size()) + " ra ("
                + calls.size() + " lời gọi)";
    }

    private static long sum(List<TokenUsage> calls, java.util.function.ToIntFunction<TokenUsage> field) {
        return calls.stream().mapToLong(field::applyAsInt).sum();
    }

    /** Không phải metric của mục 6.5 nhưng cần để đọc các số khác: AI lỗi nhiều thì đang đo rule, không đo AI. */
    private static Metric fallback(List<EvaluationRun> reports) {
        Metric m = ratio("Fallback (degraded)", reports, EvaluationRun::degraded, "—", "Thêm", null,
                "chưa có report nào");
        if (m.denominator() == null) {
            return m;
        }
        String reasons = reports.stream().filter(EvaluationRun::degraded)
                .collect(Collectors.groupingBy(EvaluationRun::fallbackReason, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining(", "));
        long review = reports.stream().filter(EvaluationRun::needsReview).count();
        return withNote(m, (reasons.isEmpty() ? "không lần nào" : reasons) + "; cần kiểm tra (AI lệch rule): " + review);
    }

    private static List<AiRuleRow> aiVsRule(Map<String, BenchmarkCase> byId, List<EvaluationRun> analysis) {
        List<AiRuleRow> rows = new ArrayList<>();
        groupByCase(analysis).forEach((caseId, caseRuns) -> {
            BenchmarkCase c = byId.get(caseId);
            rows.add(new AiRuleRow(caseId,
                    distribution(caseRuns, r -> r.ruleVerdict() == null ? null : r.ruleVerdict().name()),
                    distribution(caseRuns, EvaluationRun::aiVerdict),
                    distribution(caseRuns, r -> r.isReport() ? r.verdict().name() : r.outcomeKey()),
                    c.expectedVerdict() == null ? "(không có)" : c.expectedVerdict().name()));
        });
        return rows;
    }

    /** "FAIL" khi mọi lần như nhau; "FAIL ×14, UNKNOWN ×1" khi không; "-" khi không lần nào có giá trị. */
    static String distribution(List<EvaluationRun> runs, Function<EvaluationRun, String> value) {
        Map<String, Long> counts = runs.stream().map(value).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
        if (counts.isEmpty()) {
            return "-";
        }
        if (counts.size() == 1 && counts.values().iterator().next() == runs.size()) {
            return counts.keySet().iterator().next();
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining(", "));
    }

    /** Percentile theo hạng gần nhất (nearest-rank): luôn là một giá trị đo được thật, không nội suy. */
    static long percentile(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    private static Map<String, List<EvaluationRun>> groupByCase(List<EvaluationRun> runs) {
        return runs.stream().sorted(Comparator.comparing(EvaluationRun::caseId))
                .collect(Collectors.groupingBy(EvaluationRun::caseId, LinkedHashMap::new, Collectors.toList()));
    }

    private static BenchmarkCase.Question question(Map<String, BenchmarkCase> byId, EvaluationRun r) {
        return byId.get(r.caseId()).questions().get(r.questionIndex() - 1);
    }

    private static Metric ratio(String name, List<EvaluationRun> scope, Predicate<EvaluationRun> ok, String target,
                                String level, String note, String naReason) {
        if (scope.isEmpty()) {
            return new Metric(name, "N/A (" + naReason + ")", null, null, target, level, note);
        }
        int hit = (int) scope.stream().filter(ok).count();
        return new Metric(name, percent(hit, scope.size()), hit, scope.size(), target, level, note);
    }

    /**
     * MVP mục 6.5: "Sensitive fields đến AI ÷ Sensitive fields detected". Tử: giá trị gốc còn nguyên văn trong
     * chuỗi gửi AI; mẫu: giá trị gốc của case — cộng qua mọi lần chạy.
     */
    private static Metric inputLeakage(List<EvaluationRun> runs) {
        List<EvaluationRun> measured = runs.stream().filter(r -> r.aiInputLeaks() != null).toList();
        int detected = measured.stream().mapToInt(EvaluationRun::sensitiveValues).sum();
        int leaked = measured.stream().mapToInt(r -> total(r.aiInputLeaks())).sum();
        String note = "giá trị nhạy cảm GỐC (bộ đọc riêng, không dùng regex của Sanitizer) còn nguyên văn trong chuỗi"
                + " gửi AI, cộng qua " + measured.size() + " lần chạy" + kinds(measured, EvaluationRun::aiInputLeaks);
        if (detected == 0) {
            return new Metric("Security Leakage (input)", "N/A (không thấy giá trị nhạy cảm nào trong đầu vào)",
                    null, null, "0%", REQUIRED, note);
        }
        return new Metric("Security Leakage (input)", percent(leaked, detected), leaked, detected, "0%", REQUIRED, note);
    }

    /** MVP mục 6.5: "Sensitive values trong report, response, log" — đếm số giá trị gốc lộ ra, target 0. */
    private static Metric outputLeakage(List<EvaluationRun> runs) {
        List<EvaluationRun> measured = runs.stream().filter(r -> r.outputLeaks() != null).toList();
        if (measured.isEmpty()) {
            return new Metric("Security Leakage (output)", "N/A (chưa có lần chạy nào hoàn tất)", null, null, "0",
                    REQUIRED, null);
        }
        int leaked = measured.stream().mapToInt(r -> total(r.outputLeaks())).sum();
        return new Metric("Security Leakage (output)", String.valueOf(leaked), null, measured.size(), "0", REQUIRED,
                "giá trị gốc còn nguyên văn trong report JSON, bản render và câu trả lời; application log không quét"
                        + " ở đây (test S08)" + kinds(measured, EvaluationRun::outputLeaks));
    }

    private static int total(Map<String, Integer> leaks) {
        return leaks == null ? 0 : leaks.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** "; lộ: ip ×2, userId ×1" — chỉ loại và số đếm, không bao giờ in giá trị. */
    private static String kinds(List<EvaluationRun> runs, Function<EvaluationRun, Map<String, Integer>> leaks) {
        Map<String, Integer> byKind = new java.util.TreeMap<>();
        runs.forEach(r -> leaks.apply(r).forEach((k, v) -> byKind.merge(k, v, Integer::sum)));
        return byKind.isEmpty() ? "" : "; lộ: " + byKind.entrySet().stream()
                .map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining(", "));
    }

    private static Metric withNote(Metric m, String note) {
        return new Metric(m.name(), m.value(), m.numerator(), m.denominator(), m.target(), m.level(), note);
    }

    private static String percent(int hit, int total) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * hit / total);
    }
}
