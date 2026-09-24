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
 * Parser cho `*_webrtc.log` — log gốc của thư viện libwebrtc.
 *
 * Ba đặc điểm quyết định cách viết parser này:
 *
 * 1. Hai format khác nhau, mỗi file dùng đúng một format:
 *      iOS     : [6652:953][260115] (RTCLogging.mm:34): (RTCAudioSession.mm:680 ...): message
 *      Android : peer_connection.cc: [6652:957][12108] (line 659): message
 *
 * 2. Mốc thời gian là TƯƠNG ĐỐI (giây:mili từ lúc log khởi tạo), không phải giờ tuyệt đối,
 *    và trường giây có độ rộng BIẾN THIÊN — quan sát từ [000:000] tới [12844:xxx].
 *    Viết regex \d{3} sẽ bỏ sót phần lớn dữ liệu.
 *
 * 3. 6,2% số dòng (1 649 / 26 695 trong 27 file WebRTC mẫu) là dòng NỐI TIẾP của bản ghi
 *    trước: 1 639 dòng ở iOS, chỉ 10 ở Android; khoảng một nửa là mô tả audio route của
 *    RTCAudioSession in ra nhiều dòng. Bỏ qua chúng là mất dữ liệu.
 *    Con số "22%" ghi trước đây đã được đếm bằng regex \d{3} nên coi nhầm mọi dòng có
 *    trường giây >= 1000 là dòng nối tiếp — chính là lỗi ở điểm 2.
 */
public class WebRtcLogParser {

    private static final Pattern IOS = Pattern.compile(
            "^\\[(\\d+):(\\d{3})]\\[(\\d+)]\\s*(?:\\((?<origin>[^)]*)\\):)?\\s*(?<message>.*)$");

    private static final Pattern ANDROID = Pattern.compile(
            "^(?<module>[\\w.]+\\.(?:cc|mm|h)):\\s*\\[(\\d+):(\\d{3})]\\[(\\d+)]"
                    + "\\s*\\(line (?<line>\\d+)\\):\\s*(?<message>.*)$");

    /** Chuyển trạng thái ICE — tín hiệu mạnh nhất trong toàn bộ file. */
    private static final Pattern ICE_STATE = Pattern.compile(
            "IceConnectionState\\s+(\\w+)\\s*=>\\s*(\\w+)");

    /** Wrapper log của iOS: ngoặc đầu tiên luôn là hằng số này, không mang thông tin. */
    private static final String RTC_LOGGING_WRAPPER = "RTCLogging.mm";

    /** Ngoặc thứ hai của dòng iOS đi qua wrapper — mới là nguồn thật. */
    private static final Pattern NESTED_ORIGIN = Pattern.compile("^\\(([^)]*)\\):\\s*");

    /** "connection.cc:1824" hoặc "RTCAudioSession.mm:680 -[RTCAudioSession handleRoute:]" */
    private static final Pattern ORIGIN_WITH_LINE =
            Pattern.compile("^([\\w.+-]+):(\\d+)(?:\\s+(.*))?$");

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

            PendingRecord started;
            try {
                started = startRecord(line, lineNumber, context);
            } catch (RuntimeException e) {
                // Khớp format nhưng mốc thời gian không đọc được (ví dụ trường giây
                // vượt quá long). Đóng bản ghi đang gom lại: các dòng nối tiếp phía sau
                // thuộc về dòng hỏng này, gắn vào bản ghi trước là sai.
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "mốc thời gian không đọc được: " + e.getMessage()));
                if (pending != null) {
                    events.add(pending.toEvent(context));
                    pending = null;
                }
                continue;
            }
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
                // Dòng nối tiếp mà chưa có bản ghi nào trước đó — file bị cắt đầu.
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "dòng nối tiếp mồ côi, không có bản ghi trước đó"));
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
            String message = ios.group("message");

            // Log Objective-C đi qua wrapper RTCLogging, khiến ngoặc đầu tiên luôn là
            // hằng số "RTCLogging.mm:34" còn nguồn thật nằm ở ngoặc thứ hai của message.
            // Log C++ gốc thì ngoặc đầu tiên đã là nguồn thật.
            if (origin != null && origin.startsWith(RTC_LOGGING_WRAPPER)) {
                Matcher nested = NESTED_ORIGIN.matcher(message);
                if (nested.find()) {
                    origin = nested.group(1);
                    message = message.substring(nested.end()).stripLeading();
                }
            }
            putOrigin(attributes, origin);
            attributes.put("thread", ios.group(3));
            attributes.put("platform", "ios");
            return new PendingRecord(lineNumber, line, offset, message, attributes);
        }
        return null;
    }

    /** Tách "connection.cc:1824" thành module + số dòng, thống nhất với Format 2. */
    private static void putOrigin(Map<String, String> attributes, String origin) {
        if (origin == null || origin.isBlank()) {
            return;
        }
        Matcher m = ORIGIN_WITH_LINE.matcher(origin);
        if (m.matches()) {
            attributes.put("module", m.group(1));
            attributes.put("sourceLine", m.group(2));
            if (m.group(3) != null && !m.group(3).isBlank()) {
                attributes.put("method", m.group(3).strip());
            }
        } else {
            attributes.put("module", origin);
        }
    }

    private static Duration offset(String seconds, String millis) {
        return Duration.ofSeconds(Long.parseLong(seconds)).plusMillis(Long.parseLong(millis));
    }

    /** Bản ghi đang gom, có thể còn nhận thêm dòng nối tiếp. */
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
                    // Không có gốc thời gian tuyệt đối — giữ nguyên dạng tương đối.
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
         * Chỉ phân loại là sự kiện TURN, KHÔNG suy ra là lỗi.
         * "Received TURN allocate error response ... code=401" là bước bắt tay xác thực
         * chuẩn của TURN (RFC 8656): trên toàn bộ data mẫu có 73 dòng error và đúng 73 dòng
         * allocate thành công, không cổng nào thất bại hẳn. Việc kết luận lỗi hay không
         * thuộc tầng rule, không phải tầng parser.
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
