package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.confidence.ConfidenceAssessment;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.List;

/**
 * Kết luận theo rule: mốc đối chiếu cho verdict của AI (Guardrails G04) và đường lui khi AI lỗi (MVP mục 3.2,
 * 6.1 T8).
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
        String causeBasis,
        /** Vì sao ở mức {@code confidence} — do {@link io.hason.callanalysis.domain.confidence.ConfidencePolicy} viết. */
        List<String> confidenceReasons,
        /** Nguyên nhân con khi issueCategory là SIGNALING_FAILURE, để chọn đề xuất; null với mọi kết luận khác. */
        SignalingCause signalingCause
) {

    public RuleVerdict {
        dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
        turnFailure = turnFailure == null ? TurnFailure.NONE : turnFailure;
        confidenceReasons = confidenceReasons == null ? List.of() : List.copyOf(confidenceReasons);
    }

    public RuleVerdict(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                       ConfidenceLevel confidence, String reasoning, List<String> dataLimitations,
                       TurnFailure turnFailure, String causeBasis) {
        this(verdict, qualityFlag, issueCategory, confidence, reasoning, dataLimitations, turnFailure, causeBasis,
                List.of(), null);
    }

    public RuleVerdict(Verdict verdict, boolean qualityFlag, IssueCategory issueCategory,
                       ConfidenceLevel confidence, String reasoning, List<String> dataLimitations,
                       TurnFailure turnFailure, String causeBasis, List<String> confidenceReasons) {
        this(verdict, qualityFlag, issueCategory, confidence, reasoning, dataLimitations, turnFailure, causeBasis,
                confidenceReasons, null);
    }

    public ConfidenceAssessment confidenceAssessment() {
        return new ConfidenceAssessment(confidence, confidenceReasons);
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
