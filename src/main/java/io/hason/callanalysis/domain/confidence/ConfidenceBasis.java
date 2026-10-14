package io.hason.callanalysis.domain.confidence;

/**
 * Rule mô tả căn cứ của một kết luận cho {@link ConfidencePolicy}: bằng chứng mạnh tới đâu, và kết luận
 * nhạy với loại dữ liệu thiếu nào. Dữ liệu THỰC SỰ thiếu không nằm ở đây mà ở {@link DataCoverage}.
 *
 * @param strengthReason        vì sao bằng chứng ở mức đó — in vào lý do độ tin cậy của report
 * @param absenceBased          kết luận dựa trên việc KHÔNG THẤY một sự kiện signaling (không có INVITE,
 *                              không thấy lỗi media…): bản export bị cắt thì chính cái "không thấy" có thể
 *                              chỉ là do mất
 * @param requiresAllSources    kết luận cần đủ cả ba nguồn log (SUCCESS: không thấy lỗi ở nguồn nào cả)
 * @param exemptFromMissingLegs thiếu log client không làm kết luận yếu đi — chỉ khi server tự trả lời
 *                              (từ chối INIT_CALL kèm mã nguyên văn), không log client nào bổ sung hay
 *                              phản bác được
 */
public record ConfidenceBasis(EvidenceStrength strength, String strengthReason, boolean absenceBased,
                              boolean requiresAllSources, boolean exemptFromMissingLegs) {

    public ConfidenceBasis {
        if (strength == null || strengthReason == null || strengthReason.isBlank()) {
            throw new IllegalArgumentException("căn cứ độ tin cậy phải có mức và lý do");
        }
    }

    public static ConfidenceBasis strong(String reason) {
        return new ConfidenceBasis(EvidenceStrength.STRONG, reason, false, false, false);
    }

    public static ConfidenceBasis partial(String reason) {
        return new ConfidenceBasis(EvidenceStrength.PARTIAL, reason, false, false, false);
    }

    public static ConfidenceBasis weak(String reason) {
        return new ConfidenceBasis(EvidenceStrength.WEAK, reason, false, false, false);
    }

    public ConfidenceBasis asAbsenceBased() {
        return new ConfidenceBasis(strength, strengthReason, true, requiresAllSources, exemptFromMissingLegs);
    }

    public ConfidenceBasis asRequiringAllSources() {
        return new ConfidenceBasis(strength, strengthReason, absenceBased, true, exemptFromMissingLegs);
    }

    public ConfidenceBasis asExemptFromMissingLegs() {
        return new ConfidenceBasis(strength, strengthReason, absenceBased, requiresAllSources, true);
    }
}
