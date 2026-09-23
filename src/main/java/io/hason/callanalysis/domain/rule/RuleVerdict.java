package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Kết luận theo rule. Sprint 2 sẽ dùng đây làm mốc đối chiếu cho verdict của AI
 * và làm đường lui khi AI lỗi (MVP mục 3.2, 6.1 T8).
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
