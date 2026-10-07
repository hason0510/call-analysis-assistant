package io.hason.callanalysis.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.infrastructure.ai.OpenAiAnalyzer;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.AnalyzeCallService;
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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * In ĐÚNG phần dữ liệu cuộc gọi sẽ gửi cho AI — không gọi AI, không cần key. Để người duyệt (mentor)
 * thấy tận mắt cái gì rời khỏi máy trước khi cho phép gọi API bên ngoài.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai-context=../ai20k_sample/fail/2D9057AA-... '--question=Vi sao goi khong duoc?'"
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--ai-context-all=../ai20k_sample --out=<thư-mục-ra>"
 *
 * Bản -all ghi mỗi cuộc một file `<nhóm>/<Call-ID>/ai-context.txt` (đúng chuỗi gửi đi) để quét bằng
 * script kiểm chứng độc lập của Sanitizer, và in bảng kích thước context.
 */
@Component
public class AiContextCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiContextCommand.class);
    private static final String ONE = "ai-context";
    private static final String ALL = "ai-context-all";
    private static final String OUT = "out";
    private static final String QUESTION = "question";
    private static final String DEFAULT_QUESTION = "Phân tích giúp mình cuộc gọi này";

    private final CallFolderReader reader;
    private final AnalyzeCallService analyzeService;
    private final OpenAiAnalyzer openAi;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;

    public AiContextCommand(CallFolderReader reader, AnalyzeCallService analyzeService, OpenAiAnalyzer openAi,
                            ObjectMapper mapper, ConfigurableApplicationContext context) {
        this.reader = reader;
        this.analyzeService = analyzeService;
        this.openAi = openAi;
        this.mapper = mapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        String question = args.containsOption(QUESTION) ? args.getOptionValues(QUESTION).getFirst() : DEFAULT_QUESTION;
        if (args.containsOption(ONE)) {
            printOne(Path.of(args.getOptionValues(ONE).getFirst()).toAbsolutePath().normalize(), question);
            System.exit(SpringApplication.exit(context, () -> 0));
        }
        if (args.containsOption(ALL)) {
            if (!args.containsOption(OUT)) {
                log.error("Thiếu --out=<thư-mục-ra>");
                System.exit(SpringApplication.exit(context, () -> 2));
            }
            writeAll(Path.of(args.getOptionValues(ALL).getFirst()).toAbsolutePath().normalize(),
                    Path.of(args.getOptionValues(OUT).getFirst()).toAbsolutePath().normalize(), question);
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }

    private AiContext build(Path folder, String question) {
        String callId = folder.getFileName().toString();
        AnalyzeCallService.Analysis analysis = analyzeService.analyze(callId, reader.readAll(folder));
        return analyzeService.aiContext(analysis, "cli-" + callId.substring(0, Math.min(8, callId.length())),
                question, null);
    }

    private void printOne(Path folder, String question) throws IOException {
        AiContext ai = build(folder, question);
        log.info("Câu hỏi (đã làm sạch): {}", ai.question());
        log.info("Context gửi AI:\n{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ai.payload()));
        log.info("Độ dài phần dữ liệu gửi đi (JSON gọn): {} ký tự", openAi.userMessage(ai).length());
        log.info("Đã thay theo inventory: {}", ai.redactions().isEmpty() ? "không có gì" : ai.redactions());
    }

    private void writeAll(Path root, Path out, String question) throws IOException {
        log.info("{}", String.format("%-10s %-10s %4s %4s %4s %7s %7s  %s",
                "NHÓM", "CALL-ID", "EV", "CS", "GH", "KÝ TỰ", "BYTE", "ĐÃ THAY"));
        int total = 0;
        long chars = 0;
        int max = 0;
        for (Path folder : findCallFolders(root)) {
            AiContext ai = build(folder, question);
            String message = openAi.userMessage(ai);
            String group = folder.getParent().getFileName().toString();
            Path file = out.resolve(group).resolve(folder.getFileName()).resolve("ai-context.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, message, StandardCharsets.UTF_8);

            total++;
            chars += message.length();
            max = Math.max(max, message.length());
            log.info("{}", String.format("%-10s %-10s %4d %4d %4d %7d %7d  %s",
                    group, folder.getFileName().toString().substring(0, 8),
                    ai.payload().evidence().size(), ai.payload().metrics().size(),
                    ai.payload().dataLimitations().size(), message.length(),
                    message.getBytes(StandardCharsets.UTF_8).length, ai.redactions()));
        }
        log.info("{} cuộc gọi, trung bình {} ký tự, lớn nhất {} ký tự. Đã ghi vào {}",
                total, total == 0 ? 0 : chars / total, max, out);
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
