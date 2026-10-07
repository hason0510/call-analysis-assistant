package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.service.RequestParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Chạy Request Parser (T4) trên một câu hỏi. Không có OPENAI_API_KEY thì đi đường lui từ khoá —
 * dòng "Nguồn" cho biết kết quả đến từ đâu.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="'--parse-request=Vi sao ben nhan khong nghe duoc?'"
 *
 * Câu có khoảng trắng phải nằm trong nháy đơn, nếu không Maven tách thành nhiều tham số. Trên Windows,
 * chữ có dấu ngoài bảng mã ANSI bị thành "?" trước khi tới JVM — đường nhập thật là Web UI (T1).
 */
@Component
public class ParseRequestCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ParseRequestCommand.class);
    private static final String OPTION = "parse-request";

    private final RequestParserService parser;
    private final ConfigurableApplicationContext context;

    public ParseRequestCommand(RequestParserService parser, ConfigurableApplicationContext context) {
        this.parser = parser;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        ParsedRequest r = parser.parse("cli", String.join(" ", args.getOptionValues(OPTION)));
        log.info("Câu hỏi (đã làm sạch): {}", r.question());
        log.info("Intent   : {}", r.intent());
        log.info("Trọng tâm: {}", r.focus() == null ? "-" : r.focus());
        log.info("Call-ID  : {}", r.callId() == null ? "-" : r.callId());
        log.info("Nguồn    : {}{}", r.source(), r.fallbackReason() == null ? "" : " (fallback: " + r.fallbackReason() + ")");
        r.notes().forEach(n -> log.info("Ghi chú  : {}", n));
        if (r.isOutOfScope()) {
            log.info("Trả lời  : {}", ParsedRequest.OUT_OF_SCOPE_REPLY);
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
