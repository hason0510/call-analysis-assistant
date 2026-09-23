package io.hason.callanalysis.domain.event;

/** Signaling chỉ sinh INFO và WARN; ERROR chưa từng xuất hiện trong data mẫu. */
public enum Severity {
    INFO,
    WARN,
    ERROR
}
