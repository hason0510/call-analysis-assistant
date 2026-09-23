package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Ket luan theo rule. Sprint 2 se dung day lam moc doi chieu cho verdict cua AI
 * va lam duong lui khi AI loi (MVP muc 3.2, 6.1 T8).
 */
public record RuleVerdict(
        Verdict verdict,
        boolean qualityFlag,
        IssueCategory issueCategory,
        ConfidenceLevel confidence,
        String reasoning,
        List<String> dataLimitations
) {

    public RuleVerdict {
        dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
    }
}
