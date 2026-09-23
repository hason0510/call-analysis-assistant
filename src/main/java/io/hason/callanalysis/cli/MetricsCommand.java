package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricsCalculator;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.CallLogNormalizationService;
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
 * In bộ chỉ số của một hoặc nhiều cuộc gọi.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--metrics=../ai20k_sample/success/DE7DD314-..."
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--metrics-all=../ai20k_sample"
 */
@Component
public class MetricsCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MetricsCommand.class);
    private static final String ONE = "metrics";
    private static final String ALL = "metrics-all";

    private final CallFolderReader reader;
    private final CallLogNormalizationService service;
    private final ConfigurableApplicationContext context;
    private final MetricsCalculator calculator = new MetricsCalculator();

    public MetricsCommand(CallFolderReader reader,
                          CallLogNormalizationService service,
                          ConfigurableApplicationContext context) {
        this.reader = reader;
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (args.containsOption(ONE)) {
            printOne(Path.of(args.getOptionValues(ONE).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
        if (args.containsOption(ALL)) {
            printAll(Path.of(args.getOptionValues(ALL).getFirst()).toAbsolutePath().normalize());
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private void printOne(Path folder) {
        CallMetrics metrics = metricsOf(folder);
        log.info("Call-ID: {}", metrics.callId());
        log.info("{}", String.format("%-34s %s", "CHỈ SỐ", "GIÁ TRỊ"));
        log.info("{}", "-".repeat(96));
        for (CallMetric m : metrics.metrics()) {
            log.info("{}", String.format("%-34s %s", m.label(), m.value().display()));
        }
        log.info("{}", "-".repeat(96));
        log.info("Tính được {} / {} chỉ số", metrics.availableCount(), metrics.metrics().size());
    }

    private void printAll(Path root) throws IOException {
        log.info("{}", String.format("%-10s %-10s %10s %10s %10s %9s %8s %8s",
                "NHÓM", "CALL-ID", "SETUP", "RING", "DURATION", "END", "MOS", "LOSS%"));
        log.info("{}", "-".repeat(84));
        for (Path folder : findCallFolders(root)) {
            CallMetrics m = metricsOf(folder);
            log.info("{}", String.format("%-10s %-10s %10s %10s %10s %9s %8s %8s",
                    folder.getParent().getFileName(),
                    folder.getFileName().toString().substring(0, 8),
                    shortValue(m, io.hason.callanalysis.domain.metrics.MetricKey.SETUP_TIME),
                    shortValue(m, io.hason.callanalysis.domain.metrics.MetricKey.RINGING_TIME),
                    shortValue(m, io.hason.callanalysis.domain.metrics.MetricKey.CONNECTED_DURATION),
                    shortValue(m, io.hason.callanalysis.domain.metrics.MetricKey.TERMINATED_BY),
                    bestLegValue(m, io.hason.callanalysis.domain.metrics.MetricKey.MOS),
                    bestLegValue(m, io.hason.callanalysis.domain.metrics.MetricKey.PACKET_LOSS)));
        }
    }

    private CallMetrics metricsOf(Path folder) {
        CallTimeline timeline = service.buildTimeline(
                folder.getFileName().toString(), reader.readAll(folder));
        return calculator.calculate(timeline);
    }

    private static String shortValue(CallMetrics metrics,
                                     io.hason.callanalysis.domain.metrics.MetricKey key) {
        return metrics.find(key)
                .map(m -> m.value() instanceof io.hason.callanalysis.domain.metrics.MetricValue.NotAvailable
                        ? "N/A" : m.value().display().replace(" ms", "").replace(" lần", ""))
                .orElse("-");
    }

    private static String bestLegValue(CallMetrics metrics,
                                       io.hason.callanalysis.domain.metrics.MetricKey key) {
        return metrics.metrics().stream()
                .filter(m -> m.key() == key && m.value().isPresent())
                .findFirst()
                .map(m -> m.value().display().replace(" %", "").replace(" ms", ""))
                .orElse("N/A");
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
