package io.hason.callanalysis.domain.event;

/** Các loại event quan sát được trong data mẫu ai20k_sample. */
public enum EventType {
    /** signaling cmd; end call log #H3 send_cmd/recv_cmd. */
    SIGNALING_COMMAND,
    /** end call log #H2 cột status; webrtc IceConnectionState. */
    STATE_CHANGE,
    ICE_EVENT,
    TURN_EVENT,
    PEER_CONNECTION_EVENT,
    /** end call log #H6: cặp candidate ICE. */
    ICE_CANDIDATE,
    /** end call log #H7: periodic stats. */
    MEDIA_STATS,
    /** end call log #H9: call summary. */
    CALL_SUMMARY,
    /** end call log #H1: metadata cuộc gọi. */
    CALL_METADATA,
    /** end call log #H4: qos GRPC/SOCKET. */
    QOS,
    /** end call log #H5: hành vi người dùng. */
    USER_ACTION,
    LOG_MESSAGE
}
