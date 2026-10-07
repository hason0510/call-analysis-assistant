package io.hason.callanalysis.domain.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Safe AI Context (MVP mục 6.2): câu hỏi của người dùng cùng context ĐÃ chuẩn hoá và ĐÃ làm sạch.
 * MVP mục 3.3: LLM không nhận raw log, chỉ nhận minimum necessary context.
 *
 * Constructor để package-private: chỉ {@link AiContextBuilder} — nơi mọi chuỗi đi qua Sanitizer —
 * tạo được AiContext. Không lời gọi AI nào đi vòng qua được bước làm sạch.
 *
 * Context giữ dạng CÓ KIỂU ({@link Payload}), không phải chuỗi JSON: adapter tự chọn định dạng gửi
 * đi, còn Guardrails lấy đúng phần đã gửi làm căn cứ ({@link #groundingTexts()}, {@link #evidenceIds()}).
 * Hai nơi không thể lệch nhau như khi bên gọi tự truyền một danh sách riêng.
 */
public final class AiContext {

    private final String requestId;
    private final String question;
    private final String focus;
    private final Payload payload;
    private final Map<String, Integer> redactions;

    AiContext(String requestId, String question, String focus, Payload payload, Map<String, Integer> redactions) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.question = question;
        this.focus = focus;
        this.payload = Objects.requireNonNull(payload, "payload");
        // Xếp theo tên mục: Result.findings không giữ thứ tự, mà log cần ra giống nhau mọi lần chạy.
        this.redactions = Collections.unmodifiableMap(new TreeMap<>(redactions));
    }

    public String requestId() {
        return requestId;
    }

    /** Câu hỏi đã làm sạch; có thể null (phân tích không kèm câu hỏi). */
    public String question() {
        return question;
    }

    /** Trọng tâm câu hỏi do Request Parser (T4) trích, đã làm sạch; null khi không có trọng tâm. */
    public String focus() {
        return focus;
    }

    public Payload payload() {
        return payload;
    }

    /**
     * Số giá trị đã thay theo từng mục inventory — chỉ đếm, không giữ giá trị gốc, nên ghi log được.
     * Không gửi cho AI.
     */
    public Map<String, Integer> redactions() {
        return redactions;
    }

    /** ID evidence AI được phép trích (Guardrails G01). */
    public List<String> evidenceIds() {
        return payload.evidence().stream().map(EvidenceItem::id).toList();
    }

    /**
     * Mọi văn bản của context đã gửi AI: số nào xuất hiện ở đây thì AI được phép nhắc lại (G05).
     *
     * KHÔNG gồm câu hỏi: con số trong câu hỏi là lời người dùng kể ("gọi 5 phút mà…"), không phải
     * số liệu đã đo — AI chép lại thành kết luận là tự tạo số liệu (MVP mục 3.3).
     */
    public List<String> groundingTexts() {
        List<String> texts = new ArrayList<>();
        RuleSummary rule = payload.ruleVerdict();
        add(texts, rule.reasoning());
        add(texts, rule.causeBasis());
        payload.evidence().forEach(e -> {
            add(texts, e.time());
            add(texts, e.description());
            add(texts, e.citation());
        });
        payload.metrics().forEach(m -> add(texts, m.value()));
        texts.addAll(payload.dataLimitations());
        payload.taxonomy().forEach(c -> {
            add(texts, c.definition());
            texts.addAll(c.symptoms());
        });
        return List.copyOf(texts);
    }

    private static void add(List<String> texts, String text) {
        if (text != null) {
            texts.add(text);
        }
    }

    /**
     * Phần gửi cho AI, theo đúng thứ tự trường này. Chuỗi thay cho enum để JSON là văn bản thuần,
     * không phụ thuộc cách adapter serialize enum.
     *
     * @param evidence        sự kiện chính của timeline, theo thứ tự thời gian của Evidence Engine
     * @param logs            log nào có mặt — để AI biết đang thiếu gì khi kết luận UNKNOWN
     * @param dataLimitations đúng danh sách sẽ in ở mục "Giới hạn dữ liệu" của report
     */
    public record Payload(RuleSummary ruleVerdict, List<EvidenceItem> evidence, List<MetricItem> metrics,
                          List<LogFile> logs, List<String> dataLimitations, List<CategoryItem> taxonomy) {

        public Payload {
            Objects.requireNonNull(ruleVerdict, "ruleVerdict");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            logs = logs == null ? List.of() : List.copyOf(logs);
            dataLimitations = dataLimitations == null ? List.of() : List.copyOf(dataLimitations);
            taxonomy = taxonomy == null ? List.of() : List.copyOf(taxonomy);
        }
    }

    /** Kết luận của rule — mốc đối chiếu. issueCategory là {@link AiAnalysis#NO_ISSUE} khi không có vấn đề. */
    public record RuleSummary(String verdict, boolean qualityFlag, String issueCategory, String confidence,
                              String reasoning, String causeBasis) {}

    /** @param time mốc tuyệt đối (ISO-8601) hoặc tương đối "+123ms (tương đối)" — hai loại không so thứ tự được */
    public record EvidenceItem(String id, String source, String leg, String time, String description,
                               String citation) {}

    /** @param value dạng hiển thị của report: "63 ms", hoặc "N/A (lý do)" — không bao giờ là 0 thay cho N/A */
    public record MetricItem(String name, String value, String source) {}

    /** @param timeBase ABSOLUTE (signaling, end call log) hoặc RELATIVE (WebRTC log, không có giờ tuyệt đối) */
    public record LogFile(String file, String source, String leg, String timeBase) {}

    public record CategoryItem(String id, String definition, List<String> symptoms, boolean validated) {

        public CategoryItem {
            symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        }
    }
}
