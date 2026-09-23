package io.hason.callanalysis.domain.parse;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Nhan dien loai file theo NOI DUNG, khong theo ten file.
 *
 * Yeu cau nay khong phai gia dinh: data mau co file `calleer_webrtc.log` (thua chu 'e'),
 * va MVP muc 6.1 T3 co han test case F02 "Ten file khong khop noi dung".
 */
public class FileTypeDetector {

    /** Format 1 (iOS): [6652:953][260115] (RTCLogging.mm:34): ... */
    static final Pattern WEBRTC_IOS_LINE =
            Pattern.compile("^\\[\\d+:\\d{3}]\\[\\d+]\\s");

    /** Format 2 (Android): peer_connection.cc: [6652:957][12108] (line 659): ... */
    static final Pattern WEBRTC_ANDROID_LINE =
            Pattern.compile("^[\\w.]+\\.(?:cc|mm|h):\\s*\\[\\d+:\\d{3}]\\[\\d+]\\s");

    /** Dong dau tien cua end call log luon la dac ta format cua ban ghi loai 1. */
    private static final Pattern ENDCALL_HEADER =
            Pattern.compile("^#H\\d+\\t#ts\\t#tag(\\t|$)");

    /** So dong dau doc de cham diem. Du de vuot qua vai dong noi tiep dau file. */
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
