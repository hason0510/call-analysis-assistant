package io.hason.callanalysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.evaluation.EvaluationReportRenderer;
import io.hason.callanalysis.domain.evaluation.EvaluationRun;
import io.hason.callanalysis.domain.evaluation.EvaluationScorer;
import io.hason.callanalysis.domain.evaluation.EvaluationSummary;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.infrastructure.evaluation.BenchmarkCaseLoader;
import io.hason.callanalysis.infrastructure.evaluation.CaseFileResolver;
import io.hason.callanalysis.service.EvaluationService;
import io.hason.callanalysis.service.port.SignalingSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation Runner (MVP mục 6.1 T9): chạy bộ case từ file, chấm metric mục 6.5, xuất báo cáo.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample --repeat=5"
 *
 * Tham số:
 *   --evaluate=<file case YAML>   bắt buộc; định dạng mẫu MVP mục 6.3
 *   --logs=<thư mục log>          bắt buộc; nơi chứa thư mục con trùng tên call_id của từng case
 *   --repeat=<n>                  số lần chạy mỗi câu hỏi (Consistency: 5); mặc định 1
 *   --out=<thư mục>               nơi ghi kết quả; mặc định target/evaluation
 *
 * Chạy không tốn tiền: thêm "--ai.openai.api-key=" — mọi lần lùi về rule (degraded), vẫn kiểm được
 * đọc case, tìm file, chấm điểm. Signaling lấy từ Elasticsearch: bật ES và nạp signaling trước.
 *
 * Kết quả: evaluation-<thời điểm>.md (metric + bảng AI vs Rule) và .json (từng lần chạy, để audit).
 */
