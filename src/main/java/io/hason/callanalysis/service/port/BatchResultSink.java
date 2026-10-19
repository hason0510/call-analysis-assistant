package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.batch.BatchResult;

import java.time.Instant;
import java.util.List;

/**
 * Cổng ra: ghi kết quả một lượt phân tích hàng loạt (MVP 7.1 T10) để dashboard T11 xem được phân bố trên toàn kho
 * ("Kibana dashboard trên kết quả phân tích").
 *
 * Ghi THAY THẾ lượt trước, không cộng dồn: index giữ ảnh chụp mới nhất, mỗi cuộc một bản ghi — chạy lại hai lần không
 * làm số cuộc gấp đôi. Lỗi ném ra ngoài (UncheckedIOException); service bắt và báo, báo cáo file vẫn được ghi.
 */
public interface BatchResultSink {

    /** @param results Call-ID đã là mã giả */
    void replaceAll(Instant ranAt, List<BatchResult> results);

    BatchResultSink NONE = (ranAt, results) -> { };
}
