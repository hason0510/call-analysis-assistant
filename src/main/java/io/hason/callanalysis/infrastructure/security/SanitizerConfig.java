package io.hason.callanalysis.infrastructure.security;

import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SanitizerConfig {

    private static final Logger log = LoggerFactory.getLogger(SanitizerConfig.class);

    @Bean
    public SensitiveDataSanitizer sensitiveDataSanitizer() {
        SensitiveDataSanitizer sanitizer = SanitizerHolder.get();
        if (SanitizerHolder.usingRandomKey()) {
            log.warn("Chưa đặt {}: mã pseudonymize ổn định trong lần chạy này nhưng đổi giữa các lần chạy.",
                    SanitizerHolder.KEY_ENV);
        }
        return sanitizer;
    }
}
