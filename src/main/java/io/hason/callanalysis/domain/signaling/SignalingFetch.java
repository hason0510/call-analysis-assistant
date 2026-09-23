package io.hason.callanalysis.domain.signaling;

import java.util.List;

/**
 * Ket qua truy van signaling cua mot cuoc goi, kem metadata cua ban export goc.
 *
 * {@code truncated} bat buoc phai duoc mang theo: cuoc goi DE7DD314 trong data mau
 * co truncated=true (returned 200 / total 201), va MVP muc 3.3 yeu cau nhung thieu hut
 * du lieu nhu vay phai duoc neu ro o muc "Gioi han du lieu" cua report.
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
