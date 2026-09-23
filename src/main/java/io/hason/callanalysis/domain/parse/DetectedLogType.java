package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.LogSource;

/**
 * Loai file nhan dien duoc TU NOI DUNG, khong theo ten file.
 *
 * Data mau co file ten `calleer_webrtc.log` (thua chu 'e') — doi chieu format cua no
 * (Android) voi cot platform trong ban ghi #H1 cua end call log cho thay do la log
 * cua CALLER bi go sai ten.
 */
public enum DetectedLogType {

    SIGNALING_JSON(LogSource.SIGNALING),
    ENDCALL_LOG(LogSource.ENDCALL),
    /** Format 1 — tien to [giay:mili][thread] dung dau dong. */
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
