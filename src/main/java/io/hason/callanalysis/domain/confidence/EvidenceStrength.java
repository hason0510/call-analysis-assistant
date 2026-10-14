package io.hason.callanalysis.domain.confidence;

/**
 * Bằng chứng cho CHÍNH kết luận mạnh tới đâu — cột đầu của bảng MVP mục 7.2. Rule đặt, vì chỉ rule biết
 * kết luận đứng trên dòng log nào; {@link ConfidencePolicy} đổi ra mức tin cậy.
 */
public enum EvidenceStrength {
    /** "Strong evidence": dấu hiệu dương tính đủ để kết luận, ví dụ ICE chuyển sang failed. */
    STRONG,
    /** "Partial evidence": có dấu hiệu nhưng còn một cách hiểu khác, ví dụ CANCEL chưa rõ ai huỷ. */
    PARTIAL,
    /** "Weak / ambiguous evidence": không đủ để kết luận, hoặc ngưỡng chưa kiểm chứng. */
    WEAK
}
