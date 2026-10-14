package io.hason.callanalysis.infrastructure.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.hason.callanalysis.infrastructure.security.SanitizerHolder;
import org.slf4j.event.KeyValuePair;

import java.util.List;

/**
 * {@code %skvp} trong pattern log chữ (CLI): các trường key-value của SLF4J in thành {@code " khoá=giá trị"} sau
 * thông điệp, giá trị đi qua sanitizer như {@code %smsg}. Trường null bỏ qua.
 *
 * Log JSON (profile web) không dùng converter này: {@link SanitizedJsonLogFormatter} ghi mỗi trường một khoá.
 */
public class SanitizingKeyValueConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null || pairs.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (KeyValuePair p : pairs) {
            if (p.value == null) {
                continue;
            }
            out.append(' ').append(p.key).append('=')
                    .append(SanitizerHolder.get().sanitize(String.valueOf(p.value)).text());
        }
        return out.toString();
    }
}
