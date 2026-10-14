package io.hason.callanalysis.domain.request;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Câu hỏi bị hỏng mã hoá trên đường gửi (MVP mục 7.1 T4 — thông báo lỗi thân thiện).
 *
 * Ngày 2026-10-06, `curl -F "message=Máy … gọi lỗi"` trong Git Bash gửi byte bảng mã ANSI: server nhận
 * `M?y … g?i l?i` (dấu ? đầu là ký tự thay thế U+FFFD), AI xếp câu hỏi OUT_OF_SCOPE và người dùng
 * nhận câu từ chối mà không biết vì sao (docs/ket-qua-demo-sprint-2.md mục 3). Tham số `--question` của CLI cũng mất dấu thành `?`.
 * Chữ đã thành `?` thì không khôi phục được — chỉ phát hiện để báo người dùng gửi lại.
 *
 * Ba dấu hiệu, mỗi dấu hiệu đều không xuất hiện trong câu tiếng Việt gõ đúng:
 * - ký tự thay thế U+FFFD: byte không phải UTF-8;
 * - `?` nằm giữa chữ cái (`g?i`, `???c`) từ hai chỗ trở lên: chữ có dấu bị thay. Dấu hỏi thật đứng
 *   cuối từ (`sao?`, `sao???`), nên không tính;
 * - UTF-8 bị đọc như Latin-1 / Windows-1252 (`Ã¡` thay cho `á`, `Ä‘` thay cho `đ`) từ hai chỗ trở lên.
 *
 * Ngưỡng hai chỗ: một `?` lẻ giữa chữ có thể là gõ nhầm; câu hỏng mã hoá thật thường có nhiều chỗ
 * (câu ở trên có ba).
 */
public final class GarbledText {

    /** Dấu `?` liền trước một chữ cái mà đứng sau chữ cái hoặc đầu từ: chữ có dấu đã bị thay. */
    private static final Pattern IN_WORD_QUESTION_MARK = Pattern.compile("(?<=\\p{L})\\?+(?=\\p{L})|(?<![\\p{L}?])\\?+(?=\\p{L})");

    /**
     * Byte đầu của chữ Việt trong UTF-8 (0xC3, 0xC4, 0xC6, 0xE1…) đọc theo Latin-1 / Windows-1252 thành
     * Ã Ä Æ á…, theo sau là byte tiếp nối 0x80-0xBF — Latin-1 là U+0080-U+00BF, Windows-1252 đổi
     * 0x80-0x9F thành dấu nháy cong, gạch ngang, €… Câu tiếng Việt thật không có chữ nào đứng ngay
     * trước các ký tự này, trừ dấu nháy đóng — nên dấu nháy chỉ tính sau Ã Ä Æ, không tính sau á â.
     */
    private static final Pattern MOJIBAKE = Pattern.compile(
            "[\\u00C2-\\u00C6][\\u0080-\\u00BF\\u0152\\u0153\\u0160\\u0161\\u0178\\u017D\\u017E\\u0192\\u02C6\\u02DC"
                    + "\\u2013\\u2014\\u2018-\\u201E\\u2020-\\u2022\\u2026\\u2030\\u2039\\u203A\\u20AC\\u2122]"
                    + "|[\\u00E1\\u00E2][\\u0080-\\u00BF]");

    /** U+FFFD: bộ giải mã gặp byte không phải UTF-8. Viết bằng mã số, không viết ký tự vào mã nguồn. */
    private static final int REPLACEMENT_CHARACTER = 0xFFFD;

    /** Câu báo cho người dùng — cố định, không do AI viết. */
    public static final String NOTE = "Câu hỏi có ký tự bị hỏng mã hoá (chữ có dấu thành \"?\" hoặc ký tự lạ),"
            + " thường do gửi từ dòng lệnh Windows: hệ thống có thể hiểu sai câu hỏi. Hãy gõ lại trên Web UI,"
            + " hoặc gửi câu hỏi từ file lưu bằng UTF-8";

    private GarbledText() {
    }

    public static boolean isGarbled(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // NFC trước: macOS và một số bộ gõ gửi chữ dạng tổ hợp ("e" + dấu rời U+0302). Dấu rời không phải
        // chữ cái (\p{L}), nên ở dạng tổ hợp phép đếm "?" giữa chữ cái bị lệch.
        text = Normalizer.normalize(text, Normalizer.Form.NFC);
        return text.indexOf(REPLACEMENT_CHARACTER) >= 0
                || count(IN_WORD_QUESTION_MARK, text) >= 2
                || count(MOJIBAKE, text) >= 2;
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
