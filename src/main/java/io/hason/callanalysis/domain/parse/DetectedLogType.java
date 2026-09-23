package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.LogSource;

/**
 * Loại file nhận diện được TỪ NỘI DUNG, không theo tên file.
 *
 * Data mẫu có file tên `calleer_webrtc.log` (thừa chữ 'e') — đối chiếu format của nó
 * (Android) với cột platform trong bản ghi #H1 của end call log cho thấy đó là log
 * của CALLER bị gõ sai tên.
 */
public enum DetectedLogType {

    SIGNALING_JSON(LogSource.SIGNALING),
    ENDCALL_LOG(LogSource.ENDCALL),
    /** Format 1 — tiền tố [giây:mili][thread] đứng đầu dòng. */
    WEBRTC_IOS(LogSource.WEBRTC),
    /** Format 2 — ten file .cc dung truoc khoi [giay:mili][thread]. */
    WEBRTC_ANDROID(LogSource.WEBRTC),
    UNKNOWN(null);

    private final LogSource source;

    DetectedLogType(LogSource source) {
        this.source = source;
    }

    public LogSource source() {
        return source;
    }

    public boolean isWebRtc() {
        return this == WEBRTC_IOS || this == WEBRTC_ANDROID;
    }
}
