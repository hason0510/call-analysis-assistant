package io.hason.callanalysis.infrastructure.file;

import io.hason.callanalysis.domain.validation.AttachedFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /** Hai dòng đầu của end call log thật, có chữ tiếng Việt để bắt lỗi giải mã. */
    private static final String LOG = "#H1\t#ts\t#tag\tcallId\trole\r\n1\t1789700842905\tinfo\tCALL-1\tLoa ngoài\r\n";
    private static final List<String> LINES =
            List.of("#H1\t#ts\t#tag\tcallId\trole", "1\t1789700842905\tinfo\tCALL-1\tLoa ngoài");

    private static byte[] withPrefix(byte[] prefix, byte[] body) {
        byte[] out = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(body, 0, out, prefix.length, body.length);
        return out;
    }

    @Test
    @DisplayName("BOM UTF-8 (Notepad, PowerShell 5.1) bị bỏ — không dính vào #H1 (Peer Evaluation 2026-10-20)")
    void utf8BomIsStripped() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

        assertThat(CallFolderReader.decode(withPrefix(bom, LOG.getBytes(StandardCharsets.UTF_8)))).isEqualTo(LINES);
        assertThat(CallFolderReader.decode(LOG.getBytes(StandardCharsets.UTF_8))).isEqualTo(LINES);
    }

    @Test
    @DisplayName("UTF-16 có BOM (Notepad \"Unicode\") đọc đúng, LE lẫn BE — không còn byte 0 khiến file bị coi là nhị phân")
    void utf16WithBomIsDecoded() {
        byte[] le = withPrefix(new byte[] {(byte) 0xFF, (byte) 0xFE}, LOG.getBytes(StandardCharsets.UTF_16LE));
        byte[] be = withPrefix(new byte[] {(byte) 0xFE, (byte) 0xFF}, LOG.getBytes(StandardCharsets.UTF_16BE));

        assertThat(CallFolderReader.decode(le)).isEqualTo(LINES);
        assertThat(CallFolderReader.decode(be)).isEqualTo(LINES);
        assertThat(String.join("", CallFolderReader.decode(le))).doesNotContain("\u0000");
    }

    @Test
    @DisplayName("đọc từ đĩa (CLI, runner) giải mã y như file tải lên: BOM bị bỏ, byte hỏng thành U+FFFD chứ không ném lỗi")
    void diskReadMatchesUploadDecoding() throws IOException {
        Path withBom = folder.resolve("caller_endcall.log");
        Files.write(withBom, withPrefix(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                LOG.getBytes(StandardCharsets.UTF_8)));
        Path broken = folder.resolve("broken.log");
        Files.write(broken, new byte[] {'a', (byte) 0xC3, 'b'});

        CallFolderReader reader = new CallFolderReader(DataSize.ofMegabytes(1));

        assertThat(reader.readLines(withBom)).isEqualTo(LINES);
        assertThat(reader.readLines(broken)).containsExactly("a" + Character.toString(0xFFFD) + "b");
    }
}
