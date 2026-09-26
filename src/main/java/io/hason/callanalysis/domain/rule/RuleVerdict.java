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
        List<String> dataLimitations,
        /**
         * Kiểu TURN hỏng khi issueCategory là TURN_FAILURE, để report chọn đề xuất đúng
         * hướng; {@link TurnFailure#NONE} với mọi kết luận khác.
         */
        TurnFailure turnFailure,
        /**
         * Căn cứ đo được của CHÍNH cuộc gọi cho issueCategory, để report in thay câu định nghĩa
         * chung của taxonomy; null thì report dùng định nghĩa. Hiện đặt cho cờ chất lượng: định
         * nghĩa NETWORK_PACKET_LOSS khẳng định "đủ làm giảm chất lượng thoại", điều rule không kiểm.
         */
        String causeBasis
) {

    public RuleVerdict {
        dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
        turnFailure = turnFailure == null ? TurnFailure.NONE : turnFailure;
    }

    public RuleVerdict(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                       ConfidenceLevel confidence, String reasoning, List<String> dataLimitations) {
        this(verdict, qualityFlag, issueCategory, confidence, reasoning, dataLimitations, TurnFailure.NONE, null);
    }

    public RuleVerdict(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                       ConfidenceLevel confidence, String reasoning, List<String> dataLimitations,
                       TurnFailure turnFailure) {
        this(verdict, qualityFlag, issueCategory, confidence, reasoning, dataLimitations, turnFailure, null);
    }
}
