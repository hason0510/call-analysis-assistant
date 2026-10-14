package io.hason.callanalysis.domain.confidence;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;

import java.util.List;

/**
 * Dữ liệu thiếu của cuộc gọi — cột "đủ file / thiếu một phần file" của bảng MVP mục 7.2.
 *
 * @param missingSources       loại nguồn log không có ở leg nào
 * @param legsMissingClientLog leg không có log client nào (end call lẫn WebRTC)
 */
public record DataCoverage(boolean signalingTruncated, List<LogSource> missingSources, List<Leg> legsMissingClientLog) {

    public DataCoverage {
        missingSources = missingSources == null ? List.of() : List.copyOf(missingSources);
        legsMissingClientLog = legsMissingClientLog == null ? List.of() : List.copyOf(legsMissingClientLog);
    }

    public static DataCoverage complete() {
        return new DataCoverage(false, List.of(), List.of());
    }
}
