package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Import thật vào Elasticsearch chạy trong Docker — phần mà unit test không với tới được:
 * mapping `dynamic: strict` có nhận document không, `_id` tất định có thật sự chặn bản trùng
 * không, và chia lô có làm rơi document nào không.
 *
 * Không có Docker thì bỏ qua (disabledWithoutDocker), để `mvn test` vẫn chạy được không cần
 * Docker như Sprint 1 cam kết.
 */
@Testcontainers(disabledWithoutDocker = true)
class SignalingImporterIntegrationTest {

    /** Cùng bản với docker-compose.yml: client 8.18.8 do Boot 3.5.16 quản lý. */
    @Container
    private static final ElasticsearchContainer ES =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static RestClient restClient;
    private static ElasticsearchClient client;

    private static final String CALL_A = "AAAAAAAA-0000-0000-0000-000000000001";
    private static final String CALL_B = "BBBBBBBB-0000-0000-0000-000000000002";

    @TempDir
    Path dataRoot;

    @BeforeAll
    static void connect() {
        restClient = RestClient.builder(HttpHost.create(ES.getHttpHostAddress())).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    @AfterAll
    static void disconnect() throws IOException {
        restClient.close();
    }

    @BeforeEach
    void freshIndex() throws IOException {
        SignalingIndex index = new SignalingIndex(client);
        index.delete();
        index.createIfAbsent();
    }

    @Test
    @DisplayName("nạp hai lần cùng thư mục -> số document không đổi, không sinh bản trùng")
    void importingTwiceIsIdempotent() throws IOException {
        writeSampleData();
        // lô 2 document cho 5 document -> 3 lô, lô cuối lẻ: ép đúng đường chia lô
        SignalingImporter importer = new SignalingImporter(client, new ObjectMapper(), 2);

        SignalingImporter.ImportReport first = importer.importFrom(dataRoot);
        SignalingImporter.ImportReport second = importer.importFrom(dataRoot);

        assertThat(first.problems()).isEmpty();
        assertThat(first.filesRead()).isEqualTo(2);
        assertThat(first.documentsIndexed()).isEqualTo(5);
        assertThat(second.documentsIndexed()).isEqualTo(5);
        assertThat(countDocuments()).isEqualTo(5);
    }

    @Test
    @DisplayName("đọc lại qua adapter -> đúng thứ tự gốc và giữ metadata cắt bớt")
    void fetchReturnsEventsInOrderWithTruncationMetadata() throws IOException {
        writeSampleData();
        new SignalingImporter(client, new ObjectMapper(), 2).importFrom(dataRoot);

        SignalingFetch fetch = new ElasticsearchSignalingSource(client).fetchByCallId(CALL_A);

        assertThat(fetch.records()).extracting(RawSignalingRecord::cmd)
                .containsExactly("INIT_CALL", "INVITE", "BYE");
        assertThat(fetch.records()).extracting(RawSignalingRecord::ordinal)
                .containsExactly(0, 1, 2);
        assertThat(fetch.truncated()).isTrue();
        assertThat(fetch.totalMatching()).isEqualTo(4);
    }

    @Test
    @DisplayName("một file JSON hỏng -> ghi vào problems, các file khác vẫn được nạp")
    void brokenFileDoesNotStopTheImport() throws IOException {
        writeSampleData();
        write("broken/CCCCCCCC/signaling.json", "{ \"callId\": ");

        SignalingImporter.ImportReport report =
                new SignalingImporter(client, new ObjectMapper(), 2).importFrom(dataRoot);

        assertThat(report.filesRead()).isEqualTo(3);
        assertThat(report.documentsIndexed()).isEqualTo(5);
        assertThat(report.problems()).singleElement().asString().contains("broken");
    }

    @Test
    @DisplayName("index tạo trước khi có trường noSessionsFound -> lần nạp sau bổ sung mapping, cờ đọc lại được")
    void existingIndexGetsNewFieldsInMapping() throws IOException {
        // Mapping strict của bản trước 2026-10-19: chưa có noSessionsFound. Không bổ sung thì mọi document bị từ chối.
        SignalingIndex index = new SignalingIndex(client);
        index.delete();
        client.indices().create(c -> c.index(SignalingIndex.NAME).withJson(new java.io.StringReader("""
                {"mappings": {"dynamic": "strict", "properties": {
                  "callId": {"type": "keyword"}, "ordinal": {"type": "integer"},
                  "@timestamp": {"type": "date", "format": "strict_date_optional_time_nanos"},
                  "service": {"type": "keyword"}, "level": {"type": "keyword"}, "cmd": {"type": "keyword"},
                  "csid": {"type": "keyword"}, "requestId": {"type": "keyword"}, "appUserId": {"type": "keyword"},
                  "callSessionId": {"type": "keyword"}, "isp": {"type": "keyword"}, "asn": {"type": "keyword"},
                  "countryCode": {"type": "keyword"}, "latencyMs": {"type": "long"},
                  "sourceTruncated": {"type": "boolean"}, "sourceReturned": {"type": "integer"},
                  "sourceTotalMatching": {"type": "integer"}}}}""")));
        write("new/CCCCCCCC/signaling.json", """
                {"call_id": "CCCCCCCC-0000-0000-0000-000000000003", "total_matching": 2, "returned": 2,
                 "logs": [
                   {"@timestamp": "2026-10-13T07:50:18.216Z", "grpcMethod": "INVITE", "log": {"level": "WARN"},
                    "message": "[OUT][NATs][INVITE][CALLEE] No sessions found for Call-ID: x, User-ID CABC"},
                   {"@timestamp": "2026-10-13T07:50:21.448Z", "grpcMethod": "TRYING", "log": {"level": "INFO"},
                    "message": "[IN][NATs][Partition 6][TRYING][CALLEE] Current dialog state"}]}""");

        index.createIfAbsent();
        SignalingImporter.ImportReport report = new SignalingImporter(client, new ObjectMapper(), 2).importFrom(dataRoot);
        SignalingFetch fetch = new ElasticsearchSignalingSource(client)
                .fetchByCallId("CCCCCCCC-0000-0000-0000-000000000003");

        assertThat(report.problems()).isEmpty();
        assertThat(fetch.records()).extracting(RawSignalingRecord::noSessionsFound).containsExactly(true, false);
    }

    private long countDocuments() throws IOException {
        return client.count(c -> c.index(SignalingIndex.NAME)).count();
    }

    private void writeSampleData() throws IOException {
        write("fail/AAAAAAAA/signaling.json", """
                {
                  "callId": "%s",
                  "total_matching": 4,
                  "returned": 3,
                  "truncated": true,
                  "events": [
                    {"@timestamp": "2026-09-21T08:44:28.953Z", "level": "INFO", "cmd": "INIT_CALL", "appUserId": "U1"},
                    {"@timestamp": "2026-09-21T08:44:29.100Z", "level": "INFO", "cmd": "INVITE", "appUserId": "U1"},
                    {"@timestamp": "2026-09-21T08:44:40.000Z", "level": "INFO", "cmd": "BYE", "appUserId": "U2"}
                  ]
                }
                """.formatted(CALL_A));
        write("success/BBBBBBBB/signaling.json", """
                {
                  "callId": "%s",
                  "total_matching": 2,
                  "returned": 2,
                  "truncated": false,
                  "events": [
                    {"@timestamp": "2026-09-21T09:00:00.000Z", "level": "WARN", "cmd": "INIT_CALL", "appUserId": "U3", "latencyMs": 5},
                    {"@timestamp": "2026-09-21T09:00:01.000Z", "level": "INFO", "cmd": "INVITE", "appUserId": "U3"}
                  ]
                }
                """.formatted(CALL_B));
    }

    private void write(String relative, String content) throws IOException {
        Path file = dataRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
