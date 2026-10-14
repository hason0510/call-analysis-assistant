package io.hason.callanalysis.domain.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * AiContext dựng tay cho test của adapter / service. Nằm cùng package vì constructor của AiContext
 * là package-private: mã chính chỉ tạo được nó qua {@link AiContextBuilder}.
 */
public final class AiContextFixtures {

    private AiContextFixtures() {
    }

    /** Context FAIL / SIGNALING_FAILURE với các evidence ID cho trước, mô tả không chứa số. */
    public static AiContext withEvidence(String requestId, String question, String... evidenceIds) {
        List<AiContext.EvidenceItem> evidence = Arrays.stream(evidenceIds)
                .map(id -> new AiContext.EvidenceItem(id, "SIGNALING", "CALLER", "-", "Signaling INIT_CALL", "-", false))
                .toList();
        AiContext.Payload payload = new AiContext.Payload(
                new AiContext.RuleSummary("FAIL", false, "SIGNALING_FAILURE", "HIGH", "Không tồn tại INVITE", null,
                        List.of()),
                evidence, List.of(), List.of(), List.of(), List.of());
        return new AiContext(requestId, question, null, payload, Map.of());
    }

    /** Như {@link #withEvidence}, kèm Call-ID đã mã giả cho log. */
    public static AiContext withLogCallId(String requestId, String logCallId) {
        AiContext base = withEvidence(requestId, "Phân tích giúp mình", "EV01");
        return new AiContext(requestId, base.question(), null, base.payload(), Map.of(), logCallId);
    }
}
