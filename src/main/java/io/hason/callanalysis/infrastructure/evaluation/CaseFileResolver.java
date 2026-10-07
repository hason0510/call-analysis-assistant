package io.hason.callanalysis.infrastructure.evaluation;

import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tìm và đọc file log của một case, như người dùng đính kèm trên Web UI.
 *
 * Mẫu MVP mục 6.3 ghi tên file trần ({@code files: [caller_endcall.log, …]}) mà không nói thư mục, nên:
 * - tên trần → tìm trong thư mục con trùng tên {@code call_id}, ở bất kỳ cấp nào dưới thư mục log
 *   ({@code <log>/<call_id>/} hay {@code <log>/fail/<call_id>/} đều được);
 * - có dấu {@code /} → đường dẫn tương đối tới thư mục log, dùng cho biến thể file của tập `dev`
 *   (ví dụ ghép file của cuộc khác để thử F03). Không được trỏ ra ngoài thư mục log.
 *
 * File trong danh sách mà không có trên đĩa là lỗi của case, không phải "thiếu file": ca thiếu file
 * viết bằng cách KHÔNG liệt kê file đó.
 */
@Component
public class CaseFileResolver {

    private static final int MAX_DEPTH = 4;

    private final CallFolderReader reader;

    public CaseFileResolver(CallFolderReader reader) {
        this.reader = reader;
    }

    /** @param error lý do không chạy được case; khi khác null thì {@code files} rỗng */
    public record Resolved(List<AttachedFile> files, String error) {

        static Resolved failed(String error) {
            return new Resolved(List.of(), error);
        }
    }

    public Resolved resolve(Path logRoot, BenchmarkCase c) {
        Path root = logRoot.toAbsolutePath().normalize();
        Path callFolder = null;
        List<AttachedFile> files = new ArrayList<>();
        for (String entry : c.files()) {
            Path file;
            boolean relativePath = entry.contains("/") || entry.contains("\\");
            if (relativePath) {
                file = root.resolve(entry.replace('\\', '/')).normalize();
                if (!file.startsWith(root)) {
                    return Resolved.failed("'" + entry + "' trỏ ra ngoài thư mục log");
                }
            } else {
                if (callFolder == null) {
                    List<Path> found = findCallFolders(root, c.callId());
                    if (found.isEmpty()) {
                        return Resolved.failed("không có thư mục '" + c.callId() + "' dưới " + root);
                    }
                    if (found.size() > 1) {
                        return Resolved.failed("có " + found.size() + " thư mục trùng tên '" + c.callId() + "': "
                                + found.stream().map(root::relativize).toList());
                    }
                    callFolder = found.getFirst();
                }
                file = callFolder.resolve(entry);
            }
            if (!Files.isRegularFile(file)) {
                return Resolved.failed("không có file '" + entry + "'"
                        + (relativePath ? "" : " trong " + root.relativize(callFolder)));
            }
            files.add(reader.read(file));
        }
        return new Resolved(files, null);
    }

    /** Không phân biệt hoa thường: Call-ID là UUID, người viết case có thể gõ chữ thường. */
    private static List<Path> findCallFolders(Path root, String callId) {
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(callId))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Không duyệt được thư mục log " + root, e);
        }
    }
}
