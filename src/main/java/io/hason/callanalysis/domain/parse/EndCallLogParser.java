package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser cho `*_endcall.log` — file TSV tự mang schema của chính nó.
 *
 * Các dòng `#Hn` ở đầu file khai báo tên cột cho từng loại bản ghi; cột đầu mỗi dòng
 * dữ liệu là số hiệu `n`. Số hiệu KHÔNG cố định giữa các file: nó được đánh theo thứ
 * tự các loại bản ghi mà file đó có. Cùng là periodic stats nhưng là `H5` ở callee
 * 2D9057AA, `H6` ở caller DE7DD314, `H7` ở callee DE7DD314; call summary thì là `H5`,
 * `H7`, `H8` hoặc `H9`. Thứ duy nhất ổn định là cột `#tag` (`stats`, `endcall`, ...),
 * nên loại event được suy ra từ `#tag`, còn số hiệu chỉ dùng để tra đúng header.
 */
public class EndCallLogParser {

    /** Cột đặc tả: dòng bắt đầu bằng #H là khai báo schema, không phải dữ liệu. */
    private static final String HEADER_PREFIX = "#H";
    private static final String COL_TIMESTAMP = "#ts";
    private static final String COL_TAG = "#tag";
    private static final String TAG_INFO = "info";

    public ParseResult parse(List<String> lines, ParseContext context) {
        if (lines == null || lines.isEmpty()) {
            return ParseResult.empty();
        }

        Map<String, List<String>> schemas = new HashMap<>();
        List<CanonicalEvent> events = new ArrayList<>();
        List<ParseWarning> warnings = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }

            // -1 BẮT BUỘC: không có thì Java xoá hết cột rỗng ở cuối dòng,
            // mà end call log đầy cột rỗng -> lệch cột hàng loạt không báo lỗi.
            String[] cols = line.split("\t", -1);

            if (cols[0].startsWith(HEADER_PREFIX)) {
                readHeader(cols, lineNumber, schemas, warnings, context);
                continue;
            }

