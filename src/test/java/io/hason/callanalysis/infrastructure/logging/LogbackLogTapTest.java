package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LogbackLogTapTest {

    private final LoggerContext context = new LoggerContext();
    private final Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    /** Appender "console" giả: ghi vào bộ nhớ với pattern riêng — tap phải dùng ĐÚNG pattern này. */
    private void console(String pattern) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(pattern);
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();
        OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setName(LogbackLogTap.CONSOLE);
        appender.setEncoder(encoder);
        appender.setOutputStream(new ByteArrayOutputStream());
        appender.start();
        root.addAppender(appender);
    }

    @Test
    @DisplayName("thu đúng phần log ghi ra giữa start và stop, bằng encoder của console")
    void capturesWithConsoleEncoder() {
        console("[%level] %msg%n");
        LogbackLogTap tap = new LogbackLogTap(context, LogbackLogTap.CONSOLE);

        root.info("trước khi thu");
        tap.start();
        context.getLogger("x").warn("lần chạy {}", "đang thu");
        String captured = tap.stop();
        root.info("sau khi thu");

        assertThat(captured).isEqualTo("[WARN] lần chạy đang thu" + System.lineSeparator());
    }

    @Test
    @DisplayName("stop gỡ appender thu khỏi root — không thu tiếp, không rò bộ nhớ qua các lần chạy")
    void detachesAfterStop() {
        console("%msg%n");
        LogbackLogTap tap = new LogbackLogTap(context, LogbackLogTap.CONSOLE);

        tap.start();
        tap.stop();

        assertThat(root.iteratorForAppenders()).toIterable()
                .extracting(a -> a.getName()).containsExactly(LogbackLogTap.CONSOLE);
        assertThat(tap.stop()).isNull();                                   // stop lần hai: không có gì đang thu
    }

    @Test
    @DisplayName("không có appender console: không thu được → null (phép đo ghi 'chưa đo', không ghi 0)")
    void noConsoleMeansNotMeasured() {
        LogbackLogTap tap = new LogbackLogTap(context, LogbackLogTap.CONSOLE);

        tap.start();
        root.info("không ai thu");

        assertThat(tap.stop()).isNull();
    }
}
