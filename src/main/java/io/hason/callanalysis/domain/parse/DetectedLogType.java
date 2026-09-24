package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.LogSource;

/**
 * Loại file nhận diện được TỪ NỘI DUNG, không theo tên file.
 *
 * Data mẫu có file tên `calleer_webrtc.log` (thừa chữ 'e') mà nội dung là log của CALLER,
 * và file tên `caller_webrtc.log` (9B556E56) mà nội dung là end call log. Loại file do
 * FileTypeDetector quyết định; leg của file do LegCorrelator quyết định.
 */
public enum DetectedLogType {

    SIGNALING_JSON(LogSource.SIGNALING),
    ENDCALL_LOG(LogSource.ENDCALL),
    /** Format 1 — tiền tố [giây:mili][thread] đứng đầu dòng. */
    WEBRTC_IOS(LogSource.WEBRTC),
    /** Format 2 — tên file .cc đứng trước khối [giây:mili][thread]. */
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
