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
    @DisplayName("doc schema tu chinh file roi ghep ten cot voi gia tri")
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
    @DisplayName("#ts la epoch millis, chuyen sang gio tuyet doi cua dong ho client")
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
    @DisplayName("cot rong o CUOI dong khong bi mat — day la bay cua split(\"\\t\") thieu -1")
    void trailingEmptyColumnsAreNotDropped() {
        // Dong nay co 3 gia tri cuoi deu rong. Neu dung split("\t") khong co -1,
        // Java cat het cot rong o duoi va parser se bao lech cot.
        ParseResult result = parser.parse(List.of(H3,
                "3\t1789700842905\tsend_cmd\tC_UNKNOWN\tTRYING\t\t"), context);

        assertThat(result.warnings()).isEmpty();
        CanonicalEvent e = result.events().getFirst();
        assertThat(e.name()).isEqualTo("TRYING");
        // cot rong bi loai khoi attributes, nhung KHONG gay canh bao lech cot
        assertThat(e.attributes()).doesNotContainKeys("cseq", "payload");
    }

    @Test
    @DisplayName("khong co ban ghi #H9 van parse binh thuong — chi 2/16 file trong data mau co")
    void missingHeadersAreFine() {
        ParseResult result = parser.parse(List.of(H1, H2,
                "1\t1789700842873\tinfo\tU1\tCALL-1\tios\tcallee\tINIT",
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit"), context);

        assertThat(result.events()).hasSize(2);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("dong du lieu truoc khi co header tuong ung -> canh bao, khong throw")
    void dataBeforeHeaderProducesWarning() {
        ParseResult result = parser.parse(List.of(
                "7\t1789700842905\tstats\t4.42"), context);

        assertThat(result.events()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("#H7");
    }

    @Test
    @DisplayName("so cot lech -> van lay phan khop duoc, kem canh bao")
    void columnCountMismatchIsRecoverable() {
        ParseResult result = parser.parse(List.of(H2,
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit\tTHUA\tTHEM"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("so cot lech");
        assertThat(result.events().getFirst().attribute("status")).isEqualTo("ANSWERED");
    }

    @Test
    @DisplayName("timestamp hong -> bo dong do kem canh bao, cac dong khac van doc")
    void malformedTimestampSkipsOnlyThatLine() {
        ParseResult result = parser.parse(List.of(H2,
                "2\tKHONG-PHAI-SO\tlog_detail\tmsg\tANSWERED\temit",
                "2\t1789700842905\tlog_detail\tmsg2\tCONFIRMED\temit"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("timestamp");
    }

    @Test
    @DisplayName("callId trong file khac callId dang phan tich -> canh bao (ca kiem thu F03)")
    void mismatchedCallIdIsFlagged() {
        ParseResult result = parser.parse(List.of(H1,
                "1\t1789700842873\tinfo\tU1\tCALL-KHAC\tios\tcallee\tINIT"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason())
                .contains("CALL-KHAC").contains("CALL-1");
    }

    @Test
    @DisplayName("ban ghi #H2 co onIceConnectionChange duoc phan loai la ICE_EVENT")
    void iceMessagesAreTyped() {
        ParseResult result = parser.parse(List.of(H2,
                "2\t1789700849767\tlog_detail\tonIceConnectionChange: CHECKING\tANSWERED\twebrtc"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.type()).isEqualTo(EventType.ICE_EVENT);
        assertThat(e.name()).isEqualTo("onIceConnectionChange");
    }

    @Test
    @DisplayName("input rac / rong / dong cuc dai khong lam crash parser")
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
    @DisplayName("ket qua tat dinh: cung input cho ra cung danh sach eventId")
    void parsingIsDeterministic() {
        List<String> lines = List.of(H1, H2,
                "1\t1789700842873\tinfo\tU1\tCALL-1\tios\tcallee\tINIT",
                "2\t1789700842905\tlog_detail\tmsg\tANSWERED\temit");

        // hai dong dau la dac ta #H1/#H2, nen ban ghi du lieu nam o dong 3 va 4
        assertThat(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .isEqualTo(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .containsExactly("callee_endcall.log#3", "callee_endcall.log#4");
    }
}
