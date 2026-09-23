package io.hason.callanalysis.infrastructure.taxonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.IssueDefinition;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Nạp taxonomy từ resources/taxonomy.yaml. Đọc file là I/O nên thuộc tầng infrastructure. */
@Component
public class TaxonomyLoader {

    private static final String RESOURCE = "taxonomy.yaml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    private IssueTaxonomy cached;

    public synchronized IssueTaxonomy load() {
        if (cached == null) {
            try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
                cached = parse(yaml.readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException("Không nạp được " + RESOURCE, e);
            }
        }
        return cached;
    }

    IssueTaxonomy parse(JsonNode root) {
        List<IssueDefinition> definitions = new ArrayList<>();
        for (JsonNode node : root.path("categories")) {
            definitions.add(new IssueDefinition(
                    IssueCategory.valueOf(node.path("id").asText()),
                    node.path("definition").asText(""),
                    texts(node.path("symptoms")),
                    requiredEvidence(node.path("requiredEvidence")),
                    texts(node.path("detectionConditions")),
                    node.path("knownAmbiguity").asText(""),
                    calibration(node.path("calibration"))));
        }
        return new IssueTaxonomy(definitions);
    }

    private static IssueDefinition.Calibration calibration(JsonNode node) {
        String status = node.path("status").asText("UNVALIDATED");
        return new IssueDefinition.Calibration(
                IssueDefinition.Calibration.Status.valueOf(status),
                node.path("evidence").asText(""));
    }

    private static List<IssueDefinition.RequiredEvidence> requiredEvidence(JsonNode node) {
        List<IssueDefinition.RequiredEvidence> list = new ArrayList<>();
        node.forEach(n -> list.add(new IssueDefinition.RequiredEvidence(
                n.path("source").asText(), n.path("field").asText())));
        return list;
    }

    private static List<String> texts(JsonNode node) {
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
    }
}
