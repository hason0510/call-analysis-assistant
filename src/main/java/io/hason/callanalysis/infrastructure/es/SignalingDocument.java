package io.hason.callanalysis.infrastructure.es;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Hinh dang document trong index Elasticsearch. Chi thuoc tang infrastructure. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SignalingDocument(
        @JsonProperty("callId") String callId,
        @JsonProperty("ordinal") int ordinal,
        @JsonProperty("@timestamp") String timestamp,
        @JsonProperty("service") String service,
        @JsonProperty("level") String level,
        @JsonProperty("cmd") String cmd,
        @JsonProperty("csid") String csid,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("appUserId") String appUserId,
        @JsonProperty("callSessionId") String callSessionId,
        @JsonProperty("isp") String isp,
        @JsonProperty("asn") String asn,
        @JsonProperty("countryCode") String countryCode,
        @JsonProperty("latencyMs") Integer latencyMs,
        @JsonProperty("sourceTruncated") boolean sourceTruncated,
        @JsonProperty("sourceReturned") int sourceReturned,
        @JsonProperty("sourceTotalMatching") int sourceTotalMatching
) {

    /**
     * _id tat dinh: import lai cung mot file khong sinh ban ghi trung.
     * MVP muc 5.1 T1 yeu cau "script import data mau lap lai duoc".
     */
    public String documentId() {
        return callId + ":" + ordinal;
    }
}
