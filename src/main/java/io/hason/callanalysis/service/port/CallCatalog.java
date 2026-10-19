package io.hason.callanalysis.service.port;

import java.util.List;

/**
 * Cổng ra: danh sách Call-ID có trong nguồn signaling — đầu vào của phân tích hàng loạt (MVP 7.1 T10: "các Call-ID
 * trong ES local"). Tách khỏi {@link SignalingSource} để cổng đó vẫn là interface một hàm (test dựng bằng lambda).
 */
public interface CallCatalog {

    /** Mọi Call-ID khác nhau, sắp xếp tăng dần (thứ tự tất định cho báo cáo). */
    List<String> callIds();
}
