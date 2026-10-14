package io.hason.callanalysis.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.evaluation.EvaluationReportRenderer;
import io.hason.callanalysis.domain.evaluation.EvaluationRun;
import io.hason.callanalysis.domain.evaluation.EvaluationScorer;
import io.hason.callanalysis.domain.evaluation.EvaluationSummary;
import io.hason.callanalysis.domain.evaluation.RunAlignment;
import io.hason.callanalysis.infrastructure.evaluation.BenchmarkCaseLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chấm lại một lượt Evaluation Runner từ file .json của nó — không gọi AI, không cần Elasticsearch, không tốn tiền.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--rescore=target/evaluation/evaluation-<thời điểm>.json"
 *
 * Tham số:
 *   --rescore=<file .json>   bắt buộc; file kết quả của --evaluate
 *   --cases=<file case YAML> đáp án dùng để chấm; mặc định đúng file case ghi trong .json ("File case")
 *   --out=<thư mục>          nơi ghi kết quả; mặc định cạnh file .json
 *
 * Dùng khi: mentor bổ sung nhãn (expected_issue_category, expected_intent…) cho tập đã chạy; hoặc đổi cách tính
 * một metric. Câu trả lời của hệ thống giữ nguyên như lúc chạy; chỉ đáp án và cách chấm là mới. Câu hỏi trong file
 * case phải giữ đúng thứ tự — lần chạy được ghép theo case_id + số thứ tự câu ({@link RunAlignment}).
 *
 * Kết quả: <tên file gốc>-rescored-<thời điểm>.md và .json.
 */
@Component
public class RescoreCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RescoreCommand.class);
    private static final String RESCORE = "rescore";
    private static final String CASES = "cases";
    private static final String OUT = "out";
    private static final String CASE_FILE_KEY = "File case";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BenchmarkCaseLoader loader;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;

    public RescoreCommand(BenchmarkCaseLoader loader, ObjectMapper mapper, ConfigurableApplicationContext context) {
        this.loader = loader;
        this.mapper = mapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption(RESCORE)) {
            return;
        }
        Path input = Path.of(args.getOptionValues(RESCORE).getFirst()).toAbsolutePath().normalize();
        JsonNode root = mapper.readTree(input.toFile());
        Map<String, String> original = mapper.convertValue(root.path("setup"), new TypeReference<LinkedHashMap<String, String>>() {});
        int repeat = root.path("repeat").asInt(1);
        List<EvaluationRun> runs = mapper.convertValue(root.path("runs"), new TypeReference<List<EvaluationRun>>() {});
        List<String> previousErrors = mapper.convertValue(root.path("summary").path("caseErrors"),
                new TypeReference<List<String>>() {});

        String casesArg = args.containsOption(CASES) ? args.getOptionValues(CASES).getFirst() : original.get(CASE_FILE_KEY);
        if (casesArg == null) {
            log.error("File kết quả không ghi \"{}\" — truyền --cases=<file case YAML>", CASE_FILE_KEY);
            System.exit(SpringApplication.exit(context, () -> 2));
        }
        Path casesFile = Path.of(casesArg).toAbsolutePath().normalize();
        BenchmarkCaseLoader.Loaded loaded = loader.load(casesFile);
        loaded.errors().forEach(e -> log.warn("Case lỗi: {}", e));

        RunAlignment.Aligned aligned = RunAlignment.align(loaded.cases(), runs,
                previousErrors == null ? List.of() : previousErrors);
        EvaluationSummary summary = new EvaluationScorer().score(aligned.cases(), aligned.runs(), repeat, aligned.errors());

        Map<String, String> setup = new LinkedHashMap<>(original);
        setup.put("Chấm lại", LocalDateTime.now().withNano(0) + " từ " + input.getFileName()
                + " (" + aligned.runs().size() + "/" + runs.size() + " lần chạy ghép được; không gọi lại AI)");
        setup.put("Đáp án chấm lại", casesFile.toString());
        String markdown = new EvaluationReportRenderer().render(summary, setup);

        Path outDir = args.containsOption(OUT) ? Path.of(args.getOptionValues(OUT).getFirst()).toAbsolutePath().normalize()
                : input.getParent();
        Files.createDirectories(outDir);
        String base = input.getFileName().toString().replaceFirst("\\.json$", "")
                + "-rescored-" + LocalDateTime.now().format(STAMP);
        Path md = outDir.resolve(base + ".md");
        Path json = outDir.resolve(base + ".json");
        Files.writeString(md, markdown, StandardCharsets.UTF_8);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("setup", setup);
        raw.put("repeat", repeat);
        raw.put("summary", summary);
        raw.put("runs", aligned.runs());
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), raw);

        log.info("\n{}", markdown);
        log.info("Đã ghi {} và {}", md, json);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
