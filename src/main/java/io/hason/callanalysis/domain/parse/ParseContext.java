package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.Leg;

/**
 * Thong tin ma parser khong tu suy ra duoc tu noi dung file.
 *
 * WebRTC log la log goc cua thu vien libwebrtc — no KHONG chua Call-ID cua ung dung,
 * nen callId bat buoc phai duoc truyen vao tu ngoai.
 */
public record ParseContext(String fileName, String callId, Leg leg) {

    public static ParseContext of(String fileName, String callId, Leg leg) {
        return new ParseContext(fileName, callId, leg == null ? Leg.UNKNOWN : leg);
    }

    public ClockDomain clientClockDomain() {
        return switch (leg) {
            case CALLER -> ClockDomain.CLIENT_CALLER;
            case CALLEE -> ClockDomain.CLIENT_CALLEE;
            case SERVER, UNKNOWN -> ClockDomain.LOG_RELATIVE;
        };
    }
}
