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
 * Parser cho `*_endcall.log` — file TSV tu mang schema cua chinh no.
 *
 * Chin dong dau (`#H1`..`#H9`) khai bao ten cot cho tung loai ban ghi; cot dau moi
 * dong du lieu la so hieu loai ban ghi. So luong header THAY DOI theo file: trong data
 * mau co file chi khai bao toi `#H5`, va ban ghi `#H9` (call summary) chi ton tai o
 * 2 tren 16 file — nen khong duoc gia dinh header nao la chac chan co.
 */
public class EndCallLogParser {

    /** Cot dac ta: dong bat dau bang #H la khai bao schema, khong phai du lieu. */
    private static final String HEADER_PREFIX = "#H";
    private static final String COL_TIMESTAMP = "#ts";
    private static final String COL_TAG = "#tag";

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

            // -1 BAT BUOC: khong co thi Java xoa het cot rong o cuoi dong,
            // ma end call log day cot rong -> lech cot hang loat khong bao loi.
            String[] cols = line.split("\t", -1);

            if (cols[0].startsWith(HEADER_PREFIX)) {
                readHeader(cols, lineNumber, schemas, warnings, context);
                continue;
            }

            try {
                toEvent(cols, lineNumber, line, schemas, context, warnings).ifPresent(events::add);
            } catch (RuntimeException e) {
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "khong doc duoc ban ghi: " + e.getMessage()));
            }
        }

        return ParseResult.of(events, warnings);
    }

    private void readHeader(String[] cols, int lineNumber, Map<String, List<String>> schemas,
                            List<ParseWarning> warnings, ParseContext context) {
        String recordType = cols[0].substring(HEADER_PREFIX.length());
        if (recordType.isBlank() || cols.length < 2) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "dong header khong hop le: " + cols[0]));
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
                    "chua khai bao #H" + recordType + " truoc dong du lieu nay"));
            return java.util.Optional.empty();
        }

        Map<String, String> row = zip(schema, cols, lineNumber, context, warnings);

        String rawTimestamp = row.get(COL_TIMESTAMP);
        Instant instant = parseEpochMillis(rawTimestamp);
        if (instant == null) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "timestamp khong doc duoc: " + rawTimestamp));
            return java.util.Optional.empty();
        }

        // #H1 mang callId cua chinh file. Lech voi callId dang phan tich la dau hieu
        // nguoi dung dinh kem file cua cuoc goi khac (MVP muc 6.4, ca kiem thu F03).
        if ("1".equals(recordType)) {
            String fileCallId = row.get("callId");
            if (fileCallId != null && !fileCallId.isBlank()
                    && context.callId() != null && !fileCallId.equals(context.callId())) {
                warnings.add(new ParseWarning(context.fileName(), lineNumber,
                        "callId trong file (" + fileCallId + ") khac callId dang phan tich ("
                                + context.callId() + ")"));
            }
        }

        RecordShape shape = RecordShape.of(recordType, row);

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

    /** Ghep ten cot voi gia tri. So cot lech van lay phan khop duoc, chi ghi canh bao. */
    private Map<String, String> zip(List<String> schema, String[] cols, int lineNumber,
                                    ParseContext context, List<ParseWarning> warnings) {
        int values = cols.length - 1;
        if (values != schema.size()) {
            warnings.add(new ParseWarning(context.fileName(), lineNumber,
                    "so cot lech: schema khai bao " + schema.size() + ", dong nay co " + values));
        }
        Map<String, String> row = new LinkedHashMap<>();
        int usable = Math.min(schema.size(), values);
        for (int i = 0; i < usable; i++) {
            row.put(schema.get(i), cols[i + 1]);
        }
        return row;
    }

    /** Bo cot rong: ban ghi #H7 co 158 cot va #H9 co 211 cot, phan lon de trong. */
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

    /** Anh xa so hieu ban ghi sang loai event va ten event. */
    private record RecordShape(EventType type, String name) {

        static RecordShape of(String recordType, Map<String, String> row) {
            return switch (recordType) {
                case "1" -> new RecordShape(EventType.CALL_METADATA, "CALL_METADATA");
                case "2" -> fromLogDetail(row);
                case "3" -> new RecordShape(EventType.SIGNALING_COMMAND,
                        orDefault(row.get("cmd"), "SIGNALING_COMMAND"));
                case "4" -> new RecordShape(EventType.QOS,
                        orDefault(row.get("cmd"), "QOS"));
                case "5" -> new RecordShape(EventType.USER_ACTION,
                        orDefault(row.get("signal"), "USER_ACTION"));
                case "6" -> new RecordShape(EventType.ICE_CANDIDATE, "ICE_CANDIDATE_PAIR");
                case "7" -> new RecordShape(EventType.MEDIA_STATS, "MEDIA_STATS");
                case "8" -> new RecordShape(EventType.LOG_MESSAGE, "CONFIG");
                case "9" -> new RecordShape(EventType.CALL_SUMMARY, "CALL_SUMMARY");
                default -> new RecordShape(EventType.LOG_MESSAGE, "RECORD_" + recordType);
            };
        }

        /**
         * Ban ghi #H2 (`log_detail`) cho phan lon noi dung. Nhan dang nhe cac dong
         * ICE de T7 lay evidence de hon; con lai giu nguyen la LOG_MESSAGE va de
         * Timeline Builder tu phat hien chuyen trang thai tu cot `status`.
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
