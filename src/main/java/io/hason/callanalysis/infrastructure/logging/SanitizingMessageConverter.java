package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.hason.callanalysis.infrastructure.security.SanitizerHolder;

/**
 * {@code %smsg} trong pattern log: nội dung log đi qua sanitizer trước khi ghi ra (MVP mục 6.2:
 * "application log không chứa SENSITIVE / SECRET dạng gốc", ca kiểm thử S08).
 *
 * Làm ở tầng appender chứ không dặn từng chỗ gọi log: chỉ cần một chỗ quên là lộ.
 */
public class SanitizingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SanitizerHolder.get().sanitize(event.getFormattedMessage()).text();
    }
}
