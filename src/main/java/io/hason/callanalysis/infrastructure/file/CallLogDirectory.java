package io.hason.callanalysis.infrastructure.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Tìm thư mục log máy của một Call-ID trong một hay nhiều thư mục gốc — cho phân tích hàng loạt (T10), nơi danh sách
 * Call-ID đến từ Elasticsearch chứ không từ thư mục.
 *
 * Quy ước như mọi lệnh CLI: thư mục log của một cuộc gọi mang tên Call-ID, nằm tối đa {@value #MAX_DEPTH} cấp dưới thư
 * mục gốc ({@code ai20k_sample/fail/2D9057AA-…}, {@code ai20k_new_dataset/06bca48c-…}). So tên KHÔNG phân biệt hoa
 * thường: Call-ID trong ES viết hoa, thư mục của dataset mới viết thường (bẫy #23).
 *
 * Hai thư mục trùng Call-ID: giữ thư mục gặp trước (theo thứ tự thư mục gốc, rồi đường dẫn), báo qua
 * {@link #duplicates(List)} — không chọn im lặng.
 */
public final class CallLogDirectory {

    static final int MAX_DEPTH = 3;

    private final Map<String, Path> byCallId = new LinkedHashMap<>();
    private final Map<String, List<Path>> duplicates = new LinkedHashMap<>();

    private CallLogDirectory() {
    }

    /** Duyệt các thư mục gốc một lần; thư mục gốc không tồn tại là lỗi cấu hình nên ném ra. */
    public static CallLogDirectory scan(List<Path> roots) {
        CallLogDirectory directory = new CallLogDirectory();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Không có thư mục log: " + root);
            }
            try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
                walk.filter(p -> !p.equals(root))
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(Path::toString))
                        .forEach(directory::add);
            } catch (IOException e) {
                throw new UncheckedIOException("Không duyệt được thư mục " + root, e);
            }
        }
        return directory;
    }

    private void add(Path folder) {
        String key = key(folder.getFileName().toString());
        Path existing = byCallId.putIfAbsent(key, folder);
        if (existing != null) {
            duplicates.computeIfAbsent(key, k -> new ArrayList<>()).add(folder);
        }
    }

    public Optional<Path> find(String callId) {
        return callId == null ? Optional.empty() : Optional.ofNullable(byCallId.get(key(callId)));
    }

    /**
     * Call-ID có hơn một thư mục, chỉ xét {@code callIds} — thư mục nhóm như {@code success/} trùng tên giữa hai thư mục
     * gốc thì không phải chuyện cần báo.
     */
    public List<String> duplicates(List<String> callIds) {
        List<String> out = new ArrayList<>();
        for (String callId : callIds) {
            List<Path> skipped = duplicates.get(key(callId));
            if (skipped != null) {
                out.add(callId + ": dùng " + byCallId.get(key(callId)) + ", bỏ " + skipped);
            }
        }
        return out;
    }

    private static String key(String name) {
        return name.trim().toUpperCase(Locale.ROOT);
    }
}