            try {
                toEvent(cols, lineNumber, line, schemas, context, warnings).ifPresent(events::add);
            } catch (RuntimeException e) {
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "không đọc được bản ghi: " + e.getMessage()));
            }
        }

        return ParseResult.of(events, warnings);
    }

    private void readHeader(String[] cols, int lineNumber, Map<String, List<String>> schemas,
                            List<ParseWarning> warnings, ParseContext context) {
        String recordType = cols[0].substring(HEADER_PREFIX.length());
        if (recordType.isBlank() || cols.length < 2) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "dòng header không hợp lệ: " + cols[0]));
            return;
        }
        schemas.put(recordType, List.of(cols).subList(1, cols.length));
    }

    private java.util.Optional<CanonicalEvent> toEvent(String[] cols, int lineNumber, String rawLine,
                                                       Map<String, List<String>> schemas,
                                                       ParseContext context, List<ParseWarning> warnings) {
        String recordType = cols[0];
        List<String> schema = schemas.get(recordType);
        if (schema == null) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "chưa khai báo #H" + recordType + " trước dòng dữ liệu này"));
            return java.util.Optional.empty();
        }

        Map<String, String> row = zip(schema, cols, lineNumber, context, warnings);

        String rawTimestamp = row.get(COL_TIMESTAMP);
        Instant instant = parseEpochMillis(rawTimestamp);
        if (instant == null) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "timestamp không đọc được: " + rawTimestamp));
            return java.util.Optional.empty();
        }

        String tag = row.get(COL_TAG);

        // Bản ghi `info` mang callId của chính file. Lệch với callId đang phân tích là dấu
        // hiệu người dùng đính kèm file của cuộc gọi khác (MVP mục 6.4, ca kiểm thử F03).
        if (TAG_INFO.equals(tag)) {
            String fileCallId = row.get("callId");
            if (fileCallId != null && !fileCallId.isBlank()
                    && context.callId() != null && !fileCallId.equals(context.callId())) {
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "callId trong file (" + fileCallId + ") khác callId đang phân tích ("
                                + context.callId() + ")"));
            }
        }

        RecordShape shape = RecordShape.of(tag, recordType, row);

        return java.util.Optional.of(new CanonicalEvent(
                context.fileName() + "#" + lineNumber,
                context.callId(),
                context.leg(),
                LogSource.ENDCALL,
                EventTime.absolute(instant, context.clientClockDomain()),
                shape.type(),
                shape.name(),
                nonBlankOnly(row),
                Severity.INFO,
                new SourceRef(context.fileName(), lineNumber, rawLine)));
    }

    /** Ghép tên cột với giá trị. Số cột lệch vẫn lấy phần khớp được, chỉ ghi cảnh báo. */
    private Map<String, String> zip(List<String> schema, String[] cols, int lineNumber,
                                    ParseContext context, List<ParseWarning> warnings) {
        int values = cols.length - 1;
        if (values != schema.size()) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "số cột lệch: schema khai báo " + schema.size() + ", dòng này có " + values));
        }
        Map<String, String> row = new LinkedHashMap<>();
        int usable = Math.min(schema.size(), values);
        for (int i = 0; i < usable; i++) {
            row.put(schema.get(i), cols[i + 1]);
        }
        return row;
    }

    /** Bỏ cột rỗng: bản ghi `stats` có tới 158 cột và `endcall` tới 212 cột, phần lớn để trống. */
    private static Map<String, String> nonBlankOnly(Map<String, String> row) {
        Map<String, String> kept = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                kept.put(k, v);
            }
        });
        return kept;
    }

    private static Instant parseEpochMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Ánh xạ `#tag` của bản ghi sang loại event và tên event.
     *
     * Tag lạ (hoặc thiếu) không làm hỏng parse: bản ghi vẫn được giữ dưới dạng LOG_MESSAGE,
     * tên mang theo tag hoặc số hiệu để còn truy ngược.
     */
    private record RecordShape(EventType type, String name) {

        static RecordShape of(String tag, String recordType, Map<String, String> row) {
            if (tag == null || tag.isBlank()) {
                return new RecordShape(EventType.LOG_MESSAGE, "RECORD_H" + recordType);
            }
            return switch (tag) {
                case TAG_INFO -> new RecordShape(EventType.CALL_METADATA, "CALL_METADATA");
                case "log_detail" -> fromLogDetail(row);
                case "send_cmd", "recv_cmd" -> new RecordShape(EventType.SIGNALING_COMMAND,
                        orDefault(row.get("cmd"), "SIGNALING_COMMAND"));
                case "qos" -> new RecordShape(EventType.QOS,
                        orDefault(row.get("cmd"), "QOS"));
                case "signal" -> new RecordShape(EventType.USER_ACTION,
                        orDefault(row.get("signal"), "USER_ACTION"));
                case "local_candidate", "remote_candidate" ->
                        new RecordShape(EventType.ICE_CANDIDATE, "ICE_CANDIDATE");
                case "stats" -> new RecordShape(EventType.MEDIA_STATS, "MEDIA_STATS");
                case "config" -> new RecordShape(EventType.LOG_MESSAGE, "CONFIG");
                case "endcall" -> new RecordShape(EventType.CALL_SUMMARY, "CALL_SUMMARY");
                default -> new RecordShape(EventType.LOG_MESSAGE, "RECORD_" + tag);
            };
        }

        /**
         * Bản ghi `log_detail` cho phần lớn nội dung. Nhận dạng nhẹ các dòng ICE / kết nối
         * để lọc theo loại khi cần; còn lại giữ nguyên là LOG_MESSAGE. Cột `status` được giữ
         * trong attributes, hiện chưa bước nào dùng tới. Chuyển trạng thái ICE mà các tầng
         * sau dùng (iceStateTo) lấy từ WebRTC log, không từ đây.
         */
        static RecordShape fromLogDetail(Map<String, String> row) {
            String msg = orDefault(row.get("msg"), "");
            if (msg.contains("onIceConnectionChange")) {
                return new RecordShape(EventType.ICE_EVENT, "onIceConnectionChange");
            }
            if (msg.startsWith("onIce") || msg.contains("IceGathering") || msg.contains("_handleAddIce")) {
                return new RecordShape(EventType.ICE_EVENT, firstToken(msg));
            }
            if (msg.contains("onConnectionChange")) {
                return new RecordShape(EventType.PEER_CONNECTION_EVENT, "onConnectionChange");
            }
            return new RecordShape(EventType.LOG_MESSAGE, firstToken(msg));
        }

        private static String firstToken(String msg) {
            if (msg.isBlank()) {
                return "LOG_DETAIL";
            }
            int cut = msg.indexOf(':');
            int space = msg.indexOf(' ');
            if (cut < 0 || (space >= 0 && space < cut)) {
                cut = space;
            }
            String token = cut > 0 ? msg.substring(0, cut) : msg;
            return token.length() > 64 ? token.substring(0, 64) : token;
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
