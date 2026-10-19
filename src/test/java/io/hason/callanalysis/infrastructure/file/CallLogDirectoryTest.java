package io.hason.callanalysis.infrastructure.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tìm thư mục log của một Call-ID cho phân tích hàng loạt (T10). */
class CallLogDirectoryTest {

    private static final String CALL = "06BCA48C-6861-8A9F-19E3-0923EAD71440";

    @TempDir
    Path root;

    private Path folder(String relative) throws IOException {
        return Files.createDirectories(root.resolve(relative));
    }

    @Test
    @DisplayName("Call-ID viết HOA trong ES khớp thư mục viết thường của dataset mới (bẫy #23)")
    void caseInsensitive() throws IOException {
        Path lower = folder("new/" + CALL.toLowerCase());

        assertThat(CallLogDirectory.scan(List.of(root)).find(CALL)).contains(lower);
    }

    @Test
    @DisplayName("thư mục nhóm success/ fail/ được duyệt; không có thư mục trùng Call-ID -> rỗng")
    void nestedAndMissing() throws IOException {
        Path nested = folder("sample/fail/" + CALL);

        CallLogDirectory directory = CallLogDirectory.scan(List.of(root));

        assertThat(directory.find(CALL)).contains(nested);
        assertThat(directory.find("FFFFFFFF-0000-0000-0000-000000000000")).isEmpty();
        assertThat(directory.find(null)).isEmpty();
    }

    @Test
    @DisplayName("sâu quá " + CallLogDirectory.MAX_DEPTH + " cấp thì không tìm")
    void depthLimit() throws IOException {
        folder("a/b/c/" + CALL);

        assertThat(CallLogDirectory.scan(List.of(root)).find(CALL)).isEmpty();
    }

    @Test
    @DisplayName("hai thư mục gốc cùng có Call-ID -> giữ thư mục của gốc đầu, báo trùng; thư mục nhóm trùng tên thì không báo")
    void duplicatesAcrossRoots() throws IOException {
        Path first = folder("one/success/" + CALL);
        Path second = folder("two/success/" + CALL);

        CallLogDirectory directory = CallLogDirectory.scan(List.of(root.resolve("one"), root.resolve("two")));

        assertThat(directory.find(CALL)).contains(first);
        assertThat(directory.duplicates(List.of(CALL))).singleElement().asString()
                .contains(first.toString()).contains(second.toString());
        assertThat(directory.duplicates(List.of("FFFFFFFF-0000-0000-0000-000000000000"))).isEmpty();
    }

    @Test
    @DisplayName("thư mục gốc không tồn tại -> báo lỗi cấu hình, không im lặng chạy như không có log")
    void missingRoot() {
        assertThatThrownBy(() -> CallLogDirectory.scan(List.of(root.resolve("khong-co"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("khong-co");
    }

    @Test
    @DisplayName("không có thư mục gốc nào -> mọi cuộc chỉ có signaling")
    void noRoots() {
        assertThat(CallLogDirectory.scan(List.of()).find(CALL)).isEmpty();
    }
}
