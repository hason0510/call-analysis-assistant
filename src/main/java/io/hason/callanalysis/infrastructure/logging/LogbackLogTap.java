package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import io.hason.callanalysis.service.port.ApplicationLogTap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thu application log bằng chính ENCODER của appender console: mỗi sự kiện log được mã hoá đúng như khi ghi ra
 * console — cùng pattern, cùng converter {@code %smsg} / {@code %sex} làm sạch (logback-spring.xml). Nhờ vậy thứ
 * runner quét là thứ thật sự nằm trong log, không phải thông điệp trước khi làm sạch.
 *
 * Không tìm thấy appender console (ví dụ unit test không chạy cấu hình Spring) thì {@link #stop()} trả null — phép
 * đo ghi "chưa đo", không ghi 0.
 */
@Component
public class LogbackLogTap implements ApplicationLogTap {

    static final String CONSOLE = "CONSOLE";
    private static final String TAP_NAME = "evaluation-log-tap";

    private final LoggerContext context;
    private final String sourceAppender;
    private Capture capture;

    public LogbackLogTap() {
        this((LoggerContext) LoggerFactory.getILoggerFactory(), CONSOLE);
    }

    LogbackLogTap(LoggerContext context, String sourceAppender) {
        this.context = context;
        this.sourceAppender = sourceAppender;
    }

    @Override
    public synchronized void start() {
        stop();
        Encoder<ILoggingEvent> encoder = consoleEncoder();
        if (encoder == null) {
            return;
        }
        capture = new Capture(encoder);
        capture.setContext(context);
        capture.setName(TAP_NAME);
        capture.start();
        root().addAppender(capture);
    }

    @Override
    public synchronized String stop() {
        if (capture == null) {
            return null;
        }
        root().detachAppender(capture);
        capture.stop();
        String text = capture.text();
        capture = null;
        return text;
    }

    private Encoder<ILoggingEvent> consoleEncoder() {
        Appender<ILoggingEvent> appender = root().getAppender(sourceAppender);
        if (appender instanceof OutputStreamAppender<ILoggingEvent> stream && stream.getEncoder() != null) {
            return stream.getEncoder();
        }
        return null;
    }

    private ch.qos.logback.classic.Logger root() {
        return context.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    /** Appender chỉ ghi vào bộ nhớ, bằng encoder của console. */
    private static final class Capture extends AppenderBase<ILoggingEvent> {

        private final Encoder<ILoggingEvent> encoder;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Capture(Encoder<ILoggingEvent> encoder) {
            this.encoder = encoder;
        }

        @Override
        protected void append(ILoggingEvent event) {
            byte[] bytes = encoder.encode(event);
            synchronized (buffer) {
                buffer.writeBytes(bytes);
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
