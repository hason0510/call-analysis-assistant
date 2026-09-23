package io.hason.callanalysis.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.hason.callanalysis.domain.security.DataClassification;
import io.hason.callanalysis.domain.security.HandlingPolicy;
import io.hason.callanalysis.domain.security.SensitiveField;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Nap Sensitive Data Inventory (T9).
 *
 * Sprint 1 chi lap danh muc; Sprint 2 (T7) se dung chinh danh muc nay lam Policy Engine
 * cho Sanitizer, nen no duoc giu o dang du lieu thay vi tai lieu.
 */
@Component
public class SensitiveDataInventoryLoader {

    private static final String RESOURCE = "sensitive-data-inventory.yaml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private List<SensitiveField> cached;

    public synchronized List<SensitiveField> load() {
        if (cached == null) {
            try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
                cached = parse(yaml.readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException("Khong nap duoc " + RESOURCE, e);
            }
        }
        return cached;
    }

    List<SensitiveField> parse(JsonNode root) {
        List<SensitiveField> fields = new ArrayList<>();
        for (JsonNode node : root.path("fields")) {
            List<String> names = new ArrayList<>();
            node.path("match").forEach(n -> names.add(n.asText()));

            String pattern = node.path("pattern").asText(null);

            fields.add(new SensitiveField(
                    node.path("id").asText(),
                    names,
                    pattern == null || pattern.isBlank() ? null : Pattern.compile(pattern),
                    DataClassification.valueOf(node.path("classification").asText()),
                    HandlingPolicy.valueOf(node.path("policy").asText()),
                    node.path("rationale").asText("").strip(),
                    SensitiveField.Origin.valueOf(node.path("origin").asText("SAMPLE_REVIEW"))));
        }
        return List.copyOf(fields);
    }
}
