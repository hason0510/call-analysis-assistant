package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.LogSource;

/** Bo chi so Core theo MVP muc 4.3. */
public enum MetricKey {

    SETUP_TIME("Thoi gian thiet lap", LogSource.SIGNALING),
    TIME_TO_CALLEE("Thoi gian voi toi callee", LogSource.SIGNALING),
    INVITE_RETRANSMISSIONS("So lan gui lai INVITE", LogSource.SIGNALING),
    NO_SESSIONS_FOUND("So lan No sessions found", LogSource.SIGNALING),
    RINGING_TIME("Thoi gian do chuong", LogSource.SIGNALING),
    CONNECTED_DURATION("Thoi luong ket noi", LogSource.SIGNALING),
    TERMINATED_BY("Ben ket thuc", LogSource.SIGNALING),
    BYE_RETRANSMISSIONS("So lan gui lai BYE", LogSource.SIGNALING),

    MOS("MOS", LogSource.ENDCALL),
    PACKET_LOSS("Packet loss", LogSource.ENDCALL),
    RTT("RTT", LogSource.ENDCALL),
    JITTER("Jitter", LogSource.ENDCALL),

    ICE_FINAL_STATE("Trang thai ICE cuoi cung", LogSource.WEBRTC);

    private final String displayName;
    private final LogSource source;

    MetricKey(String displayName, LogSource source) {
        this.displayName = displayName;
        this.source = source;
    }

    public String displayName() {
        return displayName;
    }

    public LogSource source() {
        return source;
    }

    /** Chi so tinh rieng cho tung ben, phai kem leg khi hien thi. */
    public boolean isPerLeg() {
        return this == MOS || this == PACKET_LOSS || this == RTT
                || this == JITTER || this == ICE_FINAL_STATE;
    }
}
