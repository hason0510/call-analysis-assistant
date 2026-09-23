package io.hason.callanalysis.cli;

import io.hason.callanalysis.infrastructure.es.SignalingImporter;
import io.hason.callanalysis.infrastructure.es.SignalingIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Nạp data mẫu vào Elasticsearch local.
 *
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--import-signaling=../ai20k_sample"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--import-signaling=../ai20k_sample --recreate-index"
 */
@Component
public class ImportSignalingCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportSignalingCommand.class);
    private static final String OPTION = "import-signaling";

    private final SignalingIndex index;
    private final SignalingImporter importer;
    private final ConfigurableApplicationContext context;

    public ImportSignalingCommand(SignalingIndex index,
                                  SignalingImporter importer,
                                  ConfigurableApplicationContext context) {
        this.index = index;
        this.importer = importer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int exitCode = runImport(args);
        // RestClient của Elasticsearch giữ thread non-daemon nên JVM không tự thoát.
        // Thoát tường minh để import chạy được trong script và CI.
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private int runImport(ApplicationArguments args) throws Exception {
        Path root = Path.of(args.getOptionValues(OPTION).getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.error("Không phải thư mục: {}", root);
            return 2;
        }

        if (args.containsOption("recreate-index")) {
            index.delete();
        }
        index.createIfAbsent();

        SignalingImporter.ImportReport report = importer.importFrom(root);

        log.info("---- Kết quả import ----");
        log.info("Thư mục      : {}", root);
        log.info("File đọc được: {}", report.filesRead());
        log.info("Document nạp : {}", report.documentsIndexed());
        if (report.problems().isEmpty()) {
            log.info("Vấn đề       : không có");
            return 0;
        }
        log.warn("Vấn đề       : {}", report.problems().size());
        report.problems().forEach(p -> log.warn("  - {}", p));
        return 1;
    }
}
