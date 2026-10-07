package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.hason.callanalysis.infrastructure.security.SanitizerHolder;

/**
 * {@code %sex} trong pattern log: stack trace cũng đi qua sanitizer. Thông điệp exception là nơi
 * hay lộ bí mật nhất ("401 Unauthorized for Bearer eyJ…", URL kèm api_key) — ca S03 / S08.
 */
public class SanitizingThrowableConverter extends ExtendedThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        String trace = super.convert(event);
        return trace.isEmpty() ? trace : SanitizerHolder.get().sanitize(trace).text();
    }
}
