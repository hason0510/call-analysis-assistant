package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Tao index signaling voi mapping tinh (dynamic: strict) neu chua ton tai. */
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
            log.info("Index '{}' da ton tai, bo qua buoc tao.", NAME);
            return;
        }
        try (InputStream mapping = new ClassPathResource(MAPPING).getInputStream()) {
            client.indices().create(c -> c.index(NAME).withJson(mapping));
        }
        log.info("Da tao index '{}' tu {}.", NAME, MAPPING);
    }

    public void delete() throws IOException {
        if (client.indices().exists(e -> e.index(NAME)).value()) {
            client.indices().delete(d -> d.index(NAME));
            log.info("Da xoa index '{}'.", NAME);
        }
    }
}
