package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.report.ReportRenderer;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import io.hason.callanalysis.service.AnalyzeCallService;
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
 * Demo cuối cùng của Sprint 1: phân tích một cuộc gọi và in report theo mẫu mục 4.5.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--analyze=../ai20k_sample/fail/2D9057AA-..."
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--analyze-all=../ai20k_sample"
 */
@Component
public class AnalyzeCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeCommand.class);
    private static final String ONE = "analyze";
    private static final String ALL = "analyze-all";

    private final CallFolderReader reader;
    private final AnalyzeCallService analyzeService;
    private final ReportSchemaValidator validator;
    private final ConfigurableApplicationContext context;
    private final ReportRenderer renderer = new ReportRenderer();

    public AnalyzeCommand(CallFolderReader reader,
                          AnalyzeCallService analyzeService,
                          ReportSchemaValidator validator,
                          ConfigurableApplicationContext context) {
        this.reader = reader;
        this.analyzeService = analyzeService;
        this.validator = validator;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (args.containsOption(ONE)) {
            printReport(Path.of(args.getOptionValues(ONE).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
        if (args.containsOption(ALL)) {
            printSummaryTable(Path.of(args.getOptionValues(ALL).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private void printReport(Path folder) {
        AnalyzeCallService.Analysis analysis = analyzeService.analyze(
                folder.getFileName().toString(), reader.readAll(folder));
        CallReport r = analysis.report();

        // Cùng renderer với Chat API: CLI và web in ra một bố cục (mẫu MVP mục 4.5)
        log.info("\n{}", renderer.render(r));

        ReportSchemaValidator.Result result = validator.validate(r);
        log.info("Report hợp lệ theo schema v1: {}", result.valid() ? "CÓ" : "KHÔNG");
        result.errors().forEach(e -> log.warn("   {}", e));
    }

    private void printSummaryTable(Path root) throws IOException {
        log.info("{}", String.format("%-10s %-10s %-9s %-9s %-8s %-20s %-8s %-5s %s",
                "NHÓM", "CALL-ID", "KẾT LUẬN", "GR.TRUTH", "KHỚP", "VẤN ĐỀ", "TIN CẬY", "EV", "SCHEMA"));
        log.info("{}", "-".repeat(100));

        int valid = 0;
        int total = 0;
        int labelled = 0;
        int correct = 0;

        for (Path folder : findCallFolders(root)) {
            AnalyzeCallService.Analysis analysis = analyzeService.analyze(
                    folder.getFileName().toString(), reader.readAll(folder));
            CallReport r = analysis.report();
            ReportSchemaValidator.Result check = validator.validate(r);
            total++;
            if (check.valid()) {
                valid++;
            } else {
                check.errors().forEach(e -> log.warn("   {} -> {}", r.callId().substring(0, 8), e));
            }

            // Ground truth lấy từ tên thư mục cha: fail/ và success/. Thư mục for_test/
            // không có nhãn nên không tính vào accuracy.
            String group = folder.getParent().getFileName().toString();
            Verdict expected = groundTruthOf(group);
            String match = "-";
            if (expected != null) {
                labelled++;
                boolean ok = r.verdict() == expected;
                if (ok) {
                    correct++;
                }
                match = ok ? "OK" : "SAI";
            }

            log.info("{}", String.format("%-10s %-10s %-9s %-9s %-8s %-20s %-8s %-5d %s",
                    group,
                    r.callId().substring(0, 8),
                    r.verdict(),
                    expected == null ? "(không)" : expected,
                    match,
                    r.issueCategory() == null ? "-" : r.issueCategory(),
                    r.confidenceLevel(),
                    r.evidence().size(),
                    check.valid() ? "OK" : "LỖI"));
        }

        log.info("{}", "-".repeat(100));
        log.info("Verdict Accuracy         : {}/{} = {}%  (chỉ tính cuộc gọi có nhãn)",
                correct, labelled,
                labelled == 0 ? "-" : String.format("%.1f", 100.0 * correct / labelled));
        log.info("Report hợp lệ theo schema: {}/{}", valid, total);
        log.info("Cuộc gọi không có nhãn   : {}", total - labelled);
    }

    /** Ground truth duy nhất có trong data mẫu là tên thư mục fail/ và success/. */
    private static Verdict groundTruthOf(String folderName) {
        return switch (folderName) {
            case "fail" -> Verdict.FAIL;
            case "success" -> Verdict.SUCCESS;
            default -> null;
        };
    }

    private List<Path> findCallFolders(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("signaling.json")))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }
}
