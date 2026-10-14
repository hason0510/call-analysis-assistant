package io.hason.callanalysis.domain.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sensitive Data Detector + Policy Engine (MVP mục 6.1 T7, 6.2): dò dữ liệu nhạy cảm theo
 * Sensitive Data Inventory rồi DROP / MASK / PSEUDONYMIZE / MINIMIZE.
 *
 * Mọi quy tắc đến từ inventory, không viết cứng ở đây; thứ tự các mục trong inventory là thứ tự
 * áp dụng. Mỗi mục được áp theo hai cách:
 * - pattern: dò trong văn bản tự do (IP trong dòng log libwebrtc, JWT trong exception…);
 * - tên trường: dạng {@code tên=giá trị}, {@code tên: giá trị}, {@code "tên":"giá trị"} — chỉ với mục
 *   KHÔNG có pattern hoặc mục DROP, vì mục có pattern đã tự định vị giá trị chính xác hơn.
 *
 * Định danh nhận được ở chỗ có cấu trúc được NHỚ trong {@link Session} rồi thay ở mọi nơi: data
 * mẫu có appUserId nằm trong văn bản tự do của WebRTC log ({@code stream_ids:stream_<appUserId>}),
 * nơi không có tên trường nào để khớp.
 *
 * Hai chế độ cho bí mật dạng {@code tên=giá trị} (mục DROP):
 * - {@link #sanitize} — log, exception, câu trả lời AI: che TỚI HẾT DÒNG. Dừng ở khoảng trắng thì
 *   {@code Authorization: Basic dXNlcjpwYXNz} chỉ che chữ "Basic", lộ phần base64.
 * - {@link #sanitizeQuestion} — chữ người dùng gõ (câu hỏi, trọng tâm tách từ câu hỏi): che đúng MỘT từ,
 *   hoặc scheme xác thực + một từ ({@code Basic xxx}, {@code Bearer xxx}). Che tới hết dòng thì
 *   {@code "api_key=sk-… phan tich cuoc goi nay giup minh"} mất luôn phần yêu cầu, AI không còn gì để
 *   hiểu và người dùng nhận câu từ chối (case-019, 2026-10-10). Đổi lại: bí mật có khoảng trắng
 *   ({@code password=my secret pass}) gõ thẳng vào câu hỏi thì lộ phần sau từ đầu tiên — chấp nhận
 *   vì bí mật thật nằm ở log, còn câu hỏi do người dùng tự gõ (quyết định 2026-10-10, phương án C).
 *
 * Thuần domain: không đọc file, không gọi mạng.
 */
public class SensitiveDataSanitizer {

    /** MVP mục 6.2: {@code Authorization: Bearer eyJ… → Authorization: [REDACTED]}. */
    public static final String DROPPED = "[REDACTED]";

    private static final int PSEUDONYM_HEX = 6;
    private static final int MINIMIZED_HEX = 4;
    /** Giá trị ngắn hơn thì không thay ở mọi nơi: dễ trùng với chữ thường trong log. */
    private static final int MIN_KNOWN_VALUE_LENGTH = 6;
    /** Đầu ra của chính sanitizer — gặp lại thì không xử lý lần nữa (chạy hai lần cho cùng kết quả). */
    private static final Pattern OWN_OUTPUT = Pattern.compile("\\[[A-Z_]+]|[A-Z]+_[0-9a-f]{4,6}");

    /** Giá trị một từ: dừng ở khoảng trắng và dấu phân cách. */
    private static final String ONE_WORD = "[^\\s,;\"\\\\}\\])]+";
    /** Bí mật trong log: tới hết dòng / dấu nháy đóng, KHÔNG dừng ở khoảng trắng. */
    private static final String TO_END_OF_LINE = "[^\\r\\n,;\"\\\\}\\]]+";
    /** Bí mật trong câu hỏi: một từ, hoặc scheme xác thực + một từ — "Basic xxx" vẫn bị che trọn. */
    private static final String SCHEME_AND_WORD = "(?:(?i:basic|bearer|digest|negotiate|token)\\s+)?" + ONE_WORD;

    private final List<SensitiveField> fields;
    private final Pseudonymizer pseudonymizer;
    private final Map<String, Pattern> keyValuePatterns = new LinkedHashMap<>();
    /** Như {@link #keyValuePatterns}, riêng mục DROP dừng ở một từ — cho chữ người dùng gõ. */
    private final Map<String, Pattern> questionKeyValuePatterns = new LinkedHashMap<>();
    /**
     * Tên trường (chữ thường) → mục ĐẦU TIÊN theo thứ tự inventory có tên đó — kết quả như duyệt
     * {@link #fields}. {@link #policyForKey} chạy cho MỌI thuộc tính của mọi event khi làm sạch report:
     * duyệt cả inventory mỗi lần làm request 4 file của DE7DD314 mất ~7 s (đo 2026-10-11, thread dump).
     */
    private final Map<String, SensitiveField> fieldByName = new HashMap<>();
    /** Bảng tra chỉ tương đương equalsIgnoreCase khi mọi tên trong inventory là ASCII. */
    private final boolean asciiFieldNames;

    public SensitiveDataSanitizer(List<SensitiveField> inventory, Pseudonymizer pseudonymizer) {
        this.fields = List.copyOf(inventory);
        this.pseudonymizer = pseudonymizer;
        this.asciiFieldNames = fields.stream().flatMap(f -> f.fieldNames().stream())
                .allMatch(SensitiveDataSanitizer::isAscii);
        for (SensitiveField f : fields) {
            f.fieldNames().forEach(n -> fieldByName.putIfAbsent(n.toLowerCase(Locale.ROOT), f));
            if (appliesByFieldName(f)) {
                boolean drop = f.policy() == HandlingPolicy.DROP;
                keyValuePatterns.put(f.id(), keyValuePattern(f.fieldNames(), drop ? TO_END_OF_LINE : ONE_WORD));
                questionKeyValuePatterns.put(f.id(), keyValuePattern(f.fieldNames(), drop ? SCHEME_AND_WORD : ONE_WORD));
            }
        }
    }

    /** Kết quả làm sạch: văn bản mới và số chỗ đã thay theo từng mục inventory. */
    public record Result(String text, Map<String, Integer> findings) {

        public Result {
            findings = Map.copyOf(findings);
        }

        public int total() {
            return findings.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Một lần làm sạch nhiều văn bản của CÙNG một cuộc gọi, dùng chung bộ nhớ định danh. */
    public Session newSession() {
        return new Session();
    }

    /** Làm sạch một văn bản đứng riêng (log ứng dụng, câu trả lời AI). */
    public Result sanitize(String text) {
        return newSession().sanitize(text);
    }

    /** Làm sạch chữ người dùng gõ đứng riêng — xem chế độ câu hỏi ở đầu lớp. */
    public Result sanitizeQuestion(String text) {
        return newSession().sanitizeQuestion(text);
    }

    /** Mục inventory áp cho một tên trường (cột TSV, khoá JSON), theo thứ tự inventory. */
    public Optional<SensitiveField> policyForKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        if (asciiFieldNames && isAscii(key)) {
            return Optional.ofNullable(fieldByName.get(key.toLowerCase(Locale.ROOT)));
        }
        // Có ký tự ngoài ASCII: equalsIgnoreCase coi "ı" (i không chấm) bằng "i", còn chữ thường của nó
        // thì không — duyệt như cũ để không bỏ sót một trường cần che.
        return fields.stream().filter(x -> x.matchesFieldName(key)).findFirst();
    }

    private static boolean isAscii(String s) {
        return s.chars().allMatch(c -> c < 0x80);
    }

    /**
     * Call-ID cho log (MVP mục 6.2: "{@code call_id} (pseudonymized nếu cần)"). Mã giả HMAC: cùng Call-ID cùng mã,
     * nên vẫn nối được các dòng log của một cuộc gọi mà không ghi Call-ID gốc.
     */
    public String pseudonymizeCallId(String callId) {
        return callId == null || callId.isBlank() ? null : pseudonymizer.pseudonym("CALL", callId, PSEUDONYM_HEX);
    }

    /** Giá trị thay thế cho {@code value} theo policy của mục; ALLOW trả nguyên giá trị. */
    public String replacement(SensitiveField field, String value) {
        return switch (field.policy()) {
            case ALLOW -> value;
            case DROP -> DROPPED;
            case MASK -> "[" + field.label() + "_REDACTED]";
            case PSEUDONYMIZE -> pseudonymizer.pseudonym(field.label(), value, PSEUDONYM_HEX);
            case MINIMIZE -> pseudonymizer.pseudonym(field.label(), value, MINIMIZED_HEX);
        };
    }

    public final class Session {

        /** giá trị gốc → (mục inventory, giá trị thay thế), giữ thứ tự gặp để kết quả tất định. */
        private final Map<String, Known> known = new LinkedHashMap<>();

        private record Known(SensitiveField field, String replacement) {}

        private Session() {
        }

        /**
         * Ghi nhận giá trị của một trường có cấu trúc (cột TSV, khoá JSON, thuộc tính event) để
         * thay nó ở MỌI văn bản sau đó của phiên. Chỉ nhớ định danh (PSEUDONYMIZE / MINIMIZE).
         */
        public void remember(String key, String value) {
            policyForKey(key).ifPresent(f -> rememberValue(f, value));
        }

        /** Đọc trước một văn bản chỉ để nhớ định danh dạng {@code tên=giá trị}, không thay gì. */
        public void collect(String text) {
            if (text == null) {
                return;
            }
            for (SensitiveField f : fields) {
                Pattern kv = keyValuePatterns.get(f.id());
                if (kv == null || !isIdentifier(f)) {
                    continue;
                }
                Matcher m = kv.matcher(text);
                while (m.find()) {
                    rememberValue(f, m.group("value"));
                }
            }
        }

        public Result sanitize(String text) {
            return sanitize(text, keyValuePatterns);
        }

        /** Chữ người dùng gõ: bí mật {@code tên=giá trị} chỉ che đúng giá trị — xem đầu lớp. */
        public Result sanitizeQuestion(String text) {
            return sanitize(text, questionKeyValuePatterns);
        }

        private Result sanitize(String text, Map<String, Pattern> kvPatterns) {
            if (text == null || text.isEmpty()) {
                return new Result(text, Map.of());
            }
            Map<String, Integer> findings = new LinkedHashMap<>();
            String out = text;
            for (SensitiveField f : fields) {
                if (f.policy() == HandlingPolicy.ALLOW) {
                    continue;
                }
                if (f.valuePattern() != null) {
                    out = replaceByPattern(f, out, findings);
                }
                Pattern kv = kvPatterns.get(f.id());
                if (kv != null) {
                    out = replaceKeyValues(f, kv, out, findings);
                }
            }
            out = replaceKnownValues(out, findings);
            return new Result(out, findings);
        }

        /** Giá trị thay thế cho một trường có cấu trúc, đồng thời nhớ nó nếu là định danh. */
        public String sanitizeValue(SensitiveField field, String value) {
            if (field.policy() == HandlingPolicy.ALLOW || value == null || isOwnOutput(value)) {
                return value;
            }
            rememberValue(field, value);
            return replacement(field, value);
        }

        private void rememberValue(SensitiveField f, String value) {
            if (!isIdentifier(f) || value == null || value.isBlank() || isOwnOutput(value)) {
                return;
            }
            String v = value.strip();
            known.computeIfAbsent(v, x -> new Known(f, replacement(f, x)));
        }

        private String replaceByPattern(SensitiveField f, String text, Map<String, Integer> findings) {
            List<String> groups = f.valueGroups();
            Matcher m = f.valuePattern().matcher(text);
            StringBuilder sb = new StringBuilder(text.length());
            int last = 0;
            int count = 0;
            while (m.find()) {
                // Không có nhóm `value…` thì thay cả chuỗi khớp; có thì thay MỌI nhóm khớp được —
                // một dòng Cand[] của libwebrtc mang cả ufrag lẫn mật khẩu ICE.
                List<int[]> spans = new ArrayList<>();
                for (String g : groups) {
                    if (m.start(g) >= 0 && m.end(g) > m.start(g)) {
                        spans.add(new int[]{m.start(g), m.end(g)});
                    }
                }
                if (groups.isEmpty()) {
                    spans.add(new int[]{m.start(), m.end()});
                }
                spans.sort(Comparator.comparingInt(s -> s[0]));
                for (int[] span : spans) {
                    String value = text.substring(span[0], span[1]);
                    if (span[0] < last || isOwnOutput(value)) {
                        continue;
                    }
                    sb.append(text, last, span[0]).append(replacement(f, value));
                    last = span[1];
                    count++;
                }
            }
            if (count == 0) {
                return text;
            }
            findings.merge(f.id(), count, Integer::sum);
            return sb.append(text, last, text.length()).toString();
        }

        private String replaceKeyValues(SensitiveField f, Pattern kv, String text, Map<String, Integer> findings) {
            Matcher m = kv.matcher(text);
            StringBuilder sb = new StringBuilder(text.length());
            int last = 0;
            int count = 0;
            while (m.find()) {
                String value = m.group("value");
                if (isOwnOutput(value)) {
                    continue;
                }
                rememberValue(f, value);
                sb.append(text, last, m.start("value")).append(replacement(f, value));
                last = m.end("value");
                count++;
            }
            if (count == 0) {
                return text;
            }
            findings.merge(f.id(), count, Integer::sum);
            return sb.append(text, last, text.length()).toString();
        }

        /** Thay mọi lần xuất hiện của định danh đã nhớ — giá trị dài trước để không thay dở chừng. */
        private String replaceKnownValues(String text, Map<String, Integer> findings) {
            List<Map.Entry<String, Known>> byLength = known.entrySet().stream()
                    .filter(e -> e.getKey().length() >= MIN_KNOWN_VALUE_LENGTH)
                    .sorted(Comparator.comparingInt((Map.Entry<String, Known> e) -> e.getKey().length()).reversed())
                    .toList();
            String out = text;
            for (Map.Entry<String, Known> e : byLength) {
                // Ranh giới là chữ/số, KHÔNG phải \b: "stream_UZFSCHDZTWR" có '_' đứng trước.
                Matcher m = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(e.getKey()) + "(?![A-Za-z0-9])")
                        .matcher(out);
                int count = 0;
                StringBuilder sb = new StringBuilder(out.length());
                while (m.find()) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(e.getValue().replacement()));
                    count++;
                }
                if (count > 0) {
                    m.appendTail(sb);
                    out = sb.toString();
                    findings.merge(e.getValue().field().id(), count, Integer::sum);
                }
            }
            return out;
        }
    }

    private static boolean isIdentifier(SensitiveField f) {
        return f.policy() == HandlingPolicy.PSEUDONYMIZE || f.policy() == HandlingPolicy.MINIMIZE;
    }

    private static boolean isOwnOutput(String value) {
        return OWN_OUTPUT.matcher(value).matches();
    }

    /** Mục không có pattern thì chỉ còn tên trường để dò; mục DROP thì dò cả hai cho chắc. */
    private static boolean appliesByFieldName(SensitiveField f) {
        return f.policy() != HandlingPolicy.ALLOW && !f.fieldNames().isEmpty()
                && (f.valuePattern() == null || f.policy() == HandlingPolicy.DROP);
    }

    /**
     * {@code tên=giá trị}, {@code tên: giá trị}, {@code "tên":"giá trị"}, {@code \"tên\":\"giá trị\"}.
     * Tên không được dính chữ phía trước ("ice-pwd" không khớp tên "pwd"). Giá trị không bắt đầu
     * bằng '[' — đó là nhãn sanitizer đã đặt.
     */
    private static Pattern keyValuePattern(List<String> names, String value) {
        String alternation = names.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\w.-])(?i:" + alternation + ")(?:\\\\?\")?\\s*[=:]\\s*(?:\\\\?\")?"
                // (?![\[\s]): không bắt đầu bằng nhãn đã đặt, và không bắt đầu bằng khoảng trắng —
                // nếu không, \s* lùi về rỗng và "Authorization: [REDACTED]" bị che lần nữa thành "[REDACTED]]".
                + "(?<value>(?![\\[\\s])" + value + ")");
    }

    /** Danh sách mục theo thứ tự áp dụng — để báo cáo / kiểm. */
    public List<SensitiveField> fields() {
        return new ArrayList<>(fields);
    }
}
