package io.hason.callanalysis.domain.taxonomy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Toan bo taxonomy da nap, tra cuu theo category. */
public record IssueTaxonomy(List<IssueDefinition> definitions) {

    public IssueTaxonomy {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
    }

    public Optional<IssueDefinition> find(IssueCategory category) {
        return definitions.stream().filter(d -> d.id() == category).findFirst();
    }

    /** Category chua kiem chung duoc tren data — phai ghi vao Known Limitations. */
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
