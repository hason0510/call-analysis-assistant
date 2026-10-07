package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Ca kiểm thử S08 của MVP mục 6.4: dữ liệu nhạy cảm trong log ứng dụng và trong error message. */
class SanitizingConvertersTest {

    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("test");

    private LoggingEvent event(String message, Throwable error, Object... args) {
        return new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, message, error, args);
    }

    @Test
    @DisplayName("S08 — nội dung log (kể cả tham số {}) không còn bí mật / IP / số điện thoại dạng gốc")
    void s08LogMessageIsSanitized() {
        SanitizingMessageConverter converter = new SanitizingMessageConverter();

        String out = converter.convert(event("Gọi provider lỗi, header {}, client {}, hotline {}", null,
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln", "203.0.113.7", "0912345678"));

        assertThat(out).startsWith("Gọi provider lỗi, header Authorization: [REDACTED]")
                .doesNotContain("eyJ").doesNotContain("203.0.113.7").doesNotContain("0912345678");
    }

    @Test
    @DisplayName("S08 — error message trong stack trace cũng được làm sạch, stack trace vẫn còn")
    void s08ExceptionMessageIsSanitized() {
        SanitizingThrowableConverter converter = new SanitizingThrowableConverter();
        converter.setContext(context);
        converter.start();

        String out = converter.convert(event("lỗi", new IllegalStateException(
                "401 from https://internal.example.net/v1?api_key=Zx81Qp0LmN3 for user deviceId=DHV3MGQG53I")));

        assertThat(out).contains("IllegalStateException: 401 from")
                .contains("at io.hason.callanalysis")                 // stack trace không bị cắt
                .doesNotContain("Zx81Qp0LmN3").doesNotContain("internal.example.net").doesNotContain("DHV3MGQG53I");
    }
}
