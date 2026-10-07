package io.hason.callanalysis.domain.validation;

import io.hason.callanalysis.domain.parse.DetectedLogType;
import io.hason.callanalysis.domain.validation.FileValidation.Reason;
import io.hason.callanalysis.domain.validation.FileValidation.RejectedFile;
import io.hason.callanalysis.domain.validation.FileValidation.ValidFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Ca kiểm thử F02-F04 của MVP mục 6.4 (F01 ở CallLogNormalizationServiceTest — cần gán leg). */
class FileValidatorTest {

    private final FileValidator validator = new FileValidator();

    /** End call log tối thiểu đúng định dạng thật: `#H1` khai báo cột, bản ghi `info` mang callId. */
    private static List<String> endCallLog(String callId) {
        return List.of(
                "#H1\t#ts\t#tag\tappUserId\tcallId\trole",
                "1\t1789700842905\tinfo\tU1\t" + callId + "\tcaller");
    }

    private static List<String> webRtcLog() {
        return List.of("[000:120][5507] (peer_connection.cc:659): DoSetLocalDescription: offer");
    }

    private static AttachedFile file(String name, List<String> lines) {
        return AttachedFile.of(name, lines);
    }

    @Test
    @DisplayName("file hợp lệ, đúng Call-ID -> được nhận, kèm loại nhận diện theo nội dung")
    void validFilesAreAccepted() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("caller_endcall.log", endCallLog("CALL-1")),
                file("caller_webrtc.log", webRtcLog())));

        assertThat(v.rejected()).isEmpty();
        assertThat(v.accepted()).extracting(ValidFile::type)
                .containsExactly(DetectedLogType.ENDCALL_LOG, DetectedLogType.WEBRTC_IOS);
    }

    @Test
    @DisplayName("F02 — tên file nói WebRTC nhưng nội dung là end call log -> nhận theo NỘI DUNG")
    void f02TypeComesFromContentNotName() {
        // 9B556E56 trong for_test: file tên caller_webrtc.log mà nội dung là end call log.
        FileValidation v = validator.validate("CALL-1", List.of(
                file("caller_webrtc.log", endCallLog("CALL-1"))));

        assertThat(v.accepted()).singleElement()
                .satisfies(f -> assertThat(f.type()).isEqualTo(DetectedLogType.ENDCALL_LOG));
    }

    @Test
    @DisplayName("F02 — nội dung không phải log nào -> loại, nêu tên file")
    void f02UnrecognizedContentIsRejected() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("caller_webrtc.log", List.of("day khong phai log", "@@@"))));

        assertThat(v.accepted()).isEmpty();
        assertThat(v.rejected()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.UNRECOGNIZED);
            assertThat(r.message()).startsWith("caller_webrtc.log: ").contains("không nhận diện được");
        });
    }

    @Test
    @DisplayName("F03 — end call log mang Call-ID khác -> LOẠI khỏi phân tích, nêu cả hai Call-ID")
    void f03ForeignCallIdIsRejected() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("callee_endcall.log", endCallLog("CALL-KHAC")),
                file("caller_endcall.log", endCallLog("CALL-1"))));

        assertThat(v.accepted()).extracting(ValidFile::name).containsExactly("caller_endcall.log");
        assertThat(v.rejected()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.CALL_ID_MISMATCH);
            assertThat(r.message()).contains("CALL-KHAC").contains("CALL-1");
        });
    }

    @Test
    @DisplayName("F03 — không ai nói Call-ID -> lấy theo số đông end call log, file lệch bị loại")
    void f03CallIdResolvedFromFilesWhenNotGiven() {
        FileValidation v = validator.validate(null, List.of(
                file("a_endcall.log", endCallLog("CALL-KHAC")),
                file("b_endcall.log", endCallLog("CALL-1")),
                file("c_endcall.log", endCallLog("CALL-1"))));

        assertThat(v.callId()).isEqualTo("CALL-1");
        assertThat(v.rejected()).extracting(RejectedFile::name).containsExactly("a_endcall.log");
    }

    @Test
    @DisplayName("F03 — hoà số phiếu -> chọn Call-ID của file đứng trước theo tên (tất định)")
    void f03TieIsBrokenByFileName() {
        FileValidation v = validator.validate(null, List.of(
                file("z_endcall.log", endCallLog("CALL-Z")),
                file("a_endcall.log", endCallLog("CALL-A"))));

        assertThat(v.callId()).isEqualTo("CALL-A");
    }

    @Test
    @DisplayName("F03 — chỉ có WebRTC log (không mang Call-ID) và không ai nói Call-ID -> callId null")
    void callIdUnknownWithoutEndCallLog() {
        FileValidation v = validator.validate(null, List.of(file("caller_webrtc.log", webRtcLog())));

        assertThat(v.callId()).isNull();
        assertThat(v.accepted()).hasSize(1);
    }

    @Test
    @DisplayName("F04 — vượt giới hạn kích thước -> loại, KHÔNG cần nội dung, nêu dung lượng")
    void f04OversizedFileIsRejectedWithoutContent() {
        FileValidator small = new FileValidator(1024 * 1024);

        FileValidation v = small.validate("CALL-1", List.of(
                AttachedFile.notLoaded("caller_webrtc.log", 5L * 1024 * 1024)));

        assertThat(v.rejected()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.TOO_LARGE);
            assertThat(r.message()).contains("5.0 MB").contains("1.0 MB");
        });
    }

    @Test
    @DisplayName("F04 — file đã đọc nhưng vẫn vượt giới hạn -> loại")
    void f04LoadedButOversizedIsRejected() {
        FileValidator tiny = new FileValidator(50);

        FileValidation v = tiny.validate("CALL-1", List.of(file("caller_endcall.log", endCallLog("CALL-1"))));

        assertThat(v.rejected()).extracting(RejectedFile::reason).containsExactly(Reason.TOO_LARGE);
    }

    @Test
    @DisplayName("F04 — file nhị phân (có NUL) -> loại vì hỏng, không phải vì 'không nhận diện được'")
    void f04BinaryFileIsCorrupt() {
        // Giống file_rac.log trong docs/kiem-thu-tay.md: printf '\x00\x01\x02 rac\xff\xfe'
        FileValidation v = validator.validate("CALL-1", List.of(
                file("file_rac.log", List.of("\u0000\u0001\u0002 rac��"))));

        assertThat(v.rejected()).singleElement()
                .satisfies(r -> assertThat(r.reason()).isEqualTo(Reason.CORRUPT));
    }

    @Test
    @DisplayName("F04 — nội dung giải mã UTF-8 hỏng hàng loạt -> loại vì hỏng")
    void f04MostlyReplacementCharactersIsCorrupt() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("anh.log", List.of("���PNG����"))));

        assertThat(v.rejected()).extracting(RejectedFile::reason).containsExactly(Reason.CORRUPT);
    }

    @Test
    @DisplayName("vài ký tự hỏng lẻ tẻ trong log thật KHÔNG làm cả file bị loại")
    void fewReplacementCharactersAreTolerated() {
        List<String> lines = new java.util.ArrayList<>(endCallLog("CALL-1"));
        lines.add("1\t1789700842999\tlog_detail\tLoa ngo�i\tok\tinfo");

        FileValidation v = validator.validate("CALL-1", List.of(file("caller_endcall.log", lines)));

        assertThat(v.rejected()).isEmpty();
    }

    @Test
    @DisplayName("F04 — file rỗng hoặc chỉ có dòng trắng -> loại với lý do 'file rỗng'")
    void f04EmptyFileIsRejected() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("a.log", List.of()),
                file("b.log", List.of("", "   "))));

        assertThat(v.rejected()).extracting(RejectedFile::reason).containsExactly(Reason.EMPTY, Reason.EMPTY);
        assertThat(v.rejected().getFirst().message()).contains("file rỗng");
    }

    @Test
    @DisplayName("thứ tự kết quả theo tên file, không theo thứ tự đính kèm (tất định)")
    void resultIsOrderedByName() {
        FileValidation v = validator.validate("CALL-1", List.of(
                file("z.log", List.of()),
                file("callee_endcall.log", endCallLog("CALL-1")),
                file("a.log", List.of()),
                file("caller_endcall.log", endCallLog("CALL-1"))));

        assertThat(v.accepted()).extracting(ValidFile::name)
                .containsExactly("callee_endcall.log", "caller_endcall.log");
        assertThat(v.rejected()).extracting(RejectedFile::name).containsExactly("a.log", "z.log");
    }
}
