package io.hason.callanalysis.infrastructure.file;

import io.hason.callanalysis.domain.validation.AttachedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Đọc các file log của một cuộc gọi từ đĩa.
 *
 * Đây là I/O nên thuộc tầng infrastructure. Parser ở tầng domain chỉ nhận List&lt;String&gt;,
 * nhờ vậy chúng test được bằng chuỗi viết thẳng trong test.
 */
@Component
public class CallFolderReader {

    private final long maxBytesPerFile;

    /** Cùng khoá cấu hình với CallLogNormalizationService: đọc và kiểm theo một giới hạn. */
    public CallFolderReader(@Value("${call-analysis.files.max-size-per-file:20MB}") DataSize maxFileSize) {
        this.maxBytesPerFile = maxFileSize.toBytes();
    }

    /**
     * Đọc mọi file trong thư mục. File vượt giới hạn KHÔNG được nạp nội dung — chỉ báo kích
     * thước để File Validator loại và nêu tên (MVP mục 6.4, ca F04). Đọc hết rồi mới kiểm thì
     * giới hạn không bảo vệ được bộ nhớ.
     */
    public List<AttachedFile> readAll(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + folder, e);
        }
    }

    /** Một file, cùng quy tắc giới hạn kích thước với {@link #readAll}. Evaluation Runner đọc theo danh sách của case. */
    public AttachedFile read(Path file) {
        String name = file.getFileName().toString();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file " + file, e);
        }
        return size > maxBytesPerFile
                ? AttachedFile.notLoaded(name, size)
                : new AttachedFile(name, size, readLines(file));
    }

    /**
     * Log chứa tiếng Việt ("Loa ngoài", "Micro của iPhone") nên bắt buộc đọc UTF-8.
     * File không phải UTF-8 hợp lệ vẫn đọc được, ký tự hỏng bị thay thế thay vì ném lỗi —
     * parser không được crash vì input xấu (MVP mục 3.3).
     */
    public List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return decodeLenient(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file " + file, e);
        }
    }

    /** Giới hạn kích thước mỗi file — Chat API dùng cùng giới hạn để không đọc file quá cỡ vào bộ nhớ. */
    public long maxBytesPerFile() {
        return maxBytesPerFile;
    }

    /**
     * Nội dung file tải lên (Chat API) → dòng, cùng cách tách dòng với {@link #readLines}: \n, \r\n, \r.
     * Byte không phải UTF-8 hợp lệ bị thay bằng U+FFFD, không ném lỗi — File Validator dựa vào chính
     * ký tự này để nhận ra file hỏng.
     */
    public static List<String> decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).lines().toList();
    }

    private List<String> decodeLenient(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file " + file, e);
        }
    }
}
