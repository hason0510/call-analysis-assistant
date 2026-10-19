package io.hason.callanalysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.batch.BatchReportRenderer;
import io.hason.callanalysis.domain.batch.BatchResult;
import io.hason.callanalysis.domain.batch.SourceGroup;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.es.ElasticsearchBatchResultSink;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.infrastructure.file.CallLogDirectory;
import io.hason.callanalysis.service.BatchAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Phân tích hàng loạt (MVP 7.1 T10): mọi Call-ID trong Elasticsearch, thống kê phân bố verdict / issue category.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--batch-analyze"
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--batch-analyze --logs=../ai20k_sample --logs=../ai20k_new_dataset"
 *
 * Tham số:
 *   --logs=<thư mục>   tuỳ chọn, lặp được; thư mục con trùng tên Call-ID (không phân biệt hoa thường) được dùng làm log
 *                      máy. Không có thì cuộc đó chỉ có signaling — báo cáo tách riêng nhóm này.
 *   --out=<thư mục>    nơi ghi báo cáo; mặc định target/batch
 *
 * Chỉ rule, không gọi AI, không tốn tiền. Cần Elasticsearch đã nạp signaling.
 * Kết quả: batch-<thời điểm>.md (phân bố + bảng từng cuộc) và .json; index {@value ElasticsearchBatchResultSink#INDEX}
 * cho dashboard ({@code scripts/kibana/setup_dashboard.py}).
 */
@Component
public class BatchAnalyzeCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchAnalyzeCommand.class);
    private static final String BATCH = "batch-analyze";
    private static final String LOGS = "logs";
    private static final String OUT = "out";
    /** Signaling lấy từ Elasticsearch — file export trong thư mục log không phải log máy, đưa vào thì bị loại và ghi chú. */
    private static final String SIGNALING_EXPORT = "signaling.json";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BatchAnalysisService batchService;
    private final CallFolderReader reader;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;

    public BatchAnalyzeCommand(BatchAnalysisService batchService, CallFolderReader reader, ObjectMapper mapper,
                               ConfigurableApplicationContext context) {
        this.batchService = batchService;
        this.reader = reader;
        this.mapper = mapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption(BATCH)) {
            return;
        }
        List<Path> roots = args.containsOption(LOGS)
                ? args.getOptionValues(LOGS).stream().map(p -> Path.of(p).toAbsolutePath().normalize()).toList()
                : List.of();
        Path outDir = Path.of(args.containsOption(OUT) ? args.getOptionValues(OUT).getFirst() : "target/batch")
                .toAbsolutePath().normalize();

        CallLogDirectory directory;
        try {
            directory = CallLogDirectory.scan(roots);
        } catch (IllegalArgumentException e) {
            log.error("{}", e.getMessage());
            System.exit(SpringApplication.exit(context, () -> 2));
            return;
        }

        BatchAnalysisService.Batch batch;
        try {
            batch = batchService.run(callId -> directory.find(callId).map(this::clientLogs).orElse(List.of()));
        } catch (RuntimeException e) {
            log.error("Không lấy được danh sách Call-ID từ Elasticsearch ({}). Bật ES và nạp signaling:"
                    + " docker compose up -d elasticsearch, rồi --import-signaling=<thư mục>", e.getClass().getSimpleName());
            System.exit(SpringApplication.exit(context, () -> 2));
            return;
        }

        List<String> callIds = batch.results().stream().map(BatchResult::callId).toList();
        List<String> duplicates = directory.duplicates(callIds);
        duplicates.forEach(d -> log.warn("Hai thư mục cùng Call-ID: {}", d));

        Map<String, String> setup = new LinkedHashMap<>();
        setup.put("Thời điểm", LocalDateTime.now().withNano(0).toString());
        setup.put("Nguồn Call-ID", "Elasticsearch, index signaling — " + (callIds.size() + batch.errors().size())
                + " Call-ID");
        setup.put("Log máy", roots.isEmpty() ? "không dùng (không có --logs) — mọi cuộc chỉ có signaling"
                : roots + " — tìm thấy cho " + batch.summary().groups().get(SourceGroup.WITH_CLIENT_LOGS).calls()
                + "/" + callIds.size() + " cuộc");
        setup.put("Phân tích", "chỉ rule, không gọi AI (kết luận của AI chỉ có thể trùng rule hoặc bị hạ thành UNKNOWN)");
        setup.put("Elasticsearch (index " + ElasticsearchBatchResultSink.INDEX + ")", batch.sinkStatus());
        if (!batch.errors().isEmpty()) {
            setup.put("Không phân tích được", String.join("; ", batch.errors()));
        }
        if (!duplicates.isEmpty()) {
            setup.put("Thư mục trùng Call-ID", String.join("; ", duplicates));
        }
        String markdown = new BatchReportRenderer().render(batch.summary(), batch.results(), setup);

        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(STAMP);
        Path md = outDir.resolve("batch-" + stamp + ".md");
        Path json = outDir.resolve("batch-" + stamp + ".json");
        Files.writeString(md, markdown, StandardCharsets.UTF_8);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("setup", setup);
        raw.put("summary", batch.summary());
        raw.put("results", batch.results());
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), raw);

        log.info("\n{}", markdown);
        log.info("Đã ghi {} và {}", md, json);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Mọi file của thư mục trừ bản export signaling — lọc trước khi đọc, file đó có thể rất lớn. */
    private List<AttachedFile> clientLogs(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !SIGNALING_EXPORT.equalsIgnoreCase(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(reader::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + folder, e);
        }
    }
}
