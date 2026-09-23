package io.hason.callanalysis.domain.parse;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Nhận diện loại file theo NỘI DUNG, không theo tên file.
 *
 * Yêu cầu này không phải giả định: data mẫu có file `calleer_webrtc.log` (thừa chữ 'e'),
 * và MVP mục 6.1 T3 có hẳn test case F02 "Tên file không khớp nội dung".
 */
public class FileTypeDetector {

    /** Format 1 (iOS): [6652:953][260115] (RTCLogging.mm:34): ... */
    static final Pattern WEBRTC_IOS_LINE =
            Pattern.compile("^\\[\\d+:\\d{3}]\\[\\d+]\\s");

    /** Format 2 (Android): peer_connection.cc: [6652:957][12108] (line 659): ... */
    static final Pattern WEBRTC_ANDROID_LINE =
            Pattern.compile("^[\\w.]+\\.(?:cc|mm|h):\\s*\\[\\d+:\\d{3}]\\[\\d+]\\s");

    /** Dòng đầu tiên của end call log luôn là đặc tả format của bản ghi loại 1. */
    private static final Pattern ENDCALL_HEADER =
            Pattern.compile("^#H\\d+\\t#ts\\t#tag(\\t|$)");

    /** Số dòng đầu đọc để chấm điểm. Đủ để vượt qua vài dòng nối tiếp đầu file. */
    private static final int SCAN_LINES = 60;

    public DetectedLogType detect(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return DetectedLogType.UNKNOWN;
        }

        int ios = 0;
        int android = 0;
        boolean sawJsonStart = false;
        boolean sawCallId = false;
        boolean sawEvents = false;

        int scanned = 0;
        for (String raw : lines) {
            if (scanned >= SCAN_LINES) {
                break;
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            scanned++;
            String line = raw.strip();

            if (ENDCALL_HEADER.matcher(line).find()) {
                return DetectedLogType.ENDCALL_LOG;
            }
            if (WEBRTC_ANDROID_LINE.matcher(raw).find()) {
                android++;
            } else if (WEBRTC_IOS_LINE.matcher(raw).find()) {
                ios++;
            }

            if (scanned == 1 && line.startsWith("{")) {
                sawJsonStart = true;
            }
            if (sawJsonStart) {
                if (line.contains("\"callId\"")) {
                    sawCallId = true;
                }
                if (line.contains("\"events\"")) {
                    sawEvents = true;
                }
                if (sawCallId && sawEvents) {
                    return DetectedLogType.SIGNALING_JSON;
                }
            }
        }

        if (android > 0 || ios > 0) {
            return android >= ios ? DetectedLogType.WEBRTC_ANDROID : DetectedLogType.WEBRTC_IOS;
        }
        return DetectedLogType.UNKNOWN;
    }
}
