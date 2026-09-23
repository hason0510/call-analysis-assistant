package io.hason.callanalysis.domain.taxonomy;

/** Sáu nhóm nguyên nhân theo MVP mục 4.2. */
public enum IssueCategory {
    NETWORK_PACKET_LOSS,
    NETWORK_DELAY_JITTER,
    ICE_FAILURE,
    TURN_FAILURE,
    SIGNALING_FAILURE,
    UNKNOWN
}
