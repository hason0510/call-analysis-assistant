package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class EndCallLogParserTest {

    private final EndCallLogParser parser = new EndCallLogParser();
    private final ParseContext context = ParseContext.of("callee_endcall.log", "CALL-1", Leg.CALLEE);

    private static final String H1 = "#H1\t#ts\t#tag\tappUserId\tcallId\tplatform\trole\tstatus";
    private static final String H2 = "#H2\t#ts\t#tag\tmsg\tstatus\ttype";
    private static final String H3 = "#H3\t#ts\t#tag\tackCmd\tcmd\tcseq\tpayload";

    @Test
    @DisplayName("đọc schema từ chính file rồi ghép tên cột với giá trị")
    void readsSchemaFromFileItself() {
        ParseResult result = parser.parse(List.of(
                H1,
                "1\t1789700842873\tinfo\tUS43EGOIN7G\tCALL-1\tios\tcallee\tINIT"), context);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.events()).hasSize(1);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.type()).isEqualTo(EventType.CALL_METADATA);
        assertThat(e.source()).isEqualTo(LogSource.ENDCALL);
        assertThat(e.attribute("role")).isEqualTo("callee");
        assertThat(e.attribute("platform")).isEqualTo("ios");
        assertThat(e.sourceRef().citation()).isEqualTo("callee_endcall.log:2");
    }

    @Test
    @DisplayName("#ts là epoch millis, chuyển sang giờ tuyệt đối của đồng hồ client")
    void timestampIsEpochMillisOnClientClock() {
        ParseResult result = parser.parse(List.of(H2,
                "2\t1789700842905\tlog_detail\t_emitOpening\tINVITE_RECEIVED\temit"), context);

        EventTime time = result.events().getFirst().time();
        assertThat(time).isInstanceOf(EventTime.Absolute.class);
        EventTime.Absolute absolute = (EventTime.Absolute) time;
        assertThat(absolute.instant()).isEqualTo(Instant.ofEpochMilli(1789700842905L));
        assertThat(absolute.clock()).isEqualTo(io.hason.callanalysis.domain.event.ClockDomain.CLIENT_CALLEE);
    }

    @Test
    @DisplayName("cột rỗng ở CUỐI dòng không bị mất — đây là bẫy của split(\"\\t\") thiếu -1")
    void trailingEmptyColumnsAreNotDropped() {
        // Dòng này có 3 giá trị cuối đều rỗng. Nếu dùng split("\t") không có -1,
        // Java cắt hết cột rỗng ở đuôi và parser sẽ báo lệch cột.
        ParseResult result = parser.parse(List.of(H3,
                "3\t1789700842905\tsend_cmd\tC_UNKNOWN\tTRYING\t\t"), context);

        assertThat(result.warnings()).isEmpty();
        CanonicalEvent e = result.events().getFirst();
        assertThat(e.name()).isEqualTo("TRYING");
        // cột rỗng bị loại khỏi attributes, nhưng KHÔNG gây cảnh báo lệch cột
        assertThat(e.attributes()).doesNotContainKeys("cseq", "payload");
    }

    @Test
    @DisplayName("không có bản ghi #H9 vẫn parse bình thường — chỉ 2/16 file trong data mẫu có")
    void missingHeadersAreFine() {
        ParseResult result = parser.parse(List.of(H1, H2,
                "1\t1789700842873\tinfo\tU1\tCALL-1\tios\tcallee\tINIT",
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit"), context);

        assertThat(result.events()).hasSize(2);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("dòng dữ liệu trước khi có header tương ứng -> cảnh báo, không throw")
    void dataBeforeHeaderProducesWarning() {
        ParseResult result = parser.parse(List.of(
                "7\t1789700842905\tstats\t4.42"), context);

        assertThat(result.events()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("#H7");
    }

    @Test
    @DisplayName("số cột lệch -> vẫn lấy phần khớp được, kèm cảnh báo")
    void columnCountMismatchIsRecoverable() {
        ParseResult result = parser.parse(List.of(H2,
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit\tTHUA\tTHEM"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("số cột lệch");
        assertThat(result.events().getFirst().attribute("status")).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("timestamp hỏng -> bỏ dòng đó kèm cảnh báo, các dòng khác vẫn đọc")
    void malformedTimestampSkipsOnlyThatLine() {
        ParseResult result = parser.parse(List.of(H2,
                "2\tKHONG-PHAI-SO\tlog_detail\tmsg\tANSWERED\temit",
                "2\t1789700842905\tlog_detail\tmsg2\tCONFIRMED\temit"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("timestamp");
    }

    @Test
    @DisplayName("callId trong file khác callId đang phân tích -> cảnh báo (ca kiểm thử F03)")
    void mismatchedCallIdIsFlagged() {
        ParseResult result = parser.parse(List.of(H1,
                "1\t1789700842873\tinfo\tU1\tCALL-KHAC\tios\tcallee\tINIT"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason())
                .contains("CALL-KHAC").contains("CALL-1");
    }

    @Test
    @DisplayName("bản ghi #H2 có onIceConnectionChange được phân loại là ICE_EVENT")
    void iceMessagesAreTyped() {
        ParseResult result = parser.parse(List.of(H2,
                "2\t1789700849767\tlog_detail\tonIceConnectionChange: CHECKING\tANSWERED\twebrtc"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.type()).isEqualTo(EventType.ICE_EVENT);
        assertThat(e.name()).isEqualTo("onIceConnectionChange");
    }

    @Test
    @DisplayName("input rác / rỗng / dòng cực dài không làm crash parser")
    void invalidInputNeverThrows() {
        assertThatCode(() -> {
            assertThat(parser.parse(List.of(), context).events()).isEmpty();
            assertThat(parser.parse(null, context).events()).isEmpty();
            parser.parse(List.of("", "   ", "\t\t\t"), context);
            parser.parse(List.of("day khong phai TSV", "\u0000\u0001 nhi phan"), context);
            parser.parse(List.of("#H", "#H1", "1\t"), context);
            parser.parse(List.of(H2, "2\t1\tx\t" + "A".repeat(200_000) + "\tS\temit"), context);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("kết quả tất định: cùng input cho ra cùng danh sách eventId")
    void parsingIsDeterministic() {
        List<String> lines = List.of(H1, H2,
                "1\t1789700842873\tinfo\tU1\tCALL-1\tios\tcallee\tINIT",
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit");

        // hai dòng đầu là đặc tả #H1/#H2, nên bản ghi dữ liệu nằm ở dòng 3 và 4
        assertThat(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .isEqualTo(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .containsExactly("callee_endcall.log#3", "callee_endcall.log#4");
    }
}
