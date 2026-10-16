package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.validation.AttachedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giá trị nhạy cảm GỐC của một cuộc gọi — để đo Security Leakage (MVP mục 6.5) bằng cách tra xem giá trị gốc
 * nào còn xuất hiện nguyên văn trong chuỗi gửi AI hoặc trong phản hồi trả người dùng.
 *
 * Cố ý KHÔNG dùng regex của Sensitive Data Sanitizer: lấy giá trị bằng bộ đọc riêng (tên trường của signaling,
 * cột TSV của end call log, cặp tên=giá trị, SDP, Cand[]), theo đúng cách scripts/verify-sanitizer/verify_sanitized.py.
 * Sanitizer bỏ sót một dạng nào thì phép đo này vẫn bắt được — miễn bộ đọc này nhận ra giá trị gốc.
 *
 * Văn bản tự do (mọi dòng log đính kèm, và câu hỏi của người dùng qua {@link #withQuestion}) còn được quét tìm
 * IPv4, IPv6, bí mật (JWT, Bearer, API key, cặp {@code token=…}) và liên hệ (số điện thoại Việt Nam, email).
 * IPv6 được nhận bằng parser địa chỉ ({@link #isGlobalIpv6}), không bằng regex: log WebRTC đầy chuỗi trông như
 * IPv6 mà không phải ({@code Port[6449f700:0:1:0:host…}, {@code WRVMC::}, DTLS fingerprint).
 *
 * Giới hạn: IPv6 chỉ tính địa chỉ unicast toàn cầu (2000::/3) — địa chỉ client công cộng; link-local và địa chỉ
 * nội bộ không tính. Số điện thoại chỉ nhận dạng di động Việt Nam (0 / 84 / +84 rồi 9 chữ số, đầu 3, 5, 7, 8, 9).
 */
public final class SensitiveValues {

    /** Tên trường mang định danh → loại. Cùng bảng với verify_sanitized.py. */
    private static final Map<String, String> ID_FIELDS = Map.ofEntries(
            Map.entry("appUserId", "userId"), Map.entry("callUserId", "userId"),
            Map.entry("partnerAppUserId", "userId"), Map.entry("partnerCallUserId", "userId"),
            Map.entry("deviceId", "deviceId"), Map.entry("csid", "session"), Map.entry("sessionId", "session"),
            Map.entry("callSessionId", "session"), Map.entry("fromTag", "session"), Map.entry("toTag", "session"),
            Map.entry("service", "service"));

    private static final Pattern KEY_VALUE = Pattern.compile("\\b(deviceId|csid)=([A-Za-z0-9]+)");
    private static final Pattern ICE_PWD = Pattern.compile("a=ice-pwd:([A-Za-z0-9+/]+)");
    private static final Pattern USER_PASS = Pattern.compile("u/p=([A-Za-z0-9+/]+)");
    private static final Pattern CAND = Pattern.compile(
            "Cand\\[[^\\]]*:([A-Za-z0-9+/]{4,}):([A-Za-z0-9+/]{16,}):\\d+:\\d+:\\d+]");
    private static final Pattern FINGERPRINT = Pattern.compile("fingerprint:sha-256 ([0-9A-F:]+)");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![0-9.])");
    /**
     * Ứng viên IPv6: chuỗi hex và dấu hai chấm đứng riêng — trước / sau không phải chữ, số, ':' hay '.'. Chỉ để
     * KHOANH chuỗi; có phải địa chỉ hay không là việc của {@link #isGlobalIpv6}. Ranh giới chặt nên
     * {@code Port[6449f700:0:1:0:host} (dính chữ "host") và {@code rmnet0:2402:…} (dính tên giao diện) bị loại;
     * dạng thật trong data là cột TSV, SDP ({@code … 2402:… 51625 typ host}) và {@code Cand[…:[2402:…]:port…]}.
     */
    private static final Pattern IPV6_TOKEN = Pattern.compile("(?<![0-9A-Za-z:.])[0-9A-Fa-f:]{3,}(?![0-9A-Za-z:.])");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+([A-Za-z0-9._~+/=-]{8,})");
    private static final Pattern API_KEY = Pattern.compile("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{8,}");
    private static final Pattern SECRET_PAIR = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|password|passwd|secret)\\b\"?\\s*[=:]\\s*\"?([^\\s\",;}\\]]{8,})");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d.])(?:\\+84|84|0)[35789]\\d{8}(?![\\d.])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Ngắn hơn thế này thì dễ trùng ngẫu nhiên với chữ thường ("SVC1"), cùng ngưỡng với script. */
    private static final int MIN_LENGTH = 6;

    private final Map<String, Set<String>> byKind;

    private SensitiveValues(Map<String, Set<String>> byKind) {
        this.byKind = byKind;
    }

    public static SensitiveValues collect(List<AttachedFile> files, List<RawSignalingRecord> signaling) {
        Map<String, Set<String>> found = new TreeMap<>();
        for (RawSignalingRecord r : signaling == null ? List.<RawSignalingRecord>of() : signaling) {
            add(found, "userId", r.appUserId());
            add(found, "session", r.csid());
            add(found, "session", r.callSessionId());
            add(found, "service", r.service());
        }
        for (AttachedFile f : files == null ? List.<AttachedFile>of() : files) {
            if (f.loaded()) {
                collectFromLines(f.lines(), found);
            }
        }
        return new SensitiveValues(found);
    }

    private static void collectFromLines(List<String> lines, Map<String, Set<String>> found) {
        Map<String, String[]> headers = new TreeMap<>();
        for (String line : lines) {
            // End call log: dòng "#Hn" khai tên cột cho các dòng bắt đầu bằng "n"
            String[] cols = line.split("\t", -1);
            if (cols[0].startsWith("#H")) {
                headers.put(cols[0].substring(2), cols);
            } else if (headers.containsKey(cols[0])) {
                String[] names = headers.get(cols[0]);
                for (int i = 1; i < Math.min(names.length, cols.length); i++) {
                    String kind = ID_FIELDS.get(names[i]);
                    if (kind != null) {
                        add(found, kind, cols[i]);
                    }
                }
            }
            Matcher kv = KEY_VALUE.matcher(line);
            while (kv.find()) {
                add(found, ID_FIELDS.get(kv.group(1)), kv.group(2));
            }
            addAll(found, "ice-pwd", ICE_PWD, line, 1);
            addAll(found, "ice-pwd", USER_PASS, line, 1);
            addAll(found, "ice-pwd", CAND, line, 2);
            addAll(found, "fingerprint", FINGERPRINT, line, 1);
            collectFromFreeText(line, found);
        }
    }

    /**
     * Thêm giá trị nhạy cảm nằm trong câu hỏi của người dùng ("Máy 0912345678 gọi lỗi, token eyJ…"). Câu hỏi đi
     * tới AI (bước phân loại và bước phân tích) và có thể bị chép lại vào report — phải đo như log đính kèm.
     */
    public SensitiveValues withQuestion(String question) {
        Map<String, Set<String>> found = new TreeMap<>();
        byKind.forEach((kind, values) -> found.put(kind, new TreeSet<>(values)));
        if (question != null) {
            question.lines().forEach(line -> collectFromFreeText(line, found));
        }
        return new SensitiveValues(found);
    }

    /** IP, bí mật, liên hệ — thứ không có tên trường cố định, xuất hiện ở bất kỳ đâu trong văn bản. */
    private static void collectFromFreeText(String line, Map<String, Set<String>> found) {
        Matcher ip = IPV4.matcher(line);
        while (ip.find()) {
            if (isRealAddress(ip)) {
                add(found, "ip", ip.group());
            }
        }
        Matcher v6 = IPV6_TOKEN.matcher(line);
        while (v6.find()) {
            if (isGlobalIpv6(v6.group())) {
                add(found, "ip", v6.group());
            }
        }
        addAll(found, "secret", JWT, line, 0);
        addAll(found, "secret", BEARER, line, 1);
        addAll(found, "secret", API_KEY, line, 0);
        addAll(found, "secret", SECRET_PAIR, line, 1);
        addAll(found, "contact", PHONE, line, 0);
        addAll(found, "contact", EMAIL, line, 0);
    }

    /**
     * Địa chỉ IPv6 unicast toàn cầu (2000::/3) đúng cú pháp RFC 4291: 8 nhóm 1-4 chữ số hex, hoặc ít nhóm hơn với
     * đúng một "::". Dạng nhúng IPv4 ({@code ::ffff:192.0.2.4}) không tính — phần IPv4 đã có bộ đọc IPv4 bắt.
     */
    static boolean isGlobalIpv6(String s) {
        int gap = s.indexOf("::");
        List<String> groups = new ArrayList<>();
        if (gap < 0) {
            groups.addAll(List.of(s.split(":", -1)));
            if (groups.size() != 8) {
                return false;
            }
        } else {
            if (s.indexOf("::", gap + 1) >= 0 || gap == 0) {
                return false;                       // hai "::" hoặc ":::"; "::…" thì nhóm đầu là 0, ngoài 2000::/3
            }
            String right = s.substring(gap + 2);
            groups.addAll(List.of(s.substring(0, gap).split(":", -1)));
            if (!right.isEmpty()) {
                groups.addAll(List.of(right.split(":", -1)));
            }
            if (groups.size() > 7) {
                return false;
            }
        }
        for (String g : groups) {
            if (g.isEmpty() || g.length() > 4 || !g.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return false;
            }
        }
        return (Integer.parseInt(groups.getFirst(), 16) & 0xE000) == 0x2000;
    }

    /** Bốn số 0-255, không phải 0.0.0.0 / loopback / broadcast — mấy địa chỉ đó không định danh ai. */
    private static boolean isRealAddress(Matcher ip) {
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(ip.group(i + 1));
            if (octets[i] > 255) {
                return false;
            }
        }
        boolean unspecified = octets[0] == 0 && octets[1] == 0 && octets[2] == 0 && octets[3] == 0;
        boolean broadcast = octets[0] == 255 && octets[1] == 255 && octets[2] == 255 && octets[3] == 255;
        return !unspecified && !broadcast && octets[0] != 127;
    }

    private static void addAll(Map<String, Set<String>> found, String kind, Pattern p, String line, int group) {
        Matcher m = p.matcher(line);
        while (m.find()) {
            add(found, kind, m.group(group));
        }
    }

    private static void add(Map<String, Set<String>> found, String kind, String value) {
        String v = value == null ? "" : value.strip();
        if (v.length() >= MIN_LENGTH && !v.startsWith("[")) {
            found.computeIfAbsent(kind, k -> new TreeSet<>()).add(v);
        }
    }

    /**
     * Giá trị nhạy cảm trong một đoạn văn bản tự do, theo loại ({@code ip}, {@code secret}, {@code contact}) — cùng bộ
     * nhận diện dùng cho dòng log và câu hỏi. Cổng chặn trước khi gửi AI ({@code OutboundGate}) dùng lại hàm này để
     * không phụ thuộc regex của Sanitizer.
     */
    public static Map<String, Set<String>> inFreeText(String text) {
        Map<String, Set<String>> found = new TreeMap<>();
        if (text != null) {
            text.lines().forEach(line -> collectFromFreeText(line, found));
        }
        return found;
    }

    /** Số giá trị gốc khác nhau (đếm theo loại — cùng chuỗi ở hai loại tính hai). */
    public int count() {
        return byKind.values().stream().mapToInt(Set::size).sum();
    }

    /**
     * Giá trị gốc nào còn nằm nguyên văn trong {@code text}, đếm theo loại. So theo ranh giới chữ / số:
     * "C8CF631E-0" (fromTag) là chuỗi con của Call-ID "C8CF631E-0C6B-…" — không tính là lộ.
     */
    public Map<String, Integer> foundIn(String text) {
        Map<String, Integer> leaks = new TreeMap<>();
        if (text == null || text.isEmpty()) {
            return leaks;
        }
        byKind.forEach((kind, values) -> {
            int n = (int) values.stream().filter(v -> occursAsToken(text, v)).count();
            if (n > 0) {
                leaks.put(kind, n);
            }
        });
        return leaks;
    }

    private static boolean occursAsToken(String text, String value) {
        for (int at = text.indexOf(value); at >= 0; at = text.indexOf(value, at + 1)) {
            boolean leftOk = at == 0 || !isWordChar(text.charAt(at - 1));
            int end = at + value.length();
            boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
        }
        return false;
    }

    /** Chữ hoặc số liền kề thì không phải ranh giới — "192.0.2.4" không khớp bên trong "192.0.2.45". */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
