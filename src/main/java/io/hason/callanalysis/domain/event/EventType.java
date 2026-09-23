package io.hason.callanalysis.domain.event;

/** Cac loai event quan sat duoc trong data mau ai20k_sample. */
public enum EventType {
    /** signaling cmd; end call log #H3 send_cmd/recv_cmd. */
    SIGNALING_COMMAND,
    /** end call log #H2 cot status; webrtc IceConnectionState. */
    STATE_CHANGE,
    ICE_EVENT,
    TURN_EVENT,
    PEER_CONNECTION_EVENT,
    /** end call log #H6: cap candidate ICE. */
    ICE_CANDIDATE,
    /** end call log #H7: periodic stats. */
    MEDIA_STATS,
    /** end call log #H9: call summary. */
    CALL_SUMMARY,
    /** end call log #H1: metadata cuoc goi. */
    CALL_METADATA,
    /** end call log #H4: qos GRPC/SOCKET. */
    QOS,
    /** end call log #H5: hanh vi nguoi dung. */
    USER_ACTION,
    LOG_MESSAGE
}
