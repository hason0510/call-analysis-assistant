package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WebRtcLogParserTest {

    private final WebRtcLogParser parser = new WebRtcLogParser();
    private final ParseContext context = ParseContext.of("caller_webrtc.log", "CALL-1", Leg.CALLER);

    @Test
    @DisplayName("Format 2 (Android): tach duoc module, thread va so dong nguon")
    void parsesAndroidFormat() {
        ParseResult result = parser.parse(List.of(
                "peer_connection.cc: [6652:957][12108] (line 659): PeerConnection create with session: 647513"),
                context);

        assertThat(result.warnings()).isEmpty();
        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("module")).isEqualTo("peer_connection.cc");
        assertThat(e.attribute("thread")).isEqualTo("12108");
        assertThat(e.attribute("sourceLine")).isEqualTo("659");
        assertThat(e.attribute("platform")).isEqualTo("android");
        assertThat(e.type()).isEqualTo(EventType.PEER_CONNECTION_EVENT);
    }

    @Test
    @DisplayName("Format 1 (iOS): khoi thoi gian dung dau dong")
    void parsesIosFormat() {
        ParseResult result = parser.parse(List.of(
                "[4712:147][260115] (RTCLogging.mm:34): Incrementing activation count."), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("platform")).isEqualTo("ios");
        assertThat(e.attribute("thread")).isEqualTo("260115");
        assertThat(e.attribute("message")).isEqualTo("Incrementing activation count.");
    }

    @Test
    @DisplayName("iOS: log C++ goc thi ngoac dau tien da la nguon that")
    void iosNativeLogUsesFirstParenthesis() {
        ParseResult result = parser.parse(List.of(
                "[6661:424][259] (connection.cc:1824): Conn[8dcc6e10CRWS]: Sent STUN BINDING request"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("module")).isEqualTo("connection.cc");
        assertThat(e.attribute("sourceLine")).isEqualTo("1824");
        assertThat(e.attribute("message")).startsWith("Conn[");
    }

    @Test
    @DisplayName("iOS: log Objective-C di qua wrapper -> lay nguon o ngoac THU HAI")
    void iosWrappedLogUsesNestedOrigin() {
        // 691 dong trong data mau di qua RTCLogging.mm:34 — hang so khong mang thong tin.
        ParseResult result = parser.parse(List.of(
                "[4712:147][260115] (RTCLogging.mm:34): (RTCAudioSession.mm:680 "
                        + "-[RTCAudioSession incrementActivationCount]): Incrementing activation count."),
                context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("module")).isEqualTo("RTCAudioSession.mm");
        assertThat(e.attribute("sourceLine")).isEqualTo("680");
        assertThat(e.attribute("method")).isEqualTo("-[RTCAudioSession incrementActivationCount]");
        assertThat(e.attribute("message")).isEqualTo("Incrementing activation count.");
    }

    @Test
    @DisplayName("moc thoi gian la TUONG DOI, khong phai gio tuyet doi")
    void timeIsRelativeNotAbsolute() {
        ParseResult result = parser.parse(List.of(
                "peer_connection.cc: [6652:957][12108] (line 659): x"), context);

        EventTime time = result.events().getFirst().time();
        assertThat(time).isInstanceOf(EventTime.Relative.class);
        assertThat(((EventTime.Relative) time).sinceLogStart())
                .isEqualTo(Duration.ofSeconds(6652).plusMillis(957));
    }

    @Test
    @DisplayName("truong giay co do rong bien thien: [000:000] va [6652:953] deu doc duoc")
    void secondsFieldHasVariableWidth() {
        // Bay that: regex \d{3} chi khop [000:000] va bo sot phan lon du lieu.
        ParseResult result = parser.parse(List.of(
                "[000:000][259] (RTCLogging.mm:34): bat dau",
                "[6652:953][259] (RTCLogging.mm:34): gan hai gio sau"), context);

        assertThat(result.events()).hasSize(2);
        assertThat(((EventTime.Relative) result.events().get(0).time()).sinceLogStart())
                .isEqualTo(Duration.ZERO);
        assertThat(((EventTime.Relative) result.events().get(1).time()).sinceLogStart())
                .isEqualTo(Duration.ofSeconds(6652).plusMillis(953));
    }

    @Test
    @DisplayName("dong noi tiep duoc gop vao ban ghi truoc, khong sinh event rieng")
    void continuationLinesAreFolded() {
        // 22% so dong trong data mau (5 966/26 712) la dong noi tiep kieu nay.
        ParseResult result = parser.parse(List.of(
                "[000:002][259] (RTCLogging.mm:34): Previous route: <AVAudioSessionRouteDescription: 0x127a93c30,",
                "inputs = (",
                ");",
                "outputs = (",
                "    \"<AVAudioSessionPortDescription: type = Speaker; name = Loa ngoai>\"",
                ")>",
                "[000:035][259] (RTCLogging.mm:34): Audio route changed"), context);

        assertThat(result.events()).hasSize(2);
        assertThat(result.warnings()).isEmpty();

        CanonicalEvent first = result.events().getFirst();
        assertThat(first.attribute("message")).contains("inputs = (").contains("Loa ngoai");
        assertThat(first.sourceRef().rawLine().lines().count()).isEqualTo(6);
        // so dong tro ve dong BAT DAU ban ghi, khong phai dong cuoi
        assertThat(first.sourceRef().lineNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("dong noi tiep mo coi o dau file -> canh bao, khong throw")
    void orphanContinuationIsWarned() {
        ParseResult result = parser.parse(List.of(
                "day la phan duoi cua ban ghi bi cat mat",
                "[000:000][259] (RTCLogging.mm:34): ban ghi that su"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("mo coi");
    }

    @Test
    @DisplayName("chuyen trang thai ICE duoc tach thanh thuoc tinh, that bai danh dau ERROR")
    void iceStateTransitionIsExtracted() {
        ParseResult result = parser.parse(List.of(
                "peer_connection.cc: [20:195][8431] (line 2032): Changing IceConnectionState checking => failed",
                "peer_connection.cc: [6661:648][8431] (line 2032): Changing IceConnectionState checking => connected"),
                context);

        CanonicalEvent failed = result.events().get(0);
        assertThat(failed.type()).isEqualTo(EventType.ICE_EVENT);
        assertThat(failed.attribute("iceStateFrom")).isEqualTo("checking");
        assertThat(failed.attribute("iceStateTo")).isEqualTo("failed");
        assertThat(failed.severity()).isEqualTo(Severity.ERROR);

        CanonicalEvent connected = result.events().get(1);
        assertThat(connected.attribute("iceStateTo")).isEqualTo("connected");
        assertThat(connected.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("dong TURN chi duoc PHAN LOAI, KHONG bi ket luan la loi")
    void turnLinesAreClassifiedNotJudged() {
        // "allocate error response code=401" la buoc bat tay xac thuc chuan cua TURN.
        // Tren data mau: 73 dong error va dung 73 dong allocate thanh cong.
        // Ket luan loi hay khong thuoc tang rule, khong phai tang parser.
        ParseResult result = parser.parse(List.of(
                "turn_port.cc: [0:114][8429] (line 1687): TurnPort(Port[x]-Remote[1.2.3.4:3478/udp]: "
                        + "Received TURN allocate error response, id=616c, code=401"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.type()).isEqualTo(EventType.TURN_EVENT);
        assertThat(e.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("input rac / rong / dong cuc dai khong lam crash parser")
    void invalidInputNeverThrows() {
        assertThatCode(() -> {
            assertThat(parser.parse(List.of(), context).events()).isEmpty();
            assertThat(parser.parse(null, context).events()).isEmpty();
            parser.parse(List.of("", "  ", "\t"), context);
            parser.parse(List.of("\u0000\u0001 nhi phan", "[khong:dung]dinh dang"), context);
            parser.parse(List.of("[000:000][1] (x): " + "B".repeat(200_000)), context);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ket qua tat dinh: cung input cho ra cung danh sach eventId")
    void parsingIsDeterministic() {
        List<String> lines = List.of(
                "[000:000][259] (RTCLogging.mm:34): mot",
                "[000:035][259] (RTCLogging.mm:34): hai");

        assertThat(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .isEqualTo(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .containsExactly("caller_webrtc.log#1", "caller_webrtc.log#2");
    }
}
