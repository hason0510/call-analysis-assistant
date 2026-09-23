package io.hason.callanalysis.domain.taxonomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Toàn bộ taxonomy đã nạp, tra cứu theo category. */
public record IssueTaxonomy(List<IssueDefinition> definitions) {

    public IssueTaxonomy {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
    }

    public Optional<IssueDefinition> find(IssueCategory category) {
        return definitions.stream().filter(d -> d.id() == category).findFirst();
    }

    /** Category chưa kiểm chứng được trên data — phải ghi vào Known Limitations. */
    public List<IssueCategory> unvalidatedCategories() {
        return definitions.stream()
                .filter(d -> !d.calibration().isValidated())
                .map(IssueDefinition::id)
                .toList();
    }

    public Map<IssueCategory, IssueDefinition> byCategory() {
        Map<IssueCategory, IssueDefinition> map = new LinkedHashMap<>();
        definitions.forEach(d -> map.put(d.id(), d));
        return Map.copyOf(map);
    }
}
