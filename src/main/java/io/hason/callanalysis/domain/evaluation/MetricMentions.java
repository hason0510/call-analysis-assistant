package io.hason.callanalysis.domain.evaluation;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.MetricCodes;
import io.hason.callanalysis.domain.guardrail.Guardrails;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI nhắc chỉ số trong bảng bằng cách nào, đếm trên đầu ra THÔ của một lần trả lời (Sprint 3 T1a).
 *
 * @param viaCode     số lần nhắc qua mã chỉ số ({@code {MOS_CALLER}}) — tên và giá trị do code điền
 * @param handWritten số con số AI tự viết trùng giá trị một chỉ số trong bảng. Là CẬN TRÊN: số trùng ngẫu nhiên
 *                    (ví dụ "1" khi bảng có "1 lần") cũng tính, vì không phân biệt được bằng code
 */
public record MetricMentions(int viaCode, int handWritten) {

    /** @param metrics bảng chỉ số đã gửi AI ({@code AiContext.payload().metrics()}) */
    public static MetricMentions of(AiAnalysis ai, List<AiContext.MetricItem> metrics) {
        Set<BigDecimal> metricNumbers = new HashSet<>();
        metrics.stream()
                .map(AiContext.MetricItem::value)
                .filter(v -> v != null && !v.startsWith("N/A"))   // số trong lý do N/A không phải giá trị đo
                .forEach(v -> metricNumbers.addAll(Guardrails.numbers(v)));
        int viaCode = 0;
        int handWritten = 0;
        for (String text : ai.freeText()) {
            viaCode += MetricCodes.codesIn(text).size();
            handWritten += (int) Guardrails.numbers(text).stream().filter(metricNumbers::contains).count();
        }
        return new MetricMentions(viaCode, handWritten);
    }
}
