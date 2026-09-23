package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.Leg;

/**
 * Thông tin mà parser không tự suy ra được từ nội dung file.
 *
 * WebRTC log là log gốc của thư viện libwebrtc — nó KHÔNG chứa Call-ID của ứng dụng,
 * nên callId bắt buộc phải được truyền vào từ ngoài.
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
