package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.signaling.SignalingFetch;

/**
 * Cổng ra phía nguồn signaling. Tầng domain chỉ biết interface này, không biết
 * Elasticsearch tồn tại — nhờ vậy parser, timeline và metrics test được mà không
 * cần dựng container nào.
 */
public interface SignalingSource {

    SignalingFetch fetchByCallId(String callId);
}
