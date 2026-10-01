package io.hason.callanalysis.infrastructure.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Đọc các file signaling.json trong thư mục data mẫu và nạp vào Elasticsearch local.
 *
 * Import là idempotent: _id được suy ra tất định từ (callId, ordinal), nên chạy lại
 * nhiều lần không sinh bản ghi trùng — đúng yêu cầu "lặp lại được" của MVP mục 5.1 T1.
 *
 * Gửi theo LÔ {@link #DEFAULT_BATCH_SIZE} document. Bản đầu gom mọi document của mọi file
 * vào MỘT bulk request: 1 059 document thì ổn, nhưng data lớn hơn sẽ đụng giới hạn
 * `http.max_content_length` (mặc định 100 MB) và giữ toàn bộ trong heap.
 */
@Component
public class SignalingImporter {

    private static final Logger log = LoggerFactory.getLogger(SignalingImporter.class);
    private static final String FILE_NAME = "signaling.json";
    static final int DEFAULT_BATCH_SIZE = 500;

    private final ElasticsearchClient client;
    private final SignalingJsonReader reader;
    private final int batchSize;

    @Autowired
    public SignalingImporter(ElasticsearchClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, DEFAULT_BATCH_SIZE);
    }

    /** Cho test ép nhiều lô với data nhỏ. */
    SignalingImporter(ElasticsearchClient client, ObjectMapper objectMapper, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize phải >= 1: " + batchSize);
        }
        this.client = client;
        this.reader = new SignalingJsonReader(objectMapper);
        this.batchSize = batchSize;
    }

    public record ImportReport(int filesRead, int documentsIndexed, List<String> problems) {}

    public ImportReport importFrom(Path root) throws IOException {
        List<Path> files = findSignalingFiles(root);
        if (files.isEmpty()) {
            return new ImportReport(0, 0, List.of("Không tìm thấy file " + FILE_NAME + " nào dưới " + root));
        }

        List<String> problems = new ArrayList<>();
        List<SignalingDocument> batch = new ArrayList<>(batchSize);
        int indexed = 0;

        for (Path file : files) {
            List<SignalingDocument> docs;
            try {
                docs = reader.read(Files.readString(file, StandardCharsets.UTF_8));
            } catch (Exception e) {
                problems.add(file + " -> " + e.getMessage());
                continue;
            }
            for (SignalingDocument doc : docs) {
                batch.add(doc);
                if (batch.size() == batchSize) {
                    indexed += bulkIndex(batch, problems);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            indexed += bulkIndex(batch, problems);
        }
        log.info("Đã nạp {} document từ {} file.", indexed, files.size());
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
        // refresh mỗi lô thay vì một lần cuối: import chỉ chạy tay, đổi lại lô nào nạp xong
        // là truy vấn được ngay, và không cần thêm lời gọi refresh riêng.
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
        log.debug("Lô {} document: {} lỗi.", docs.size(), failed);
        return indexed;
    }
}
