package io.hason.callanalysis.infrastructure.ai;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dấu vân tay của những gì quyết định cách AI trả lời ngoài code: hai system prompt và hai JSON Schema của
 * structured output. Evaluation Runner in nó vào báo cáo để mỗi con số truy được về đúng phiên bản prompt —
 * Sprint 2 từng có số chính thức đo trước một lần sửa prompt mà báo cáo không cho biết điều đó.
 *
 * Băm nguyên byte của file (SHA-256, 12 ký tự hex đầu): đó đúng là thứ được gửi đi. Repo ép xuống dòng LF
 * qua {@code .gitattributes}, nên cùng nội dung thì cùng dấu trên mọi máy.
 */
public final class AiPromptFingerprint {

    private static final int HEX_CHARS = 12;

    /** Thứ tự cố định: prompt phân tích, schema phân tích, prompt phân loại, schema phân loại. */
    static final List<String> FILES = List.of(
            OpenAiAnalyzer.SYSTEM_PROMPT, OpenAiAnalyzer.SCHEMA,
            OpenAiIntentClassifier.SYSTEM_PROMPT, OpenAiIntentClassifier.SCHEMA);

    private AiPromptFingerprint() {
    }

    /** Tên file (không thư mục) → 12 ký tự hex đầu của SHA-256, theo thứ tự {@link #FILES}. */
    public static Map<String, String> current() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : FILES) {
            out.put(path.substring(path.lastIndexOf('/') + 1), of(read(path)));
        }
        return out;
    }

    /** Một dòng cho báo cáo: {@code analyze-call.system.txt 3f2a…, ai-analysis.schema.json …}. */
    public static String summary() {
        StringBuilder line = new StringBuilder();
        current().forEach((file, hash) -> line.append(line.isEmpty() ? "" : ", ").append(file).append(' ').append(hash));
        return line.toString();
    }

    static String of(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(digest).substring(0, HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM thiếu SHA-256", e);    // mọi JVM chuẩn đều phải có
        }
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Không đọc được " + path, e);
        }
    }
}
