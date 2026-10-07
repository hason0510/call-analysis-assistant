package io.hason.callanalysis.domain.request;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phân loại intent bằng từ khoá — đường lui khi AI lỗi (docs/sprint-2-plan.md, T4), và mốc so sánh
 * khi đo Intent Accuracy. Tất định, không I/O.
 *
 * Luật theo thứ tự:
 * 1. Nhờ làm một VIỆC khác (viết email, làm thơ, dịch, lập trình…) → OUT_OF_SCOPE, kể cả khi câu có
 *    nhắc tới cuộc gọi: "viết email báo lỗi cuộc gọi" vẫn là viết email. Cùng định nghĩa với prompt AI.
 * 2. Có dấu hiệu chắc chắn nói về cuộc gọi (cuộc gọi, log, ICE, bên nhận, Call-ID…) → trong phạm vi.
 * 3. Không có, mà có CHỦ ĐỀ khác (thời tiết, chứng khoán, du lịch…) → OUT_OF_SCOPE.
 * 4. Không có, mà có từ phân tích chung (phân tích, lỗi, nghe, mạng…) → trong phạm vi.
 * 5. Không có gì → OUT_OF_SCOPE: không nhận ra câu hỏi về cuộc gọi thì không phân tích bừa.
 *
 * Trong phạm vi: câu nêu một KHÍA CẠNH cụ thể — một bên (bên nhận), một triệu chứng (rè, rớt,
 * không nghe), một giai đoạn (đổ chuông, kết nối), một chỉ số (MOS, jitter) — là ANALYZE_WITH_FOCUS;
 * câu chung chung ("có lỗi gì không", "vì sao thất bại") là ANALYZE_CALL.
 *
 * So khớp trên chữ đã bỏ dấu: người dùng hay gõ không dấu ("vi sao ben nhan khong nghe duoc").
 * Cái giá là vài từ trùng khi bỏ dấu ("rè"/"rẻ"); chấp nhận được vì đây là đường lui.
 */
public class KeywordIntentClassifier {

    private static final Pattern STRONG = phrases(
            "cuoc goi", "cuoc thoai", "goi dien", "call", "call id", "callid", "log", "sip", "voip", "webrtc",
            "ice", "turn", "stun", "signaling", "mos", "jitter", "rtt", "packet", "packet loss", "goi tin",
            "do chuong", "bat may", "nhac may", "cup may", "ben nhan", "ben goi", "nguoi nhan", "nguoi goi",
            "caller", "callee", "endcall", "invite", "bye");

    /**
     * Việc khác: thắng cả dấu hiệu cuộc gọi. Chỉ cụm từ đủ đặc trưng — "dịch" đứng riêng trùng
     * "dịch vụ", "truyện" trùng "truyền" khi bỏ dấu.
     */
    private static final Pattern OTHER_TASK = phrases(
            "email", "e-mail", "mail", "viet thu", "viet bai", "viet code", "viet ham", "soan thao",
            "soan van ban", "bai tho", "lam tho", "dich sang", "dich giup", "dich doan", "dich cau", "phien dich",
            "ke chuyen", "doc truyen", "truyen ngan", "bai hat", "lap trinh", "giai toan", "bai tap", "slide",
            "cv", "don xin", "hop dong");

    /** Chủ đề khác: chỉ quyết định khi câu không có dấu hiệu cuộc gọi nào. */
    private static final Pattern OTHER_TOPIC = phrases(
            "viet giup", "viet cho", "thoi tiet", "nau an", "mon an", "cong thuc", "nghe nhac", "python",
            "javascript", "tin tuc", "chung khoan", "co phieu", "gia vang", "bitcoin", "du lich", "khach san",
            "ve may bay", "phim", "bong da", "thu do");

    private static final Pattern GENERAL = phrases(
            "phan tich", "kiem tra", "xem giup", "loi", "bi loi", "van de", "su co", "nguyen nhan", "chat luong",
            "ket noi", "mang", "wifi", "4g", "5g", "nghe", "tieng", "am thanh", "thanh cong", "that bai",
            "fail", "video", "camera");

