package io.hason.callanalysis.domain.signaling;

import java.util.List;

/**
 * Kết quả truy vấn signaling của một cuộc gọi, kèm metadata của bản export gốc.
 *
 * {@code truncated} bắt buộc phải được mang theo: cuộc gọi DE7DD314 trong data mẫu
 * có truncated=true (returned 200 / total 201), và MVP mục 3.3 yêu cầu những thiếu hụt
 * dữ liệu như vậy phải được nêu rõ ở mục "Giới hạn dữ liệu" của report.
 */
public record SignalingFetch(
        String callId,
        List<RawSignalingRecord> records,
        boolean truncated,
        int returned,
        int totalMatching
) {

    public SignalingFetch {
        records = records == null ? List.of() : List.copyOf(records);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public int missingCount() {
        return Math.max(0, totalMatching - returned);
    }
}
