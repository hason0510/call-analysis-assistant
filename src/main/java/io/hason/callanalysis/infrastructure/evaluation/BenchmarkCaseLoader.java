package io.hason.callanalysis.infrastructure.evaluation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.hason.callanalysis.domain.evaluation.BenchmarkCase;
import io.hason.callanalysis.domain.request.Intent;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Đọc bộ case benchmark từ file YAML theo mẫu MVP mục 6.3 — file của mentor (`held-out`) phải đọc được
 * mà không sửa gì.
 *
 * Nhận cả ba cách xếp nhiều case: một case mỗi tài liệu (cách nhau `---`), một danh sách ở gốc, hoặc chỉ
 * một case. Câu hỏi là chuỗi (mẫu 6.3) hoặc {@code {text, expected_intent}} (mở rộng tuỳ chọn).
 *
 * Case sai (thiếu trường, giá trị ngoài taxonomy, trùng case_id) bị loại và NÊU TÊN, các case khác vẫn
 * chạy — một lỗi gõ trong file 50 case không được làm mất cả lượt đánh giá.
 */
@Component
public class BenchmarkCaseLoader {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    /** @param errors mỗi case bị loại một dòng: vị trí, case_id nếu có, lý do */
    public record Loaded(List<BenchmarkCase> cases, List<String> errors) {}

    public Loaded load(Path file) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được file case " + file, e);
        }
    }

    public Loaded parse(String content) {
        List<JsonNode> nodes = new ArrayList<>();
        try (Reader reader = new StringReader(content);
             JsonParser parser = yaml.getFactory().createParser(reader);
             MappingIterator<JsonNode> docs = yaml.readValues(parser, JsonNode.class)) {
            while (docs.hasNextValue()) {
                JsonNode doc = docs.nextValue();
                if (doc == null || doc.isNull() || doc.isMissingNode()) {
                    continue;                                   // tài liệu rỗng: chỉ có chú thích
                }
                if (doc.isArray()) {
                    doc.forEach(nodes::add);
                } else {
                    nodes.add(doc);
                }
            }
        } catch (IOException e) {
            return new Loaded(List.of(), List.of("File case không phải YAML hợp lệ: " + firstLine(e.getMessage())));
        }

        List<BenchmarkCase> cases = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonNode node = nodes.get(i);
            String where = "case thứ " + (i + 1) + (node.hasNonNull("case_id") ? " (" + node.get("case_id").asText() + ")" : "");
            try {
                BenchmarkCase c = toCase(node);
                if (!seen.add(c.caseId())) {
                    throw new IllegalArgumentException("case_id trùng với case trước");
                }
                cases.add(c);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage() + " — đã bỏ qua case này");
            }
        }
        return new Loaded(cases, errors);
    }

    private static BenchmarkCase toCase(JsonNode n) {
        if (!n.isObject()) {
            throw new IllegalArgumentException("không phải một case (cần các trường case_id, call_id, files, questions…)");
        }
        String caseId = requiredText(n, "case_id");
        String callId = requiredText(n, "call_id");
        // files: [] hợp lệ — ca "UNKNOWN do thiếu file" (MVP mục 6.3): chỉ có Call-ID, không đính kèm gì.
        // Thiếu hẳn trường files vẫn là lỗi: không phân biệt được với case viết sót.
        List<String> files = texts(n.path("files"), "files");
        List<BenchmarkCase.Question> questions = new ArrayList<>();
        for (JsonNode q : n.path("questions")) {
            questions.add(question(q));
        }
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions rỗng");
        }
        JsonNode flag = n.path("expected_quality_flag");
        if (!isAbsent(flag) && !flag.isBoolean()) {
            throw new IllegalArgumentException("expected_quality_flag phải là true / false, gặp '" + flag.asText() + "'");
        }
        JsonNode evidence = n.path("expected_evidence");
        return new BenchmarkCase(caseId, callId, files, questions,
                optionalEnum(n, "expected_verdict", Verdict.class),
                isAbsent(flag) ? null : flag.booleanValue(),
                optionalEnum(n, "expected_issue_category", IssueCategory.class),
                isAbsent(evidence) ? null : texts(evidence, "expected_evidence"),
                isAbsent(n.path("split")) ? null : n.get("split").asText());
    }

    private static BenchmarkCase.Question question(JsonNode q) {
        if (q.isTextual()) {
            return new BenchmarkCase.Question(q.asText(), null);
        }
        if (q.isObject() && q.path("text").isTextual()) {
            return new BenchmarkCase.Question(q.get("text").asText(), optionalEnum(q, "expected_intent", Intent.class));
        }
        throw new IllegalArgumentException("câu hỏi phải là chuỗi hoặc {text, expected_intent}");
    }

    /** Giá trị enum đúng tên, phân biệt hoa thường — cùng luật với Guardrails G03 ("success" không phải SUCCESS). */
    private static <E extends Enum<E>> E optionalEnum(JsonNode n, String field, Class<E> type) {
        JsonNode v = n.path(field);
        if (isAbsent(v)) {
            return null;
        }
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(v.asText())) {
                return e;
            }
        }
        throw new IllegalArgumentException(field + " '" + v.asText() + "' không thuộc "
                + Arrays.toString(type.getEnumConstants()));
    }

    private static String requiredText(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (isAbsent(v) || v.asText().isBlank()) {
            throw new IllegalArgumentException("thiếu " + field);
        }
        return v.asText().strip();
    }

    private static List<String> texts(JsonNode array, String field) {
        if (!array.isArray()) {
            throw new IllegalArgumentException(field + " phải là danh sách");
        }
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText().strip()));
        return out;
    }

    private static boolean isAbsent(JsonNode v) {
        return v == null || v.isMissingNode() || v.isNull();
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }
}
