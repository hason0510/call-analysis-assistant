package io.hason.callanalysis.infrastructure.file;

import io.hason.callanalysis.domain.validation.AttachedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CallFolderReaderTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("F04 — file vượt giới hạn KHÔNG được nạp nội dung, chỉ báo kích thước")
    void oversizedFileIsNotLoaded() throws IOException {
        Files.write(folder.resolve("big.log"), new byte[2048]);
        Files.writeString(folder.resolve("small.log"), "dong 1\ndong 2\n");

        List<AttachedFile> files = new CallFolderReader(DataSize.ofKilobytes(1)).readAll(folder);

        assertThat(files).extracting(AttachedFile::name).containsExactly("big.log", "small.log");
        assertThat(files.get(0).loaded()).isFalse();
        assertThat(files.get(0).sizeBytes()).isEqualTo(2048);
        assertThat(files.get(1).lines()).containsExactly("dong 1", "dong 2");
    }
}
