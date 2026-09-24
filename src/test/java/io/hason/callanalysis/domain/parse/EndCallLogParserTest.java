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
    @DisplayName("file chỉ khai báo vài header vẫn parse bình thường")
    void missingHeadersAreFine() {
        ParseResult result = parser.parse(List.of(H1, H2,
                "1\t1789700842873\tinfo\tU1\tCALL-1\tios\tcallee\tINIT",
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit"), context);

        assertThat(result.events()).hasSize(2);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("loại bản ghi suy ra từ #tag, KHÔNG từ số hiệu — bố cục thật của caller DE7DD314")
    void recordTypeComesFromTagNotNumber() {
        // Caller DE7DD314 không có bản ghi qos, nên mọi số hiệu từ H4 trở đi lùi một bậc
        // so với callee: stats là H6, config là H7, summary là H8. Map cứng theo số từng
        // biến 359 dòng stats thành ICE_CANDIDATE và coi dòng config là "chỉ số cuối".
        ParseResult result = parser.parse(List.of(
                "#H4\t#ts\t#tag\tmsg\toriginator\tsignal",
                "#H5\t#ts\t#tag\taddress\tcandidateType\tip",
                "#H6\t#ts\t#tag\taudio.audioMos\taudio.bytesReceived",
                "#H7\t#ts\t#tag\tinitCallConfig\twebrtcConfig",
                "#H8\t#ts\t#tag\taudio.audioMos\tendCall.duration",
                "4\t1789700850297\tsignal\t\tremote\tunmuted",
                "5\t1789700851306\tlocal_candidate\t\trelay\t14.238.62.98",
                "6\t1789700851306\tstats\t4.42737\t2698",
                "7\t1789701211075\tconfig\t{}\t{}",
                "8\t1789701211078\tendcall\t4.42397\t360535"), context);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.events()).extracting(CanonicalEvent::type).containsExactly(
                EventType.USER_ACTION,
                EventType.ICE_CANDIDATE,
                EventType.MEDIA_STATS,
                EventType.LOG_MESSAGE,
                EventType.CALL_SUMMARY);
        assertThat(result.events().get(2).attribute("audio.bytesReceived")).isEqualTo("2698");
        assertThat(result.events().get(3).name()).isEqualTo("CONFIG");
        assertThat(result.events().get(4).attribute("endCall.duration")).isEqualTo("360535");
    }

    @Test
    @DisplayName("cùng tag nhưng khác số hiệu giữa hai file -> cùng loại event (callee 2D9057AA)")
    void sameTagDifferentNumberGivesSameType() {
        // Callee 2D9057AA: stats là H5, summary là H7 — ngược hẳn với cách đánh số của
        // file khác, nơi H7 lại là stats.
        ParseResult result = parser.parse(List.of(
                "#H5\t#ts\t#tag\taudio.bytesReceived\ttransport.countMediaFail",
                "#H7\t#ts\t#tag\taudio.bytesReceived\ttransport.hasMediaFail",
                "5\t1789688596209\tstats\t0\t1",
                "5\t1789688597209\tstats\t0\t2",
                "7\t1789688621377\tendcall\t0\t1"), context);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.events()).extracting(CanonicalEvent::type).containsExactly(
                EventType.MEDIA_STATS, EventType.MEDIA_STATS, EventType.CALL_SUMMARY);
    }

    @Test
    @DisplayName("tag lạ hoặc thiếu -> giữ lại dưới dạng LOG_MESSAGE, không throw")
    void unknownOrMissingTagIsKept() {
        ParseResult result = parser.parse(List.of(
                "#H4\t#ts\t#tag\tvalue",
                "#H5\t#ts\tvalue",
                "4\t1789700850297\ttag_moi\tx",
                "5\t1789700850298\ty"), context);

        assertThat(result.events()).extracting(CanonicalEvent::type)
                .containsExactly(EventType.LOG_MESSAGE, EventType.LOG_MESSAGE);
        assertThat(result.events()).extracting(CanonicalEvent::name)
                .containsExactly("RECORD_tag_moi", "RECORD_H5");
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
