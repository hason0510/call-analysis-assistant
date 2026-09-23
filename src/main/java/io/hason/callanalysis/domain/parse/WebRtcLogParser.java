package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser cho `*_webrtc.log` — log goc cua thu vien libwebrtc.
 *
 * Ba dac diem quyet dinh cach viet parser nay:
 *
 * 1. Hai format khac nhau, moi file dung dung mot format:
 *      iOS     : [6652:953][260115] (RTCLogging.mm:34): (RTCAudioSession.mm:680 ...): message
 *      Android : peer_connection.cc: [6652:957][12108] (line 659): message
 *
 * 2. Moc thoi gian la TUONG DOI (giay:mili tu luc log khoi tao), khong phai gio tuyet doi,
 *    va truong giay co do rong BIEN THIEN — quan sat tu [000:000] toi [6652:953].
 *    Viet regex \d{3} se bo sot phan lon du lieu.
 *
 * 3. 22% so dong (5 966 / 26 712 trong data mau) la dong NOI TIEP cua ban ghi truoc,
 *    chu yeu khi iOS in mo ta audio route dai nhieu dong. Bo qua chung la mat 1/5 du lieu.
 */
public class WebRtcLogParser {

    private static final Pattern IOS = Pattern.compile(
            "^\\[(\\d+):(\\d{3})]\\[(\\d+)]\\s*(?:\\((?<origin>[^)]*)\\):)?\\s*(?<message>.*)$");

    private static final Pattern ANDROID = Pattern.compile(
            "^(?<module>[\\w.]+\\.(?:cc|mm|h)):\\s*\\[(\\d+):(\\d{3})]\\[(\\d+)]"
                    + "\\s*\\(line (?<line>\\d+)\\):\\s*(?<message>.*)$");

    /** Chuyen trang thai ICE — tin hieu manh nhat trong toan bo file. */
    private static final Pattern ICE_STATE = Pattern.compile(
            "IceConnectionState\\s+(\\w+)\\s*=>\\s*(\\w+)");

    public ParseResult parse(List<String> lines, ParseContext context) {
        if (lines == null || lines.isEmpty()) {
            return ParseResult.empty();
        }

        List<CanonicalEvent> events = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();
        PendingRecord pending = null;

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            if (line == null) {
                continue;
            }

            PendingRecord started = startRecord(line, lineNumber, context);
            if (started != null) {
                if (pending != null) {
                    events.add(pending.toEvent(context));
                }
                pending = started;
                continue;
            }

            if (line.isBlank()) {
                continue;
            }
            if (pending == null) {
                // Dong noi tiep ma chua co ban ghi nao truoc do — file bi cat dau.
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "dong noi tiep mo coi, khong co ban ghi truoc do"));
                continue;
            }
            pending.append(line);
        }

        if (pending != null) {
            events.add(pending.toEvent(context));
        }
        return ParseResult.of(events, warnings);
    }

    private PendingRecord startRecord(String line, int lineNumber, ParseContext context) {
        Matcher android = ANDROID.matcher(line);
        if (android.find()) {
            Duration offset = offset(android.group(2), android.group(3));
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("module", android.group("module"));
            attributes.put("thread", android.group(4));
            attributes.put("sourceLine", android.group("line"));
            attributes.put("platform", "android");
            return new PendingRecord(lineNumber, line, offset, android.group("message"), attributes);
        }

        Matcher ios = IOS.matcher(line);
        if (ios.find()) {
            Duration offset = offset(ios.group(1), ios.group(2));
            Map<String, String> attributes = new LinkedHashMap<>();
            String origin = ios.group("origin");
            if (origin != null && !origin.isBlank()) {
                attributes.put("module", origin);
            }
            attributes.put("thread", ios.group(3));
            attributes.put("platform", "ios");
            return new PendingRecord(lineNumber, line, offset, ios.group("message"), attributes);
        }
        return null;
    }

    private static Duration offset(String seconds, String millis) {
        return Duration.ofSeconds(Long.parseLong(seconds)).plusMillis(Long.parseLong(millis));
    }

    /** Ban ghi dang gom, co the con nhan them dong noi tiep. */
    private static final class PendingRecord {

        private final int lineNumber;
        private final StringBuilder rawLine;
        private final StringBuilder message;
        private final Duration offset;
        private final Map<String, String> attributes;

        PendingRecord(int lineNumber, String rawLine, Duration offset,
                      String message, Map<String, String> attributes) {
            this.lineNumber = lineNumber;
            this.rawLine = new StringBuilder(rawLine);
            this.message = new StringBuilder(message == null ? "" : message);
            this.offset = offset;
            this.attributes = attributes;
        }

        void append(String continuation) {
            rawLine.append('\n').append(continuation);
            message.append('\n').append(continuation);
        }

        CanonicalEvent toEvent(ParseContext context) {
            String text = message.toString();
            Classification classification = Classification.of(text, attributes.get("module"));

            Map<String, String> allAttributes = new LinkedHashMap<>(attributes);
            allAttributes.put("message", text);
            classification.extra().forEach(allAttributes::put);

            return new CanonicalEvent(
                    context.fileName() + "#" + lineNumber,
                    context.callId(),
                    context.leg(),
                    LogSource.WEBRTC,
                    // Khong co goc thoi gian tuyet doi — giu nguyen dang tuong doi.
                    EventTime.relative(offset),
                    classification.type(),
                    classification.name(),
                    allAttributes,
                    classification.severity(),
                    new SourceRef(context.fileName(), lineNumber, rawLine.toString()));
        }
    }

    private record Classification(EventType type, String name, Severity severity,
                                  Map<String, String> extra) {

        static Classification of(String message, String module) {
            Matcher ice = ICE_STATE.matcher(message);
            if (ice.find()) {
                String to = ice.group(2);
                Map<String, String> extra = new LinkedHashMap<>();
                extra.put("iceStateFrom", ice.group(1));
                extra.put("iceStateTo", to);
                Severity severity = "failed".equalsIgnoreCase(to) ? Severity.ERROR : Severity.INFO;
                return new Classification(EventType.ICE_EVENT, "IceConnectionState", severity, extra);
            }
            if (message.contains("Switching selected connection")) {
                return new Classification(EventType.ICE_EVENT, "SelectedConnectionSwitched",
                        Severity.INFO, Map.of());
            }
            if (message.contains("for the first time")) {
                return new Classification(EventType.PEER_CONNECTION_EVENT, "ChannelWritable",
                        Severity.INFO, Map.of());
            }
            if (isTurn(module, message)) {
                return new Classification(EventType.TURN_EVENT, "TurnPort", Severity.INFO, Map.of());
            }
            if (isPeerConnection(module, message)) {
                return new Classification(EventType.PEER_CONNECTION_EVENT, "PeerConnection",
                        Severity.INFO, Map.of());
            }
            return new Classification(EventType.LOG_MESSAGE, "LOG", Severity.INFO, Map.of());
        }

        /**
         * Chi phan loai la su kien TURN, KHONG suy ra la loi.
         * "Received TURN allocate error response ... code=401" la buoc bat tay xac thuc
         * chuan cua TURN (RFC 5766): tren toan bo data mau co 73 dong error va dung 73 dong
         * allocate thanh cong, khong cong nao that bai han. Viec ket luan loi hay khong
         * thuoc tang rule, khong phai tang parser.
         */
        private static boolean isTurn(String module, String message) {
            return (module != null && module.contains("turn_port")) || message.contains("TurnPort(");
        }

        private static boolean isPeerConnection(String module, String message) {
            return (module != null && module.contains("peer_connection"))
                    || message.contains("PeerConnection");
        }
    }
}
