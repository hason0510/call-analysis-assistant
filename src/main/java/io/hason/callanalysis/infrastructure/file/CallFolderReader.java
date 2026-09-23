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
 * Doc cac file log cua mot cuoc goi tu dia.
 *
 * Day la I/O nen thuoc tang infrastructure. Parser o tang domain chi nhan List&lt;String&gt;,
 * nho vay chung test duoc bang chuoi viet thang trong test.
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
            throw new UncheckedIOException("Khong doc duoc thu muc " + folder, e);
        }
    }

    /**
     * Log chua tieng Viet ("Loa ngoai", "Micro cua iPhone") nen bat buoc doc UTF-8.
     * File khong phai UTF-8 hop le van doc duoc, ky tu hong bi thay the thay vi nem loi —
     * parser khong duoc crash vi input xau (MVP muc 3.3).
     */
    public List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return decodeLenient(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Khong doc duoc file " + file, e);
        }
    }

    private List<String> decodeLenient(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException e) {
            throw new UncheckedIOException("Khong doc duoc file " + file, e);
        }
    }
}
