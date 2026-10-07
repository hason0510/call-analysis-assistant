package io.hason.callanalysis.domain.guardrail;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.guardrail.GuardrailResult.Status;
import io.hason.callanalysis.domain.guardrail.GuardrailResult.Violation;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricValue;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kiểm đầu ra AI trước khi vào report (MVP mục 6.1 T6, ca G01-G05 mục 6.4).
 *
 * Thứ tự: lỗi HÌNH THỨC (G02, G03, G01, G05) → REJECTED, fallback về rule; chỉ khi hình thức
 * hợp lệ mới đối chiếu NỘI DUNG với rule (G04) → FLAGGED. Lý do: một câu trả lời đã bịa
 * evidence hay số liệu thì không đáng được đem ra so verdict.
 *
 * Lớp thuần, không gọi AI, không I/O — chạy tất định trên mọi lần như phần còn lại của domain.
 */
public class Guardrails {

    /**
     * Một con số đứng riêng: không dính chữ hay số phía trước/sau, nên bỏ qua "EV02", "2D9057AA",
     * "H264". Dấu phẩy thập phân kiểu Việt ("3,5") được chấp nhận.
     */
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\p{L}\\d.,])\\d+(?:[.,]\\d+)?(?![\\p{L}\\d])");

    /**
     * @param knownEvidenceIds ID evidence mà Evidence Engine đã cấp cho cuộc gọi này
     * @param groundingTexts   văn bản đã có trong context gửi AI (mô tả evidence, mốc thời gian,
     *                         câu lý do của rule): số nào xuất hiện ở đây thì AI được phép nhắc lại
     */
    public GuardrailResult check(AiAnalysis ai, RuleVerdict rule, Collection<String> knownEvidenceIds,
                                 CallMetrics metrics, Collection<String> groundingTexts) {
        if (ai == null) {
            return rejected(List.of(new Violation("G02", "AI không trả về nội dung")));
        }
        List<Violation> formal = new ArrayList<>();

        // G03 — verdict / issueCategory ngoài taxonomy
        Optional<Verdict> verdict = parse(Verdict.class, ai.verdict());
        if (verdict.isEmpty()) {
            formal.add(new Violation("G03", "verdict ngoài taxonomy: '" + ai.verdict() + "'"));
        }
        boolean noIssue = AiAnalysis.NO_ISSUE.equals(ai.issueCategory());
        Optional<IssueCategory> category = noIssue ? Optional.empty() : parse(IssueCategory.class, ai.issueCategory());
        if (!noIssue && category.isEmpty()) {
            formal.add(new Violation("G03", "issueCategory ngoài taxonomy: '" + ai.issueCategory() + "'"));
        }

        // G02 — thiếu trường / trường không nhất quán với nhau
        if (ai.summary() == null || ai.summary().isBlank()) {
            formal.add(new Violation("G02", "thiếu summary"));
        }
        if (verdict.isPresent()) {
            boolean needsCategory = verdict.get() == Verdict.FAIL || ai.qualityFlag();
            if (needsCategory && noIssue) {
                formal.add(new Violation("G02", verdict.get() + (ai.qualityFlag() ? " có cờ chất lượng" : "")
                        + " nhưng không nêu issueCategory"));
            }
            if (verdict.get() != Verdict.UNKNOWN && ai.evidenceIds().isEmpty()) {
                formal.add(new Violation("G02", "kết luận " + verdict.get() + " không trích evidence nào"));
            }
        }

        // G01 — evidence ID không tồn tại
        Set<String> known = Set.copyOf(knownEvidenceIds);
        ai.evidenceIds().stream()
                .filter(id -> !known.contains(id))
                .forEach(id -> formal.add(new Violation("G01", "evidence ID không tồn tại: " + id)));

        // G05 — số liệu không có trong bộ chỉ số đã tính hay trong context
        Set<BigDecimal> allowed = allowedNumbers(metrics, groundingTexts);
        unsupportedNumbers(ai.freeText(), allowed)
                .forEach(n -> formal.add(new Violation("G05", "số liệu không có trong input: " + n)));

        if (!formal.isEmpty()) {
            return rejected(formal);
        }

        // G04 — đối chiếu với rule verdict
        List<Violation> content = new ArrayList<>();
        if (verdict.get() != rule.verdict()) {
            content.add(new Violation("G04", "AI kết luận " + verdict.get() + ", rule kết luận " + rule.verdict()));
            return new GuardrailResult(Status.FLAGGED, Verdict.UNKNOWN, false, IssueCategory.UNKNOWN, content);
        }
        IssueCategory finalCategory = category.orElse(null);
        boolean finalFlag = ai.qualityFlag();
        // Cùng verdict nhưng khác nguyên nhân: giữ của rule. Rule tất định, nên cùng log luôn ra
        // cùng category — đúng mục tiêu Consistency (MVP 6.5); AI lệch thì gắn cờ để người xem.
        if (finalCategory != rule.issueCategory()) {
            content.add(new Violation("G04", "AI xếp " + finalCategory + ", rule xếp " + rule.issueCategory()));
            finalCategory = rule.issueCategory();
        }
        if (finalFlag != rule.qualityFlag()) {
            content.add(new Violation("G04", "cờ chất lượng của AI (" + finalFlag + ") khác rule (" + rule.qualityFlag() + ")"));
            finalFlag = rule.qualityFlag();
        }
        Status status = content.isEmpty() ? Status.PASSED : Status.FLAGGED;
        return new GuardrailResult(status, verdict.get(), finalFlag, finalCategory, content);
    }

    private static GuardrailResult rejected(List<Violation> violations) {
        return new GuardrailResult(Status.REJECTED, null, false, null, violations);
    }

    private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return Arrays.stream(type.getEnumConstants()).filter(e -> e.name().equals(raw)).findFirst();
    }

    private static Set<BigDecimal> allowedNumbers(CallMetrics metrics, Collection<String> groundingTexts) {
        Set<BigDecimal> allowed = new LinkedHashSet<>();
        metrics.metrics().forEach(m -> {
            if (m.value() instanceof MetricValue.Present p) {
                allowed.add(normalize(p.value()));
            } else if (m.value() instanceof MetricValue.Text t) {
                allowed.addAll(numbersIn(t.value()));
            }
        });
        groundingTexts.forEach(t -> allowed.addAll(numbersIn(t)));
        return allowed;
    }

    private static List<String> unsupportedNumbers(List<String> texts, Set<BigDecimal> allowed) {
        Set<String> unsupported = new LinkedHashSet<>();
        for (String text : texts) {
            Matcher m = NUMBER.matcher(text);
            while (m.find()) {
                if (!allowed.contains(toNumber(m.group()))) {
                    unsupported.add(m.group());
                }
            }
        }
        return List.copyOf(unsupported);
    }

    private static Set<BigDecimal> numbersIn(String text) {
        Set<BigDecimal> numbers = new LinkedHashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            numbers.add(toNumber(m.group()));
        }
        return numbers;
    }

    private static BigDecimal toNumber(String token) {
        return normalize(new BigDecimal(token.replace(',', '.')));
    }

    /** 3.50 và 3.5 là một số; 08 và 8 cũng vậy. */
    private static BigDecimal normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
