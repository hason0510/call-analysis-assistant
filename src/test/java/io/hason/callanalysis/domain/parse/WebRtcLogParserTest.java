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
    @DisplayName("Format 2 (Android): tách được module, thread và số dòng nguồn")
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
    @DisplayName("Format 1 (iOS): khối thời gian đứng đầu dòng")
    void parsesIosFormat() {
        ParseResult result = parser.parse(List.of(
                "[4712:147][260115] (RTCLogging.mm:34): Incrementing activation count."), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("platform")).isEqualTo("ios");
        assertThat(e.attribute("thread")).isEqualTo("260115");
        assertThat(e.attribute("message")).isEqualTo("Incrementing activation count.");
    }

    @Test
    @DisplayName("iOS: log C++ gốc thì ngoặc đầu tiên đã là nguồn thật")
    void iosNativeLogUsesFirstParenthesis() {
        ParseResult result = parser.parse(List.of(
                "[6661:424][259] (connection.cc:1824): Conn[8dcc6e10CRWS]: Sent STUN BINDING request"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.attribute("module")).isEqualTo("connection.cc");
        assertThat(e.attribute("sourceLine")).isEqualTo("1824");
        assertThat(e.attribute("message")).startsWith("Conn[");
    }

    @Test
    @DisplayName("iOS: log Objective-C đi qua wrapper -> lấy nguồn ở ngoặc THỨ HAI")
    void iosWrappedLogUsesNestedOrigin() {
        // 691 dòng trong data mẫu đi qua RTCLogging.mm:34 — hằng số không mang thông tin.
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
    @DisplayName("mốc thời gian là TƯƠNG ĐỐI, không phải giờ tuyệt đối")
    void timeIsRelativeNotAbsolute() {
        ParseResult result = parser.parse(List.of(
                "peer_connection.cc: [6652:957][12108] (line 659): x"), context);

        EventTime time = result.events().getFirst().time();
        assertThat(time).isInstanceOf(EventTime.Relative.class);
        assertThat(((EventTime.Relative) time).sinceLogStart())
                .isEqualTo(Duration.ofSeconds(6652).plusMillis(957));
    }

    @Test
    @DisplayName("trường giây có độ rộng biến thiên: [000:000] và [6652:953] đều đọc được")
    void secondsFieldHasVariableWidth() {
        // Bẫy thật: regex \d{3} chỉ khớp [000:000] và bỏ sót phần lớn dữ liệu.
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
    @DisplayName("dòng nối tiếp được gộp vào bản ghi trước, không sinh event riêng")
    void continuationLinesAreFolded() {
        // 22% số dòng trong data mẫu (5 966/26 712) là dòng nối tiếp kiểu này.
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
        // số dòng trỏ về dòng BẮT ĐẦU bản ghi, không phải dòng cuối
        assertThat(first.sourceRef().lineNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("dòng nối tiếp mồ côi ở đầu file -> cảnh báo, không throw")
    void orphanContinuationIsWarned() {
        ParseResult result = parser.parse(List.of(
                "day la phan duoi cua ban ghi bi cat mat",
                "[000:000][259] (RTCLogging.mm:34): ban ghi that su"), context);

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("mồ côi");
    }

    @Test
    @DisplayName("chuyển trạng thái ICE được tách thành thuộc tính, thất bại đánh dấu ERROR")
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
    @DisplayName("dòng TURN chỉ được PHÂN LOẠI, KHÔNG bị kết luận là lỗi")
    void turnLinesAreClassifiedNotJudged() {
        // "allocate error response code=401" là bước bắt tay xác thực chuẩn của TURN.
        // Trên data mẫu: 73 dòng error và đúng 73 dòng allocate thành công.
        // Kết luận lỗi hay không thuộc tầng rule, không phải tầng parser.
        ParseResult result = parser.parse(List.of(
                "turn_port.cc: [0:114][8429] (line 1687): TurnPort(Port[x]-Remote[1.2.3.4:3478/udp]: "
                        + "Received TURN allocate error response, id=616c, code=401"), context);

        CanonicalEvent e = result.events().getFirst();
        assertThat(e.type()).isEqualTo(EventType.TURN_EVENT);
        assertThat(e.severity()).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("input rác / rỗng / dòng cực dài không làm crash parser")
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
    @DisplayName("kết quả tất định: cùng input cho ra cùng danh sách eventId")
    void parsingIsDeterministic() {
        List<String> lines = List.of(
                "[000:000][259] (RTCLogging.mm:34): mot",
                "[000:035][259] (RTCLogging.mm:34): hai");

        assertThat(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .isEqualTo(parser.parse(lines, context).events().stream().map(CanonicalEvent::eventId).toList())
                .containsExactly("caller_webrtc.log#1", "caller_webrtc.log#2");
    }
}
