package io.hason.callanalysis.infrastructure.evaluation;

import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaseFileResolverTest {

    private static final String CALL = "2D9057AA-C496-48B2-946A-98FA2896D086";
    private static final String OTHER = "DE7DD314-F432-45CB-BCB4-AE9103CC0919";

    @TempDir
    Path root;

    private final CaseFileResolver resolver = new CaseFileResolver(new CallFolderReader(DataSize.ofMegabytes(20)));

    @BeforeEach
    void layout() throws IOException {
        // Cùng bố cục ai20k_sample: <log>/fail/<call_id>/…
        Path call = Files.createDirectories(root.resolve("fail").resolve(CALL));
        Files.writeString(call.resolve("callee_endcall.log"), "a\nb\n");
        Path other = Files.createDirectories(root.resolve("success").resolve(OTHER));
        Files.writeString(other.resolve("caller_endcall.log"), "c\n");
    }

    private static BenchmarkCase caseWith(String callId, String... files) {
        return new BenchmarkCase("c", callId, List.of(files), List.of(new BenchmarkCase.Question("q", null)),
                null, null, null, null, "dev");
    }

    @Test
    @DisplayName("tên file trần -> tìm thư mục trùng call_id ở cấp bất kỳ, không phân biệt hoa thường")
    void bareNameFoundInNestedCallFolder() {
        CaseFileResolver.Resolved r = resolver.resolve(root, caseWith(CALL.toLowerCase(), "callee_endcall.log"));

        assertThat(r.error()).isNull();
        assertThat(r.files()).extracting(AttachedFile::name).containsExactly("callee_endcall.log");
        assertThat(r.files().getFirst().lines()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("đường dẫn tương đối -> ghép file của cuộc khác được (biến thể F03), tên gửi đi là tên file")
    void relativePathForForeignFile() {
        CaseFileResolver.Resolved r = resolver.resolve(root,
                caseWith(CALL, "callee_endcall.log", "success/" + OTHER + "/caller_endcall.log"));

        assertThat(r.error()).isNull();
        assertThat(r.files()).extracting(AttachedFile::name).containsExactly("callee_endcall.log", "caller_endcall.log");
    }

    @Test
    @DisplayName("file liệt kê mà không có -> lỗi case, không coi là 'thiếu file'")
    void listedFileMissing() {
        CaseFileResolver.Resolved r = resolver.resolve(root, caseWith(CALL, "caller_endcall.log"));

        assertThat(r.files()).isEmpty();
        assertThat(r.error()).contains("caller_endcall.log");
    }

    @Test
    @DisplayName("không có thư mục call_id -> lỗi case nêu rõ")
    void callFolderMissing() {
        CaseFileResolver.Resolved r = resolver.resolve(root, caseWith("KHONG-CO", "caller_endcall.log"));

        assertThat(r.error()).contains("KHONG-CO");
    }

    @Test
    @DisplayName("đường dẫn trỏ ra ngoài thư mục log -> lỗi")
    void pathEscapingRootRejected() {
        CaseFileResolver.Resolved r = resolver.resolve(root, caseWith(CALL, "../ngoai.log"));

        assertThat(r.error()).contains("ra ngoài");
    }
}
