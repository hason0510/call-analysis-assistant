package io.hason.callanalysis.domain.event;

public enum ClockDomain {
    SERVER,
    CLIENT_CALLER,
    CLIENT_CALLEE,
    /** WebRTC log: moc tuong doi tu luc log khoi tao, khong quy ve gio tuyet doi duoc. */
    LOG_RELATIVE
}
