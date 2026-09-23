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

    ICE_FINAL_STATE("Trang thai ICE cuoi cung", LogSource.WEBRTC),

    // ---- Chi so "Neu kip" (T11), MVP muc 4.3 ----

    /** PROXY: khoang trong lon nhat giua cac PAIR_PING — phai ghi ro la proxy trong report. */
    MAX_PAIR_PING_GAP("Khoang trong PAIR_PING lon nhat", LogSource.SIGNALING, true),
    INTERNAL_API_LATENCY("Latency API noi bo luc INIT_CALL", LogSource.SIGNALING),
    WARN_COUNT_BY_SERVICE("So WARN theo service", LogSource.SIGNALING),
    ERROR_COUNT_BY_SERVICE("So ERROR theo service", LogSource.SIGNALING),
    NETWORK_CONTEXT("ISP / ASN / quoc gia", LogSource.SIGNALING);

    private final String displayName;
    private final LogSource source;
    private final boolean proxy;

    MetricKey(String displayName, LogSource source) {
        this(displayName, source, false);
    }

    MetricKey(String displayName, LogSource source, boolean proxy) {
        this.displayName = displayName;
        this.source = source;
        this.proxy = proxy;
    }

    public String displayName() {
        return displayName;
    }

    public LogSource source() {
        return source;
    }

    /**
     * Chi so PROXY: khong do truc tiep thu can biet, chi la dau hieu gian tiep.
     * MVP muc 4.3 yeu cau ghi ro trong report de nguoi doc khong hieu nham.
     */
    public boolean isProxy() {
        return proxy;
    }

    /** Chi so tinh rieng cho tung ben, phai kem leg khi hien thi. */
    public boolean isPerLeg() {
        return this == MOS || this == PACKET_LOSS || this == RTT
                || this == JITTER || this == ICE_FINAL_STATE
                || this == MAX_PAIR_PING_GAP || this == NETWORK_CONTEXT;
    }
}
