package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.validation.AttachedFile;

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
 * Giới hạn: chưa lấy địa chỉ IPv6 (cần parser địa chỉ, không làm bằng regex an toàn được); giá trị nhạy cảm
 * nằm trong câu hỏi người dùng không được tính.
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
            Matcher ip = IPV4.matcher(line);
            while (ip.find()) {
                if (isRealAddress(ip)) {
                    add(found, "ip", ip.group());
                }
            }
        }
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

    /** Chữ hoặc số liền kề thì không phải ranh giới — "1.2.3.4" không khớp bên trong "1.2.3.45". */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
