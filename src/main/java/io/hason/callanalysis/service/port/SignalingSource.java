package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.signaling.SignalingFetch;

/**
 * Cong ra phia nguon signaling. Tang domain chi biet interface nay, khong biet
 * Elasticsearch ton tai — nho vay parser, timeline va metrics test duoc ma khong
 * can dung container nao.
 */
public interface SignalingSource {

    SignalingFetch fetchByCallId(String callId);
}
