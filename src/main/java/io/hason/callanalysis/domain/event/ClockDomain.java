package io.hason.callanalysis.domain.event;

public enum ClockDomain {
    SERVER,
    CLIENT_CALLER,
    CLIENT_CALLEE,
    /** WebRTC log: mốc tương đối từ lúc log khởi tạo, không quy về giờ tuyệt đối được. */
    LOG_RELATIVE
}
