package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.Leg;

import java.math.BigDecimal;

/**
 * Căn cứ đo được của cờ chất lượng trên MỘT leg, đếm trên chuỗi mẫu stats.
 *
 * Tính bằng đúng các ngưỡng của điều kiện bật cờ trong SignalExtractor, nên câu căn cứ trong
 * report không thể lệch với lý do cờ thật sự bật. Giữ cả số liệu PHẢN BÁC (MOS thấp nhất, số
 * mẫu app tự báo kém) để người đọc tự cân — 271D1FAF: 18/80 mẫu mất gói > 5 % nhưng MOS thấp
 * nhất vẫn 4,10 và app không bật hasMediaPoor lần nào.
 *
 * @param samples           số mẫu stats đã xét (không đếm bản ghi summary, vì nó trùng mẫu cuối)
 * @param lossOverThreshold số mẫu có audio.packetLostPercent vượt ngưỡng
 * @param maxLoss           audio.packetLostPercent cao nhất; null nếu không mẫu nào có trường này
 * @param mosBelowThreshold số mẫu có MOS (> 0) dưới ngưỡng
 * @param minMos            MOS thấp nhất trong các mẫu đo được (> 0); null nếu không có
 * @param mediaPoor         số mẫu app tự bật transport.hasMediaPoor = 1
 * @param packetsReceived   bộ đếm cộng dồn audio.packetsReceived cao nhất của leg
 * @param avgRttMs          trung bình transport.currentRttMs trên các mẫu ĐÃ có âm thanh; null nếu không có
 * @param avgJitterMs       trung bình audio.jitter (đổi giây → mili) trên các mẫu đã có âm thanh; null nếu không có
 */
public record LegQuality(
        Leg leg,
        int samples,
        int lossOverThreshold,
        BigDecimal maxLoss,
        int mosBelowThreshold,
        BigDecimal minMos,
        int mediaPoor,
        long packetsReceived,
        BigDecimal avgRttMs,
        BigDecimal avgJitterMs
) {

    /*
     * Ngưỡng độ trễ / jitter: bộ phân loại "Poor" cho luồng âm thanh của Microsoft Teams Call Quality Dashboard
     * (learn.microsoft.com/microsoftteams/stream-classification-in-call-quality-dashboard, cập nhật 2026-06):
     * luồng > 500 gói, RTT TRUNG BÌNH > 500 ms hoặc jitter TRUNG BÌNH > 30 ms. Chọn nguồn này vì nó phân loại đúng
     * loại số liệu end call log có (RTT khứ hồi, jitter của luồng nhận) và dùng trung bình — không bật cờ vì một mẫu.
     * Đối chiếu: ITU-T G.114 (05/2003, còn hiệu lực) — trễ MỘT CHIỀU > 400 ms không chấp nhận được; RTT 500 ms ứng
     * với ~250 ms một chiều nếu đối xứng, chưa tính codec / jitter buffer. Ngưỡng cũ 300 ms / 50 ms là phỏng đoán.
     */
    static final BigDecimal RTT_POOR_MS = new BigDecimal("500");
    static final BigDecimal JITTER_POOR_MS = new BigDecimal("30");
    static final long MIN_PACKETS = 500;

    /** Chỉ dùng cho test viết sẵn trước khi có số liệu độ trễ. */
    public LegQuality(Leg leg, int samples, int lossOverThreshold, BigDecimal maxLoss, int mosBelowThreshold,
                      BigDecimal minMos, int mediaPoor) {
        this(leg, samples, lossOverThreshold, maxLoss, mosBelowThreshold, minMos, mediaPoor, 0, null, null);
    }

    /** Suy giảm theo mất gói / MOS / app tự báo — điều kiện của NETWORK_PACKET_LOSS. */
    public boolean degraded() {
        return lossOverThreshold > 0 || mosBelowThreshold > 0 || mediaPoor > 0;
    }

    /**
     * Độ trễ hoặc jitter TRUNG BÌNH vượt ngưỡng Teams CQD, trên luồng đủ dài (> 500 gói, như Teams). Luồng ngắn
     * hơn thì Teams không phân loại ("Unclassified"), mình cũng không bật cờ.
     */
    public boolean delayDegraded() {
        return packetsReceived > MIN_PACKETS
                && (avgRttMs != null && avgRttMs.compareTo(RTT_POOR_MS) > 0
                    || avgJitterMs != null && avgJitterMs.compareTo(JITTER_POOR_MS) > 0);
    }
}