@Component
public class EvaluateCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluateCommand.class);
    private static final String CASES = "evaluate";
    private static final String LOGS = "logs";
    private static final String REPEAT = "repeat";
    private static final String OUT = "out";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BenchmarkCaseLoader loader;
    private final CaseFileResolver resolver;
    private final EvaluationService evaluation;
    private final SignalingSource signaling;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;
    private final String model;
    private final boolean aiConfigured;

    public EvaluateCommand(BenchmarkCaseLoader loader, CaseFileResolver resolver, EvaluationService evaluation,
                           SignalingSource signaling, ObjectMapper mapper, ConfigurableApplicationContext context,
                           @Value("${ai.openai.model}") String model,
                           @Value("${ai.openai.api-key:}") String apiKey) {
        this.loader = loader;
        this.resolver = resolver;
        this.evaluation = evaluation;
        this.signaling = signaling;
        this.mapper = mapper;
        this.context = context;
        this.model = model;
        this.aiConfigured = apiKey != null && !apiKey.isBlank();
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!args.containsOption(CASES)) {
            return;
        }
        if (!args.containsOption(LOGS)) {
            log.error("Thiếu --logs=<thư mục log>: nơi chứa thư mục con trùng tên call_id của từng case");
            System.exit(SpringApplication.exit(context, () -> 2));
        }
        Path casesFile = Path.of(args.getOptionValues(CASES).getFirst()).toAbsolutePath().normalize();
        Path logRoot = Path.of(args.getOptionValues(LOGS).getFirst()).toAbsolutePath().normalize();
        int repeat = args.containsOption(REPEAT) ? Integer.parseInt(args.getOptionValues(REPEAT).getFirst()) : 1;
        Path outDir = Path.of(args.containsOption(OUT) ? args.getOptionValues(OUT).getFirst() : "target/evaluation")
                .toAbsolutePath().normalize();
        if (repeat < 1) {
            log.error("--repeat phải ≥ 1");
            System.exit(SpringApplication.exit(context, () -> 2));
        }

        BenchmarkCaseLoader.Loaded loaded = loader.load(casesFile);
        List<String> caseErrors = new ArrayList<>(loaded.errors());
        int questions = loaded.cases().stream().mapToInt(c -> c.questions().size()).sum();
        log.info("{} case, {} câu hỏi × {} lần = {} lần chạy. AI: {}", loaded.cases().size(), questions, repeat,
                questions * repeat, aiConfigured
                        ? model + " — tối đa " + 2 * questions * repeat + " lời gọi API (phân loại + phân tích)"
                        : "chưa có key → mọi lần lùi về rule (degraded), không tốn tiền");
        loaded.errors().forEach(e -> log.warn("Case lỗi: {}", e));
        String signalingStatus = checkSignaling(loaded.cases());

        List<BenchmarkCase> ran = new ArrayList<>();
        List<EvaluationRun> runs = new ArrayList<>();
        for (BenchmarkCase c : loaded.cases()) {
            CaseFileResolver.Resolved files = resolver.resolve(logRoot, c);
            if (files.error() != null) {
                caseErrors.add(c.caseId() + ": " + files.error() + " — đã bỏ qua case này");
                log.warn("Case lỗi: {}: {}", c.caseId(), files.error());
                continue;
            }
            ran.add(c);
            runs.addAll(evaluation.runCase(c, files.files(), rawSignaling(c.callId()), repeat));
        }

        EvaluationSummary summary = new EvaluationScorer().score(ran, runs, repeat, caseErrors);
        Map<String, String> setup = new LinkedHashMap<>();
        setup.put("Thời điểm", LocalDateTime.now().withNano(0).toString());
        setup.put("File case", casesFile.toString());
        setup.put("Thư mục log", logRoot.toString());
        setup.put("Signaling", signalingStatus);
        setup.put("AI", aiConfigured ? model : "không dùng (chưa có key) — mọi report từ rule");
        String markdown = new EvaluationReportRenderer().render(summary, setup);

        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(STAMP);
        Path md = outDir.resolve("evaluation-" + stamp + ".md");
        Path json = outDir.resolve("evaluation-" + stamp + ".json");
        Files.writeString(md, markdown, StandardCharsets.UTF_8);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("setup", setup);
        raw.put("repeat", repeat);
        raw.put("summary", summary);
        raw.put("runs", runs);
        mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), raw);

        log.info("\n{}", markdown);
        log.info("Đã ghi {} và {}", md, json);
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    /** Signaling thô — chỉ để lấy giá trị nhạy cảm gốc cho phép đo Security Leakage. */
    private List<RawSignalingRecord> rawSignaling(String callId) {
        try {
            return signaling.fetchByCallId(callId).records();
        } catch (RuntimeException e) {
            return List.of();                                   // đã cảnh báo ở checkSignaling
        }
    }

    /**
     * Kiểm signaling TRƯỚC khi chạy. Pipeline không sập khi ES tắt — nó ghi "Giới hạn dữ liệu" và kết luận
     * từ log đính kèm — nên thiếu bước này thì cả lượt ra UNKNOWN mà bảng điểm không cho biết vì sao.
     * Chỉ cảnh báo, không dừng: có thể tập case cố ý không có signaling.
     */
    private String checkSignaling(List<BenchmarkCase> cases) {
        List<String> callIds = cases.stream().map(BenchmarkCase::callId).distinct().toList();
        int found = 0;
        for (String callId : callIds) {
            try {
                if (!signaling.fetchByCallId(callId).isEmpty()) {
                    found++;
                }
            } catch (RuntimeException e) {
                String status = "KHÔNG truy vấn được (" + e.getClass().getSimpleName()
                        + ") — mọi kết luận chỉ dựa trên log đính kèm; bật Elasticsearch và nạp signaling rồi chạy lại";
                log.warn("Signaling: {}", status);
                return status;
            }
        }
        String status = "có cho " + found + "/" + callIds.size() + " Call-ID";
        if (found < callIds.size()) {
            log.warn("Signaling: {} — Call-ID còn lại chưa được nạp vào Elasticsearch (--import-signaling)", status);
            return status + " (Call-ID còn lại chưa nạp vào Elasticsearch)";
        }
        log.info("Signaling: {}", status);
        return status;
    }
}
