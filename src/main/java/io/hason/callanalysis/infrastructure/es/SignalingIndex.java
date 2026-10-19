package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

/**
 * Tạo index signaling với mapping tĩnh (dynamic: strict) nếu chưa tồn tại; đã tồn tại thì bổ sung trường mới
 * vào mapping. Không bổ sung thì index tạo trước khi có trường mới (ví dụ {@code noSessionsFound}, 2026-10-19)
 * từ chối MỌI document ở lần nạp sau, vì mapping strict không nhận trường lạ.
 */
@Component
public class SignalingIndex {

    public static final String NAME = "signaling-events";

    private static final Logger log = LoggerFactory.getLogger(SignalingIndex.class);
    private static final String MAPPING = "es/signaling-mapping.json";

    private final ElasticsearchClient client;

    public SignalingIndex(ElasticsearchClient client) {
        this.client = client;
    }

    public void createIfAbsent() throws IOException {
        if (client.indices().exists(e -> e.index(NAME)).value()) {
            updateMapping();
            log.info("Index '{}' đã tồn tại, đã bổ sung trường mới (nếu có) vào mapping.", NAME);
            return;
        }
        try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
            client.indices().create(c -> c.index(NAME).withJson(mapping));
        }
        log.info("Đã tạo index '{}' từ {}.", NAME, MAPPING);
    }

    /**
     * Elasticsearch cho THÊM trường vào mapping của index đang có; trường cũ gửi lại đúng kiểu thì không đổi gì.
     * Đổi kiểu một trường cũ thì Elasticsearch từ chối — khi đó phải nạp lại với --recreate-index.
     */
    private void updateMapping() throws IOException {
        String mappings;
        try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
            mappings = new ObjectMapper().readTree(mapping).path("mappings").toString();
        }
        client.indices().putMapping(p -> p.index(NAME).withJson(new StringReader(mappings)));
    }

    public void delete() throws IOException {
        if (client.indices().exists(e -> e.index(NAME)).value()) {
            client.indices().delete(d -> d.index(NAME));
            log.info("Đã xoá index '{}'.", NAME);
        }
    }
}
