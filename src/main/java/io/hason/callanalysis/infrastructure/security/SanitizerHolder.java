package io.hason.callanalysis.infrastructure.security;

import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MỘT sanitizer cho cả ứng dụng — dùng chung giữa Spring (AI, report) và Logback (log ứng dụng).
 *
 * Logback khởi tạo converter TRƯỚC khi Spring dựng context, nên không chờ được bean; hai bên lấy
 * cùng một instance từ đây, nhờ vậy cùng một appUserId ra cùng một mã ở log lẫn ở report.
 *
 * Khoá pseudonymize đọc từ biến môi trường {@value #KEY_ENV} (hoặc system property cùng tên).
 * Không đặt thì sinh khoá ngẫu nhiên: mã ổn định trong một lần chạy, đổi giữa các lần chạy.
 */
public final class SanitizerHolder {

    public static final String KEY_ENV = "CALL_ANALYSIS_PSEUDONYM_KEY";

    private static volatile SensitiveDataSanitizer instance;
    private static volatile boolean randomKey;

    private SanitizerHolder() {
    }

    public static SensitiveDataSanitizer get() {
        SensitiveDataSanitizer s = instance;
        if (s == null) {
            synchronized (SanitizerHolder.class) {
                s = instance;
                if (s == null) {
                    s = new SensitiveDataSanitizer(new SensitiveDataInventoryLoader().load(), pseudonymizer());
                    instance = s;
                }
            }
        }
        return s;
    }

    /** true khi không có khoá cấu hình — mã pseudonymize sẽ khác giữa các lần chạy. */
    public static boolean usingRandomKey() {
        get();
        return randomKey;
    }

    private static Pseudonymizer pseudonymizer() {
        String key = System.getProperty(KEY_ENV, System.getenv(KEY_ENV));
        if (key == null || key.isBlank()) {
            randomKey = true;
            return Pseudonymizer.withRandomKey();
        }
        try {
            // Băm khoá người dùng đặt thành 32 byte: chấp nhận mọi độ dài, độ mạnh do chính khoá quyết định.
            return new Pseudonymizer(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
