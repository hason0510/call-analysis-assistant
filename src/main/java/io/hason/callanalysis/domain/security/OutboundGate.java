package io.hason.callanalysis.domain.security;

import io.hason.callanalysis.domain.evaluation.SensitiveValues;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Cổng fail-closed ngay trước khi gửi AI (góp ý mentor Sprint 2): quét ĐÚNG chuỗi sắp rời máy, còn dấu hiệu dữ liệu nhạy
 * cảm thì không gửi — bên gọi lùi về rule ({@code SENSITIVE_DATA_BLOCKED}).
 *
 * Sanitizer là lớp chính; cổng này là lớp thứ hai, cho trường hợp Sanitizer bỏ sót một dạng. Vì vậy nó cố ý KHÔNG dùng
 * regex của Sanitizer mà dùng bộ nhận diện độc lập của phép đo Security Leakage ({@link SensitiveValues#inFreeText}):
 * IPv4, IPv6 unicast toàn cầu, JWT, Bearer, API key {@code sk-}, cặp {@code token=…}, số điện thoại di động Việt Nam,
 * email. Giá trị đã được Sanitizer thay ({@code [IP]}, {@code [REDACTED]}, {@code USER_a81f2c}) không tính.
 *
 * Thuần domain: chỉ quyết định, không gọi mạng — adapter AI gọi trước mỗi lời gọi. Chỉ trả TÊN LOẠI, không trả giá trị,
 * để thông điệp lỗi và log không chép lại chính thứ vừa chặn.
 */
public final class OutboundGate {

    /** Mã giả của Sanitizer (MINIMIZE / PSEUDONYMIZE): tiền tố chữ hoa + 4-6 ký tự hex. */
    private static final Pattern PSEUDONYM = Pattern.compile("[A-Z]+_[0-9a-f]{4,6}");

    private OutboundGate() {
    }

    /** @return loại dữ liệu nhạy cảm còn trong chuỗi, theo thứ tự tên; rỗng khi gửi được */
    public static Set<String> kindsFound(String outbound) {
        Set<String> kinds = new TreeSet<>();
        SensitiveValues.inFreeText(outbound).forEach((kind, values) -> {
            if (values.stream().anyMatch(v -> !PSEUDONYM.matcher(v).matches())) {
                kinds.add(kind);
            }
        });
        return kinds;
    }
}
