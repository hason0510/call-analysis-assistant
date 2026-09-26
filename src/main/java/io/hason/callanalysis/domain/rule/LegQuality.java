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
 */
public record LegQuality(
        Leg leg,
        int samples,
        int lossOverThreshold,
        BigDecimal maxLoss,
        int mosBelowThreshold,
        BigDecimal minMos,
        int mediaPoor
) {

    public boolean degraded() {
        return lossOverThreshold > 0 || mosBelowThreshold > 0 || mediaPoor > 0;
    }
}
