package io.hason.callanalysis.domain.confidence;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;

import java.util.List;

/**
 * Mức tin cậy cùng lý do, cả hai do {@link ConfidencePolicy} tính — report in lý do cạnh mức
 * ("MEDIUM — thiếu log client của callee"), người đọc không phải đoán vì sao.
 *
 * @param reasons căn cứ của mức này, theo thứ tự: yếu tố hạ mức (nếu có) trước, bằng chứng của kết luận sau
 */
public record ConfidenceAssessment(ConfidenceLevel level, List<String> reasons) {

    public ConfidenceAssessment {
        if (level == null) {
            throw new IllegalArgumentException("level null");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** "MEDIUM — lý do 1; lý do 2": dạng in ở dòng "Độ tin cậy" của report. */
    public String describe() {
        return reasons.isEmpty() ? level.name() : level.name() + " — " + String.join("; ", reasons);
    }
}
