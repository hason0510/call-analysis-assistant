package io.hason.callanalysis.domain.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Một file người dùng đính kèm, trước khi kiểm.
 *
 * Kích thước đi riêng với nội dung: file vượt giới hạn thì bên đọc (CLI, Chat API) KHÔNG nạp
 * nội dung vào bộ nhớ, chỉ báo kích thước — {@code lines} khi đó là null. Kiểm kích thước sau
 * khi đã đọc hết file thì giới hạn mất tác dụng.
 */
public record AttachedFile(String name, long sizeBytes, List<String> lines) {

    public AttachedFile {
        lines = lines == null ? null : List.copyOf(lines);
    }

    /** File đã đọc: kích thước tính lại từ nội dung (UTF-8, mỗi dòng cộng 1 byte xuống dòng). */
    public static AttachedFile of(String name, List<String> lines) {
        long size = lines.stream().mapToLong(l -> l.getBytes(StandardCharsets.UTF_8).length + 1L).sum();
        return new AttachedFile(name, size, lines);
    }

    /** File không đọc nội dung vì đã vượt giới hạn. */
    public static AttachedFile notLoaded(String name, long sizeBytes) {
        return new AttachedFile(name, sizeBytes, null);
    }

    public boolean loaded() {
        return lines != null;
    }
}
