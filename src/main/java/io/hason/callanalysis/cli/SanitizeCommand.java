package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Làm sạch TOÀN BỘ log thô của mọi cuộc gọi dưới một thư mục, ghi bản sạch ra thư mục khác.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--sanitize-dir=../ai20k_sample/success --out=<thư-mục-ra>"
 *
 * Dùng để KIỂM CHỨNG Sanitizer (T7) bằng công cụ độc lập: quét thư mục ra bằng script không dùng
 * chung regex với sanitizer, đếm xem còn sót IP, bí mật, định danh nào. Đây là phép thử khắt khe
 * hơn AI context thật — AI chỉ nhận phần đã chuẩn hoá, ở đây là cả log gốc.
 *
 * Mỗi thư mục cuộc gọi dùng MỘT phiên: lượt đầu nhớ định danh ở mọi file (cột TSV của end call log,
 * khoá JSON của signaling, dạng tên=giá trị), lượt sau mới thay — nên appUserId biết được từ
 * signaling vẫn bị thay trong văn bản tự do của WebRTC log.
 */
@Component
public class SanitizeCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SanitizeCommand.class);
    private static final String OPTION = "sanitize-dir";
    private static final String OUT = "out";
    private static final String HEADER_PREFIX = "#H";

    private final SensitiveDataSanitizer sanitizer;
    private final ConfigurableApplicationContext context;

    public SanitizeCommand(SensitiveDataSanitizer sanitizer, ConfigurableApplicationContext context) {
        this.sanitizer = sanitizer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        int exit = execute(args);
        System.exit(SpringApplication.exit(context, () -> exit));
    }

    private int execute(ApplicationArguments args) {
        if (!args.containsOption(OUT)) {
            log.error("Thiếu --out=<thư mục ghi bản đã làm sạch>");
            return 2;
        }
        Path root = Path.of(args.getOptionValues(OPTION).getFirst()).toAbsolutePath().normalize();
        Path out = Path.of(args.getOptionValues(OUT).getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.error("Không phải thư mục: {}", root);
            return 2;
        }
        if (out.startsWith(root)) {
            log.error("--out không được nằm trong thư mục nguồn");
            return 2;
        }

        Map<String, Integer> total = new LinkedHashMap<>();
        int files = 0;
        for (Path call : callFolders(root)) {
            List<Path> logs = listFiles(call);
            Map<Path, String> texts = new LinkedHashMap<>();
            logs.forEach(p -> texts.put(p, read(p)));

            SensitiveDataSanitizer.Session session = sanitizer.newSession();
            texts.forEach((p, t) -> {
                rememberTsvColumns(t, session);
                session.collect(t);
            });
            for (Map.Entry<Path, String> e : texts.entrySet()) {
                SensitiveDataSanitizer.Result r = session.sanitize(e.getValue());
                r.findings().forEach((k, v) -> total.merge(k, v, Integer::sum));
                write(out.resolve(root.relativize(e.getKey())), r.text());
                files++;
            }
        }

        log.info("Đã làm sạch {} file dưới {}", files, root);
        log.info("Ghi ra: {}", out);
        log.info("Số chỗ đã thay theo mục inventory:");
        sanitizer.fields().forEach(f -> {
            Integer n = total.get(f.id());
            if (n != null) {
                log.info("  {}  {}  ({})", String.format("%-24s", f.id()), String.format("%7d", n), f.policy());
            }
        });
        return 0;
    }

    /**
     * End call log là TSV: dòng {@code #Hn} khai tên cột, dòng dữ liệu mở đầu bằng {@code n}.
     * Nhớ giá trị của các cột là định danh (appUserId, callUserId, sessionId, fromTag…).
     */
    private static void rememberTsvColumns(String text, SensitiveDataSanitizer.Session session) {
        if (!text.startsWith(HEADER_PREFIX)) {
            return;
        }
        Map<String, String[]> headers = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] cols = line.stripTrailing().split("\t", -1);
            if (cols[0].startsWith(HEADER_PREFIX)) {
                headers.put(cols[0].substring(HEADER_PREFIX.length()), cols);
                continue;
            }
            String[] header = headers.get(cols[0]);
            if (header == null) {
                continue;
            }
            for (int i = 1; i < Math.min(header.length, cols.length); i++) {
                session.remember(header[i], cols[i]);
            }
        }
    }

    private static List<Path> callFolders(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> result = new ArrayList<>();
            walk.filter(Files::isDirectory)
                    .filter(d -> !listFiles(d).isEmpty())
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(result::add);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> listFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path p, String text) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
