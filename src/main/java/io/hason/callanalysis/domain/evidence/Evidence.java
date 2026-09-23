package io.hason.callanalysis.domain.evidence;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.LogSource;

/**
 * Mot manh bang chung, co ID rieng de ket luan trich dan duoc.
 *
 * MVP muc 8.2: "moi evidence trace duoc ve dong log goc". Mau report muc 4.5 in ra
 * dang [EV05][callee_endcall.log 10:00:41.000], nen Evidence phai giu ca nguon lan
 * vi tri dong.
 */
public record Evidence(String id, LogSource source, String timeLabel,
                       String description, CanonicalEvent event) {

    /** Dang trich dan trong report: [EV05][callee_endcall.log:142]. */
    public String citation() {
        return "[" + id + "][" + event.sourceRef().citation() + "]";
    }
}
