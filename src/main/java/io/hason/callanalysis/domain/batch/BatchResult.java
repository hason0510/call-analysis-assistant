package io.hason.callanalysis.domain.batch;

import io.hason.callanalysis.domain.report.CallReport;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

/**
 * Kết quả của một cuộc gọi trong lượt phân tích hàng loạt (MVP 7.1 T10). Chỉ mã, số và cờ — không chữ tự do — nên
 * ghi được vào Elasticsearch mà không cần qua Sanitizer (MVP 6.2), như {@code RequestRecord} của T9.
 *
 * @param callId        Call-ID gốc: chỉ dùng cho báo cáo ghi ra máy (target/batch); bản ghi ES dùng mã giả
 * @param issueCategory null khi SUCCESS không có cờ chất lượng
 * @param evidenceCount số evidence của report
 */
public record BatchResult(
        String callId,
        SourceGroup sourceGroup,
        Verdict verdict,
        IssueCategory issueCategory,
        boolean qualityFlag,
        ConfidenceLevel confidence,
        int evidenceCount
) {

    public static BatchResult of(CallReport report, SourceGroup sourceGroup) {
        return new BatchResult(report.callId(), sourceGroup, report.verdict(), report.issueCategory(),
                report.qualityFlag(), report.confidenceLevel(), report.evidence().size());
    }

    /** Cùng kết quả, Call-ID thay bằng {@code callId} — dùng để thay Call-ID gốc bằng mã giả trước khi ghi ES. */
    public BatchResult withCallId(String callId) {
        return new BatchResult(callId, sourceGroup, verdict, issueCategory, qualityFlag, confidence, evidenceCount);
    }
}
