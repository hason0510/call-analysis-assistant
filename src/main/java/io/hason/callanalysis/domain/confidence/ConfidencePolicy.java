package io.hason.callanalysis.domain.confidence;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nơi DUY NHẤT quyết định độ tin cậy (MVP mục 7.2). Không để AI sinh số; mức suy bằng logic tất định từ
 * bốn thứ bảng 7.2 nêu:
 *
 * <pre>
 *   Strong evidence + AI khớp rule + đủ file    → HIGH
 *   Partial evidence hoặc thiếu một phần file   → MEDIUM
 *   Weak / ambiguous evidence hoặc AI lệch rule → LOW
 * </pre>
 *
 * Hai bước, vì rule cần mức của mình trước khi có AI (gửi sang AI làm mốc, và là kết quả khi AI lỗi):
 * - {@link #forRule}: độ mạnh bằng chứng ({@link ConfidenceBasis}, rule đặt) + dữ liệu thiếu ({@link DataCoverage}).
 * - {@link #forReport}: thêm kết quả đối chiếu với AI.
 *
 * Trước Sprint 3 T3 các mức này nằm rải ở ~10 nhánh của RuleVerdictEngine và hai chỗ của ReportBuilder;
 * report không nói vì sao MEDIUM. Mức của 20 cuộc gọi mẫu không đổi khi gom về đây (design-decisions mục 35).
 */
public final class ConfidencePolicy {

    /** Kết quả đối chiếu với AI. */
    public enum AiCheck {
        /** AI khớp rule (verdict, cờ chất lượng, category). */
        AGREES,
        /** AI lệch rule — Guardrails G04, report gắn cờ cần kiểm tra. */
        DISAGREES,
        /** Đã thử AI nhưng phải lùi về rule (timeout, lỗi provider, bị Guardrails loại…). */
        UNAVAILABLE,
        /** Không chạy AI: luồng CLI chỉ rule của Sprint 1. */
        NOT_RUN
    }

    static final String TRUNCATED = "bản export signaling bị cắt bớt, mà kết luận dựa trên việc không thấy sự kiện";
    static final String AI_AGREES = "AI khớp rule";
    static final String AI_DISAGREES = "AI và rule không thống nhất";

    private ConfidencePolicy() {
    }

    public static ConfidenceAssessment forRule(ConfidenceBasis basis, DataCoverage coverage) {
        List<String> lowering = new ArrayList<>();
        if (basis.absenceBased() && coverage.signalingTruncated()) {
            lowering.add(TRUNCATED);
        }
        if (basis.requiresAllSources() && !coverage.missingSources().isEmpty()) {
            lowering.add("thiếu " + String.join(", ", coverage.missingSources().stream()
                    .map(ConfidencePolicy::label).toList()) + " nên không loại trừ được lỗi ở nguồn đó");
        }
        if (!basis.exemptFromMissingLegs() && !coverage.legsMissingClientLog().isEmpty()) {
            lowering.add("thiếu log client của " + String.join(", ", coverage.legsMissingClientLog().stream()
                    .map(l -> l.name().toLowerCase(Locale.ROOT)).toList()));
        }

        ConfidenceLevel level = switch (basis.strength()) {
            case STRONG -> lowering.isEmpty() ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM;
            case PARTIAL -> ConfidenceLevel.MEDIUM;
            case WEAK -> ConfidenceLevel.LOW;
        };

        // Lý do làm mức thấp đi đứng trước: người đọc thấy ngay vì sao không phải HIGH.
        List<String> reasons = new ArrayList<>();
        String evidence = switch (basis.strength()) {
            case STRONG -> "bằng chứng rõ: ";
            case PARTIAL -> "bằng chứng chưa trọn: ";
            case WEAK -> "bằng chứng yếu: ";
        } + basis.strengthReason();
        if (basis.strength() != EvidenceStrength.STRONG) {
            reasons.add(evidence);
        }
        reasons.addAll(lowering);
        if (basis.strength() == EvidenceStrength.STRONG) {
            reasons.add(evidence);
        }
        return new ConfidenceAssessment(level, reasons);
    }

    /**
     * @param fallbackReason mã lý do lùi về rule khi {@code ai} là UNAVAILABLE; bỏ qua ở các trường hợp khác
     */
    public static ConfidenceAssessment forReport(ConfidenceAssessment rule, AiCheck ai, String fallbackReason) {
        return switch (ai) {
            case DISAGREES -> new ConfidenceAssessment(ConfidenceLevel.LOW, prepend(AI_DISAGREES, rule.reasons()));
            case UNAVAILABLE -> new ConfidenceAssessment(
                    rule.level() == ConfidenceLevel.HIGH ? ConfidenceLevel.MEDIUM : rule.level(),
                    prepend("không có AI để đối chiếu" + (fallbackReason == null ? "" : " (" + fallbackReason + ")"),
                            rule.reasons()));
            case AGREES -> {
                List<String> reasons = new ArrayList<>(rule.reasons());
                reasons.add(AI_AGREES);
                yield new ConfidenceAssessment(rule.level(), reasons);
            }
            // Luồng CLI chỉ rule giữ mức của rule như Sprint 1 — xem design-decisions mục 35, phần còn mở.
            case NOT_RUN -> rule;
        };
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static String label(LogSource source) {
        return switch (source) {
            case SIGNALING -> "signaling";
            case ENDCALL -> "end call log";
            case WEBRTC -> "WebRTC log";
        };
    }
}
