package io.hason.callanalysis.domain.metrics;

import io.hason.callanalysis.domain.event.LogSource;

/** Bộ chỉ số Core theo MVP mục 4.3. */
public enum MetricKey {

    SETUP_TIME("Thời gian thiết lập", LogSource.SIGNALING),
    TIME_TO_CALLEE("Thời gian với tới callee", LogSource.SIGNALING),
    INVITE_RETRANSMISSIONS("Số lần gửi lại INVITE", LogSource.SIGNALING),
    NO_SESSIONS_FOUND("Số lần No sessions found", LogSource.SIGNALING),
    RINGING_TIME("Thời gian đổ chuông", LogSource.SIGNALING),
    CONNECTED_DURATION("Thời lượng kết nối", LogSource.SIGNALING),
    TERMINATED_BY("Bên kết thúc", LogSource.SIGNALING),
    BYE_RETRANSMISSIONS("Số lần gửi lại BYE", LogSource.SIGNALING),

    MOS("MOS", LogSource.ENDCALL),
    PACKET_LOSS("Packet loss", LogSource.ENDCALL),
    RTT("RTT", LogSource.ENDCALL),
    JITTER("Jitter", LogSource.ENDCALL),

    ICE_FINAL_STATE("Trạng thái ICE đạt được", LogSource.WEBRTC),

    // ---- Chỉ số "Nếu kịp" (T11), MVP mục 4.3 ----

    /** PROXY: khoảng trống lớn nhất giữa các PAIR_PING — phải ghi rõ là proxy trong report. */
    MAX_PAIR_PING_GAP("Khoảng trống PAIR_PING lớn nhất", LogSource.SIGNALING, true),
    INTERNAL_API_LATENCY("Latency API nội bộ lúc INIT_CALL", LogSource.SIGNALING),
    WARN_COUNT_BY_SERVICE("Số WARN theo service", LogSource.SIGNALING),
    ERROR_COUNT_BY_SERVICE("Số ERROR theo service", LogSource.SIGNALING),
    NETWORK_CONTEXT("ISP / ASN / quốc gia", LogSource.SIGNALING);

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
     * Chỉ số PROXY: không đo trực tiếp thứ cần biết, chỉ là dấu hiệu gián tiếp.
     * MVP mục 4.3 yêu cầu ghi rõ trong report để người đọc không hiểu nhầm.
     */
    public boolean isProxy() {
        return proxy;
    }

    /** Chỉ số tính riêng cho từng bên, phải kèm leg khi hiển thị. */
    public boolean isPerLeg() {
        return this == MOS || this == PACKET_LOSS || this == RTT
                || this == JITTER || this == ICE_FINAL_STATE
                || this == MAX_PAIR_PING_GAP || this == NETWORK_CONTEXT;
    }
}
