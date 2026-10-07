package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.guardrail.GuardrailResult;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.AiVerdictService;
import io.hason.callanalysis.service.AnalyzeCallService;
import io.hason.callanalysis.service.RequestParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Kiểm T4 + T5 với AI THẬT trên log mẫu: Request Parser → pipeline Sprint 1 → Safe AI Context → AI →
 * Guardrails → fallback. Chỉ ghép các khối đã có; luồng đầy đủ cho web là việc của T2.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai-analyze=../ai20k_sample/fail/2D9057AA-... '--question=Vi sao goi khong duoc?'"
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai-analyze-all=../ai20k_sample/fail"
 *
 * MỖI cuộc gọi tốn hai lời gọi API (phân loại câu hỏi + phân tích). Token và độ trễ của từng lời gọi
 * nằm ở dòng log của OpenAiIntentClassifier / OpenAiAnalyzer.
 */
@Component
public class AiAnalyzeCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiAnalyzeCommand.class);
    private static final String ONE = "ai-analyze";
    private static final String ALL = "ai-analyze-all";
    private static final String QUESTION = "question";
    private static final String DEFAULT_QUESTION = "Phân tích giúp mình cuộc gọi này";

    private final CallFolderReader reader;
    private final RequestParserService requestParser;
    private final AnalyzeCallService analyzeService;
    private final AiVerdictService aiVerdictService;
    private final ConfigurableApplicationContext context;

    public AiAnalyzeCommand(CallFolderReader reader, RequestParserService requestParser,
                            AnalyzeCallService analyzeService, AiVerdictService aiVerdictService,
                            ConfigurableApplicationContext context) {
        this.reader = reader;
        this.requestParser = requestParser;
        this.analyzeService = analyzeService;
        this.aiVerdictService = aiVerdictService;
        this.context = context;
    }

    /** Kết quả một cuộc gọi, đủ để in bảng tổng. */
    private record Row(String group, String callId, String rule, String ai, String finalVerdict,
                       String guardrail, String fallback) {}

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String question = args.containsOption(QUESTION) ? args.getOptionValues(QUESTION).getFirst() : DEFAULT_QUESTION;
        if (args.containsOption(ONE)) {
            analyze(Path.of(args.getOptionValues(ONE).getFirst()).toAbsolutePath().normalize(), question, true);
            System.exit(SpringApplication.exit(context, () -> 0));
        }
        if (args.containsOption(ALL)) {
            List<Row> rows = findCallFolders(Path.of(args.getOptionValues(ALL).getFirst()).toAbsolutePath().normalize())
                    .stream().map(f -> analyze(f, question, false)).toList();
            log.info("{}", String.format("%-8s %-9s %-26s %-26s %-26s %-9s %s",
                    "NHÓM", "CALL-ID", "RULE", "AI (thô)", "CUỐI", "GUARDRAIL", "FALLBACK"));
            rows.forEach(r -> log.info("{}", String.format("%-8s %-9s %-26s %-26s %-26s %-9s %s",
                    r.group(), r.callId(), r.rule(), r.ai(), r.finalVerdict(), r.guardrail(), r.fallback())));
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private Row analyze(Path folder, String question, boolean verbose) {
        String callId = folder.getFileName().toString();
        String requestId = "cli-" + callId.substring(0, Math.min(8, callId.length()));
        String group = folder.getParent().getFileName().toString();

        ParsedRequest request = requestParser.parse(requestId, question);
        if (request.isOutOfScope()) {
            log.info("{}: {} ({}) -> {}", callId.substring(0, 8), request.intent(), request.source(),
                    ParsedRequest.OUT_OF_SCOPE_REPLY);
            return new Row(group, callId.substring(0, 8), "-", "-", "OUT_OF_SCOPE", "-", "-");
        }

        AnalyzeCallService.Analysis analysis = analyzeService.analyze(callId, reader.readAll(folder));
        AiContext aiContext = analyzeService.aiContext(analysis, requestId, request.question(), request.focus());
        AiVerdictService.Outcome outcome = aiVerdictService.decide(aiContext, analysis.verdict(), analysis.metrics());

        String rule = analysis.verdict().verdict() + "/" + analysis.verdict().issueCategory();
        AiAnalysis ai = outcome.ai();
        String aiRaw = ai == null ? "-" : ai.verdict() + "/" + ai.issueCategory();
        GuardrailResult g = outcome.guardrail();
        Row row = new Row(group, callId.substring(0, 8), rule, aiRaw,
                outcome.verdict() + "/" + outcome.issueCategory(), g == null ? "-" : g.status().name(),
                outcome.fallbackReason() == null ? "-" : outcome.fallbackReason());
        if (!verbose) {
            return row;
        }

        log.info("Intent: {} ({}){}", request.intent(), request.source(),
                request.focus() == null ? "" : " — trọng tâm: " + request.focus());
        log.info("Rule    : {} (tin cậy {})", rule, analysis.verdict().confidence());
        log.info("AI (thô): {}{}", aiRaw, ai == null ? "" : " (AI tự nhận tin cậy " + ai.confidenceLevel() + ")");
        log.info("Cuối    : {} — nguồn {}{}{}", outcome.verdict() + "/" + outcome.issueCategory(), outcome.source(),
                outcome.degraded() ? ", DEGRADED (" + outcome.fallbackReason() + ")" : "",
                outcome.needsReview() ? ", CẦN KIỂM TRA" : "");
        if (g != null) {
            log.info("Guardrails: {}", g.status());
            g.violations().forEach(v -> log.info("   {}", v));
        }
        if (ai != null) {
            log.info("Evidence AI trích: {}", ai.evidenceIds());
            log.info("Tóm tắt : {}", ai.summary());
            log.info("Phân tích: {}", ai.analysis());
            ai.suggestions().forEach(s -> log.info("Đề xuất : {}", s));
        }
        return row;
    }

    private static List<Path> findCallFolders(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("signaling.json")))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
