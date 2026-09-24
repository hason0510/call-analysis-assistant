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
    /** end call log, tag local_candidate / remote_candidate: candidate ICE lúc kết thúc. */
    ICE_CANDIDATE,
    /** end call log, tag stats: periodic stats, 1 giây một dòng. */
    MEDIA_STATS,
    /** end call log, tag endcall: call summary. */
    CALL_SUMMARY,
    /** end call log #H1: metadata cuộc gọi. */
    CALL_METADATA,
    /** end call log, tag qos: GRPC/SOCKET. */
    QOS,
    /** end call log, tag signal: hành vi người dùng và tín hiệu từ đối phương. */
    USER_ACTION,
    LOG_MESSAGE
}
