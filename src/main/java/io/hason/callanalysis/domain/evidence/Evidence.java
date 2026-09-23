package io.hason.callanalysis.domain.evidence;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.LogSource;

/**
 * Một mảnh bằng chứng, có ID riêng để kết luận trích dẫn được.
 *
 * MVP mục 8.2: "mọi evidence trace được về dòng log gốc". Mẫu report mục 4.5 in ra
 * dạng [EV05][callee_endcall.log 10:00:41.000], nên Evidence phải giữ cả nguồn lẫn
 * vị trí dòng.
 */
public record Evidence(String id, LogSource source, String timeLabel,
                       String description, CanonicalEvent event) {

    /** Dạng trích dẫn trong report: [EV05][callee_endcall.log:142]. */
    public String citation() {
        return "[" + id + "][" + event.sourceRef().citation() + "]";
    }
}
