package io.hason.callanalysis.infrastructure.file;

import io.hason.callanalysis.domain.validation.AttachedFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
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

    /** Cùng cách giải mã với file tải lên qua Chat API ({@link #decode}). */
    public List<String> readLines(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file " + file, e);
        }
    }

    /** Giới hạn kích thước mỗi file — Chat API dùng cùng giới hạn để không đọc file quá cỡ vào bộ nhớ. */
    public long maxBytesPerFile() {
        return maxBytesPerFile;
    }

    /**
     * Nội dung file → dòng, tách theo \n, \r\n, \r. Log chứa tiếng Việt ("Loa ngoài", "Micro của iPhone") nên đọc
     * UTF-8; có BOM thì theo BOM:
     * - BOM UTF-8 ({@code EF BB BF}) bị bỏ. Notepad và PowerShell 5.1 ({@code Out-File}, {@code Set-Content -Encoding
     *   UTF8}) chèn nó khi lưu lại file; giữ lại thì nó dính vào {@code #H1} và parser không nhận ra header.
     * - BOM UTF-16 LE / BE ({@code FF FE} / {@code FE FF}, Notepad "Unicode") → đọc UTF-16. Đọc như UTF-8 thì mỗi ký tự
     *   kèm một byte 0, và File Validator loại cả file vì "có ký tự nhị phân".
     *
     * Byte không giải mã được bị thay bằng U+FFFD, không ném lỗi — parser không được crash vì input xấu (MVP mục 3.3),
     * và File Validator dựa vào chính ký tự này để nhận ra file hỏng.
     */
    public static List<String> decode(byte[] bytes) {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            skip = 3;
        } else if (startsWith(bytes, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (startsWith(bytes, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        }
        return new String(bytes, skip, bytes.length - skip, charset).lines().toList();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
