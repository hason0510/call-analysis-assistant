package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.observability.RequestRecord;

/**
 * Cổng ra: ghi bản ghi request (MVP 7.1 T9). Adapter Elasticsearch chỉ bật ở profile web
 * ({@code call-analysis.request-log.enabled}); không có adapter thì không ghi gì.
 *
 * Không được làm hỏng hay làm chậm request: adapter tự nuốt lỗi và ghi bất đồng bộ.
 */
public interface RequestRecordSink {

    void record(RequestRecord record);

    RequestRecordSink NONE = record -> { };
}