    private static final Pattern ASPECT = phrases(
            "khong nghe", "nghe khong", "nghe bi", "re", "giat", "tre", "do tre", "lag", "vong", "vang", "echo",
            "mat tieng", "khong co tieng", "im lang", "tieng", "am thanh", "rot", "ngat", "tu tat", "bi cup",
            "cup may", "khong goi duoc", "khong goi dc", "khong ket noi", "khong do chuong", "khong bat may",
            "khong nhac may", "do chuong", "bat may", "nhac may", "thiet lap", "ket noi", "cham", "mang", "wifi", "4g", "5g", "packet",
            "packet loss", "mat goi", "jitter", "rtt", "mos", "ice", "turn", "stun", "signaling", "ben nhan",
            "ben goi", "nguoi nhan", "nguoi goi", "caller", "callee", "video", "camera", "hinh");

    /** Lời dẫn đầu câu, không phải nội dung trọng tâm: "Cho mình hỏi vì sao …" → "…". */
    private static final Pattern LEADING = Pattern.compile(
            "^(?:(?:cho\\s+(?:mình|minh|tôi|toi|em|anh|chị|chi)\\s+(?:hỏi|hoi)|(?:bạn|ban)\\s+(?:ơi|oi))[\\s,:]*)?"
                    + "(?:(?:vì\\s+sao|vi\\s+sao|tại\\s+sao|tai\\s+sao|sao|why)\\s+)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Tiểu từ và dấu câu cuối câu: "… vậy?" → "…". */
    private static final Pattern TRAILING = Pattern.compile(
            "(?:\\s+(?:vậy|vay|thế|the|nhỉ|nhi|nhé|nhe|à|ạ|hả|ha))*[\\s?.!]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public IntentClassification classify(String question) {
        if (question == null || question.isBlank()) {
            // Chỉ đính kèm log, không gõ gì: người dùng muốn phân tích cuộc gọi đó.
            return new IntentClassification(Intent.ANALYZE_CALL, null);
        }
        String folded = fold(question);
        boolean strong = STRONG.matcher(folded).find() || CallIdExtractor.extract(question).callId() != null;
        boolean inScope = !OTHER_TASK.matcher(folded).find() && (strong
                || (!OTHER_TOPIC.matcher(folded).find() && GENERAL.matcher(folded).find()));
        if (!inScope) {
            return new IntentClassification(Intent.OUT_OF_SCOPE, null);
        }
        if (ASPECT.matcher(folded).find()) {
            String focus = focusOf(question);
            if (focus != null) {
                return new IntentClassification(Intent.ANALYZE_WITH_FOCUS, focus);
            }
        }
        return new IntentClassification(Intent.ANALYZE_CALL, null);
    }

    /** Call-ID kèm chữ dẫn ("Call", "Call-ID:") — đã trích riêng, không phải khía cạnh được hỏi. */
    private static final Pattern CALL_ID = Pattern.compile(
            "(?:call[\\s-]*id\\s*:?|call|cuộc\\s+gọi|cuoc\\s+goi)?\\s*" + CallIdExtractor.UUID.pattern(),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Trọng tâm = câu hỏi bỏ Call-ID, lời dẫn và tiểu từ cuối, giữ nguyên chữ người dùng gõ. */
    static String focusOf(String question) {
        String withoutCallId = CALL_ID.matcher(question).replaceAll(" ").replaceAll("\\s+", " ").strip();
        String focus = TRAILING.matcher(LEADING.matcher(withoutCallId).replaceFirst("")).replaceFirst("").strip();
        if (focus.isEmpty()) {
            return null;
        }
        return focus.length() <= IntentClassification.MAX_FOCUS_LENGTH ? focus
                : focus.substring(0, IntentClassification.MAX_FOCUS_LENGTH).strip();
    }

    /** Chữ thường, bỏ dấu tiếng Việt, gộp khoảng trắng: "Vì sao bên nhận…" → "vi sao ben nhan…". */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").replace('đ', 'd').replaceAll("\\s+", " ");
    }

    /** Một cụm từ khớp nguyên từ: "ice" không khớp "service", "re" không khớp "free". */
    private static Pattern phrases(String... phrases) {
        String alternation = List.of(phrases).stream()
                .map(p -> Pattern.quote(p).replace(" ", "\\E\\s+\\Q"))
                .collect(Collectors.joining("|"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternation + ")(?![\\p{L}\\p{N}])");
    }
}
