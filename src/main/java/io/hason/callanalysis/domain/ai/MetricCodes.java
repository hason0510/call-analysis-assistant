package io.hason.callanalysis.domain.ai;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mã chỉ số (Sprint 3 T1a): AI nhắc một chỉ số bằng mã {@code {PACKET_LOSS_CALLER}}, code thay bằng giá trị
 * kèm tên chỉ số: {@code "1.83 % [Packet loss (caller)]"}.
 *
 * Trước đây AI tự chép số vào câu, và hai kiểu sai lọt ra: chép lệch số (làm tròn, đổi đơn vị, bịa — G05 chặn,
 * report lùi về rule) và chép ĐÚNG số nhưng gán SAI nghĩa (G05 không bắt được: {@code 271D1FAF}, tỉ lệ mất gói
 * 11.3208 % của một mẫu stats bị gọi là mất gói của cả cuộc, thật ra 1.83 %). Khi tên và giá trị do code điền,
 * cả hai kiểu đều không xảy ra được với chỉ số trong bảng; nếu AI chọn nhầm chỉ số thì người đọc thấy ngay tên
 * chỉ số sai chỗ, thay vì một con số trông hợp lý.
 *
 * Mã là tên chỉ số ({@code MetricKey}) kèm leg, KHÔNG đánh số {@code M01, M02…} như kế hoạch ban đầu: thử với AI
 * thật (2026-10-10), context có {@code M20} (trạng thái ICE của callee) làm AI trích evidence {@code EV20} không
 * tồn tại ở 5/5 lần trên {@code 2D9057AA} — code trước T1a (không có mã) thì không. Mã theo tên không có số để
 * lẫn với evidence ID, và AI chọn chỉ số theo nghĩa thay vì theo vị trí trong danh sách.
 *
 * Giá trị đứng trước, tên trong ngoặc vuông: AI hay viết "Thời gian đổ chuông là {RINGING_TIME}", nên dạng
 * "tên = giá trị" cho ra câu "Thời gian đổ chuông là Thời gian đổ chuông = 5987 ms".
 */
public final class MetricCodes {

    /** Mã trong chữ AI: {@code {MOS_CALLER}}. Mã không có trong bảng thì Guardrails chặn (G05). */
    private static final Pattern CODE = Pattern.compile("\\{([A-Z][A-Z0-9_]*)}");

    private final Map<String, String> texts;

    private MetricCodes(Map<String, String> texts) {
        this.texts = texts;
    }

    public static MetricCodes of(CallMetrics metrics) {
        Map<String, String> texts = new LinkedHashMap<>();
        metrics.metrics().forEach(m -> texts.put(id(m), shown(m)));
        return new MetricCodes(texts);
    }

    /**
     * Mã của một chỉ số: tên {@code MetricKey}, thêm leg khi chỉ số tính riêng từng bên — cùng quy tắc với
     * {@link CallMetric#label()}, nên mỗi dòng của bảng chỉ số có một mã riêng.
     */
    public static String id(CallMetric metric) {
        return metric.key().isPerLeg() && metric.leg() != Leg.UNKNOWN
                ? metric.key().name() + "_" + metric.leg().name()
                : metric.key().name();
    }

    /** Chữ thay cho mã: {@code "5987 ms [Thời gian đổ chuông]"}, chỉ số proxy ghi rõ là proxy (MVP mục 4.3). */
    static String shown(CallMetric metric) {
        return metric.value().display() + " [" + metric.label() + (metric.key().isProxy() ? ", proxy" : "") + "]";
    }

    /** Như cột "Giá trị" của report: N/A kèm lý do, và chỉ số proxy ghi rõ là proxy (MVP mục 4.3). */
    static String display(CallMetric metric) {
        return metric.value().display() + (metric.key().isProxy() ? " [proxy]" : "");
    }

    public Set<String> ids() {
        return texts.keySet();
    }

    /** Mã nhắc trong một đoạn chữ, theo thứ tự xuất hiện, kể cả mã lặp lại. */
    public static List<String> codesIn(String text) {
        List<String> codes = new ArrayList<>();
        if (text != null) {
            Matcher m = CODE.matcher(text);
            while (m.find()) {
                codes.add(m.group(1));
            }
        }
        return codes;
    }

    /** Thay mọi mã có trong bảng; mã lạ để nguyên (Guardrails đã chặn câu trả lời có mã lạ trước bước này). */
    public String expand(String text) {
        if (text == null) {
            return null;
        }
        return CODE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(texts.getOrDefault(m.group(1), m.group())));
    }

    /** Bản có tên và giá trị chỉ số thay cho mã ở mọi đoạn chữ AI viết; các trường khác giữ nguyên. */
    public AiAnalysis expand(AiAnalysis ai) {
        return new AiAnalysis(ai.verdict(), ai.qualityFlag(), ai.issueCategory(), ai.confidenceLevel(),
                expand(ai.summary()), ai.evidenceIds(), expand(ai.analysis()),
                ai.suggestions().stream().map(this::expand).toList(), ai.usage());
    }
}
