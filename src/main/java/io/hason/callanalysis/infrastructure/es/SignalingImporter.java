package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Doc cac file signaling.json trong thu muc data mau va nap vao Elasticsearch local.
 *
 * Import la idempotent: _id duoc suy ra tat dinh tu (callId, ordinal), nen chay lai
 * nhieu lan khong sinh ban ghi trung — dung yeu cau "lap lai duoc" cua MVP muc 5.1 T1.
 */
@Component
public class SignalingImporter {

    private static final Logger log = LoggerFactory.getLogger(SignalingImporter.class);
    private static final String FILE_NAME = "signaling.json";

    private final ElasticsearchClient client;
    private final SignalingJsonReader reader;

    public SignalingImporter(ElasticsearchClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.reader = new SignalingJsonReader(objectMapper);
    }

    public record ImportReport(int filesRead, int documentsIndexed, List<String> problems) {}

    public ImportReport importFrom(Path root) throws IOException {
        List<Path> files = findSignalingFiles(root);
        if (files.isEmpty()) {
            return new ImportReport(0, 0, List.of("Khong tim thay file " + FILE_NAME + " nao duoi " + root));
        }

        List<String> problems = new ArrayList<>();
        List<SignalingDocument> batch = new ArrayList<>();

        for (Path file : files) {
            try {
                batch.addAll(reader.read(Files.readString(file, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                problems.add(file + " -> " + e.getMessage());
            }
        }

        int indexed = batch.isEmpty() ? 0 : bulkIndex(batch, problems);
        return new ImportReport(files.size(), indexed, List.copyOf(problems));
    }

    private List<Path> findSignalingFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(FILE_NAME))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private int bulkIndex(List<SignalingDocument> docs, List<String> problems) throws IOException {
        BulkRequest.Builder request = new BulkRequest.Builder().refresh(Refresh.True);
        for (SignalingDocument doc : docs) {
            request.operations(op -> op.index(i -> i
                    .index(SignalingIndex.NAME)
                    .id(doc.documentId())
                    .document(doc)));
        }

        BulkResponse response = client.bulk(request.build());
        if (response.errors()) {
            response.items().stream()
                    .filter(i -> i.error() != null)
                    .limit(10)
                    .forEach(i -> problems.add(i.id() + " -> " + i.error().reason()));
        }
        long failed = response.items().stream().filter(i -> i.error() != null).count();
        int indexed = docs.size() - (int) failed;
        log.info("Da nap {} document ({} loi).", indexed, failed);
        return indexed;
    }
}
