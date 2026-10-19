package io.hason.callanalysis.domain.batch;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.timeline.CallTimeline;

/**
 * Nguồn dữ liệu thật sự dùng được cho một cuộc gọi trong lượt phân tích hàng loạt (MVP 7.1 T10).
 *
 * Elasticsearch chỉ chứa signaling (MVP 1.2: log máy do người dùng đính kèm). Signaling cho biết cuộc gọi có thiết lập
 * được không, nhưng không cho biết âm thanh có tới không — cuộc {@code 2D9057AA} signaling hoàn hảo mà 0 byte âm thanh
 * (bẫy #12). Nên hai nhóm phải thống kê RIÊNG:
 * - {@link #SIGNALING_ONLY}: UNKNOWN nghĩa là "thiếu log máy", không phải "cuộc gọi có vấn đề"; loại lỗi là triệu chứng
 *   nhìn từ server (lỗi TURN hiện ra thành SIGNALING_FAILURE vì nguyên nhân chỉ có trong WebRTC log).
 * - {@link #WITH_CLIENT_LOGS}: như report của Web UI.
 *
 * Xét theo timeline chứ không theo "có tìm thấy thư mục": thư mục có thể chỉ chứa file bị File Validator loại.
 */
public enum SourceGroup {

    SIGNALING_ONLY("Chỉ signaling"),
    WITH_CLIENT_LOGS("Signaling + log máy");

    private final String label;

    SourceGroup(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static SourceGroup of(CallTimeline timeline) {
        return timeline.hasSource(LogSource.ENDCALL) || timeline.hasSource(LogSource.WEBRTC)
                ? WITH_CLIENT_LOGS : SIGNALING_ONLY;
    }
}
