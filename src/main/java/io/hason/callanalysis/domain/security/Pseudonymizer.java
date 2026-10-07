package io.hason.callanalysis.domain.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Thay định danh bằng mã ổn định: cùng giá trị + cùng khoá → cùng mã (MVP mục 6.2:
 * {@code user_id=123456 → user_id=USER_a81f2c}).
 *
 * HMAC chứ không phải hash trần: không gian appUserId nhỏ (11 ký tự), hash trần thì ai cũng tính
 * ngược được bằng cách thử hết. Có khoá thì phải có khoá mới dò được.
 */
public final class Pseudonymizer {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 16;

    private final byte[] key;

    public Pseudonymizer(byte[] key) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("Khoá pseudonymize phải dài ít nhất " + MIN_KEY_BYTES + " byte");
        }
        this.key = key.clone();
    }

    /** Khoá ngẫu nhiên: mã ổn định trong một lần chạy, khác giữa các lần chạy. */
    public static Pseudonymizer withRandomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new Pseudonymizer(key);
    }

    /** {@code LABEL_} + {@code hexChars} ký tự hex đầu của HMAC(label, value). */
    public String pseudonym(String label, String value, int hexChars) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // Gộp nhãn vào đầu vào: cùng một chuỗi là userId và là sessionId thì ra hai mã khác nhau.
            byte[] digest = mac.doFinal((label + '\u0000' + value).getBytes(StandardCharsets.UTF_8));
            return label + "_" + HexFormat.of().formatHex(digest).substring(0, hexChars);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không hỗ trợ " + ALGORITHM, e);
        }
    }
}
