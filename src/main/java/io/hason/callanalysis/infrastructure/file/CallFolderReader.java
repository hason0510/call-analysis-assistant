package io.hason.callanalysis.infrastructure.file;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Đọc các file log của một cuộc gọi từ đĩa.
 *
 * Đây là I/O nên thuộc tầng infrastructure. Parser ở tầng domain chỉ nhận List&lt;String&gt;,
 * nhờ vậy chúng test được bằng chuỗi viết thẳng trong test.
 */
@Component
public class CallFolderReader {

    public Map<String, List<String>> readAll(Path folder) {
        try (Stream<Path> files = Files.list(folder)) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> result.put(p.getFileName().toString(), readLines(p)));
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được thư mục " + folder, e);
        }
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

    private List<String> decodeLenient(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file " + file, e);
        }
    }
}
