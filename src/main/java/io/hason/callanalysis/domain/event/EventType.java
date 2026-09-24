package io.hason.callanalysis.domain.event;

/** Các loại event quan sát được trong data mẫu ai20k_sample. */
public enum EventType {
    /** signaling cmd; end call log, tag send_cmd / recv_cmd. */
    SIGNALING_COMMAND,
    /**
     * Khai báo sẵn nhưng hiện CHƯA parser nào sinh ra: IceConnectionState của WebRTC được
     * xếp ICE_EVENT, còn cột status của bản ghi log_detail giữ nguyên trong attributes.
     */
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
    /** end call log, tag info: metadata cuộc gọi. */
    CALL_METADATA,
    /** end call log, tag qos: GRPC/SOCKET. */
    QOS,
    /** end call log, tag signal: hành vi người dùng và tín hiệu từ đối phương. */
    USER_ACTION,
    LOG_MESSAGE
}
