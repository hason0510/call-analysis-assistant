package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.evidence.Evidence;

import java.util.List;

/**
 * Evidence AI trích làm căn cứ trong MỘT lần trả lời, so với evidence có sẵn (Sprint 3 T1b).
 *
 * Chỉ giữ ID — không mang nội dung cuộc gọi, như phần còn lại của file kết quả.
 *
 * @param cited     ID AI trích, đúng thứ tự AI trả về, trước Guardrails (có thể có ID không tồn tại — G01)
 * @param available số evidence trong context gửi AI
 * @param ruleBasis ID của evidence là dòng căn cứ của rule ({@link Evidence#ruleBasis()})
 */
public record EvidenceCitation(List<String> cited, int available, List<String> ruleBasis) {

    public EvidenceCitation {
        cited = cited == null ? List.of() : List.copyOf(cited);
        ruleBasis = ruleBasis == null ? List.of() : List.copyOf(ruleBasis);
    }

    public static EvidenceCitation of(List<String> cited, List<Evidence> evidence) {
        return new EvidenceCitation(cited, evidence.size(),
                evidence.stream().filter(Evidence::ruleBasis).map(Evidence::id).toList());
    }

    /** Trích mọi evidence có sẵn — không chọn lọc gì. */
    public boolean citesAll() {
        return available > 0 && cited.stream().distinct().count() >= available;
    }

    /** Có dòng căn cứ của rule và AI trích đủ cả. */
    public boolean coversRuleBasis() {
        return cited.containsAll(ruleBasis);
    }
}
