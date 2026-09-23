package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.parse.ParseResult;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.CallLogNormalizationService;
import io.hason.callanalysis.service.CallLogNormalizationService.CallOutcome;
import io.hason.callanalysis.service.CallLogNormalizationService.FileOutcome;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Parse log cua mot hoac nhieu cuoc goi va in tom tat.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--parse-call=../ai20k_sample/fail/2D9057AA-..."
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--parse-all=../ai20k_sample"
 */
@Component
public class ParseCallCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ParseCallCommand.class);
    private static final String ONE = "parse-call";
    private static final String ALL = "parse-all";

    private final CallFolderReader reader;
    private final CallLogNormalizationService normalizationService;
    private final ConfigurableApplicationContext context;

    public ParseCallCommand(CallFolderReader reader,
                            CallLogNormalizationService normalizationService,
                            ConfigurableApplicationContext context) {
        this.reader = reader;
        this.normalizationService = normalizationService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (args.containsOption(ONE)) {
            parseOne(Path.of(args.getOptionValues(ONE).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
        if (args.containsOption(ALL)) {
            parseAll(Path.of(args.getOptionValues(ALL).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private void parseOne(Path folder) {
        CallOutcome outcome = normalizationService.normalize(
                folder.getFileName().toString(), reader.readAll(folder));

        log.info("Call-ID: {}", outcome.callId());
        log.info("");
        printSection("signaling (tu Elasticsearch)", outcome.signaling());
        for (FileOutcome file : outcome.files()) {
            printSection(String.format("%s  [%s, leg=%s, %d dong]",
                    file.fileName(), file.detectedType(), file.leg(), file.lineCount()), file.result());
        }

        ParseResult combined = outcome.combined();
        log.info("");
        log.info("=== TONG: {} event, {} canh bao ===", combined.events().size(), combined.warnings().size());
        combined.warnings().stream().limit(10).forEach(w -> log.info("   {}", w.describe()));
    }

    private void parseAll(Path root) throws IOException {
        List<Path> calls = findCallFolders(root);
        log.info("Tim thay {} cuoc goi duoi {}", calls.size(), root);
        log.info("");
        log.info("{}", String.format("%-10s %-26s %7s %7s %9s %9s", "NHOM", "CALL-ID", "DONG", "EVENT", "CANH BAO", "KHONG NB"));
        log.info("{}", "-".repeat(76));

        int totalLines = 0;
        int totalEvents = 0;
        int totalWarnings = 0;
        int totalUnrecognised = 0;

        for (Path folder : calls) {
            Map<String, List<String>> files = reader.readAll(folder);
            CallOutcome outcome = normalizationService.normalize(folder.getFileName().toString(), files);
            ParseResult combined = outcome.combined();

            int lines = outcome.files().stream().mapToInt(FileOutcome::lineCount).sum();
            long unrecognised = outcome.files().stream()
                    .filter(f -> f.detectedType() == io.hason.callanalysis.domain.parse.DetectedLogType.UNKNOWN)
                    .count();

            totalLines += lines;
            totalEvents += combined.events().size();
            totalWarnings += combined.warnings().size();
            totalUnrecognised += (int) unrecognised;

            log.info("{}", String.format("%-10s %-26s %7d %7d %9d %9d",
                    folder.getParent().getFileName(), folder.getFileName().toString().substring(0, 8),
                    lines, combined.events().size(), combined.warnings().size(), unrecognised));
        }

        log.info("{}", "-".repeat(76));
        log.info("Tong dong file dinh kem : {}", totalLines);
        log.info("Tong event sinh ra      : {}", totalEvents);
        log.info("Tong canh bao           : {}", totalWarnings);
        log.info("File khong nhan dien duoc: {}", totalUnrecognised);
    }

    private List<Path> findCallFolders(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, 3)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("signaling.json")))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private void printSection(String label, ParseResult result) {
        log.info("--- {} ---", label);
        log.info("    {} event, {} canh bao", result.events().size(), result.warnings().size());
        Map<String, Integer> byType = new TreeMap<>();
        for (CanonicalEvent e : result.events()) {
            byType.merge(e.type().name(), 1, Integer::sum);
        }
        byType.forEach((k, v) -> log.info("      {}", String.format("%-26s %d", k, v)));
    }
}
