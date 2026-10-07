package io.hason.callanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.signaling.RawSignalingRecord;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import io.hason.callanalysis.infrastructure.report.ReportSchemaValidator;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import io.hason.callanalysis.service.port.AiAnalyzer;
import io.hason.callanalysis.service.port.IntentClassifier;
import io.hason.callanalysis.service.port.SignalingSource;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * Dựng cả luồng Chat API bằng code thật, chỉ thay hai cổng ra: signaling (không cần ES) và AI (không
 * gọi mạng). Dùng chung cho test service và test controller.
 */
public final class ChatPipelineFixture {

    public static final String CALL_ID = "2D9057AA-C496-48B2-946A-98FA2896D086";

    /** Phân loại câu hỏi bằng AI "chưa cấu hình" → luôn đi đường lui từ khoá, tất định. */
    public static final IntentClassifier KEYWORDS_ONLY = (id, q) -> {
        throw new AiUnavailableException(AiUnavailableException.Reason.NOT_CONFIGURED, "test");
    };

    /** Ghi lại Call-ID được truy vấn — để kiểm câu OUT_OF_SCOPE không chạy pipeline. */
    public static final class RecordingSource implements SignalingSource {
        public final List<String> queried = new ArrayList<>();

        @Override
        public SignalingFetch fetchByCallId(String callId) {
            queried.add(callId);
            return new SignalingFetch(callId, healthySignaling(callId), false, 4, 4);
        }
    }

    private ChatPipelineFixture() {
    }

    public static ChatAnalysisService chat(SignalingSource source, AiAnalyzer ai) {
        return chat(source, ai, DataSize.ofMegabytes(20));
    }

    /** @param maxFileSize phải trùng giới hạn của bên đọc file — khi chạy thật hai bên đọc cùng một khoá cấu hình */
    public static ChatAnalysisService chat(SignalingSource source, AiAnalyzer ai, DataSize maxFileSize) {
        SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(
                new SensitiveDataInventoryLoader().load(), new Pseudonymizer(new byte[32]));
        return new ChatAnalysisService(
                new RequestParserService(KEYWORDS_ONLY, sanitizer),
                new AnalyzeCallService(new CallLogNormalizationService(source, maxFileSize), new TaxonomyLoader(), sanitizer),
                new AiVerdictService(ai, sanitizer),
                new ReportSchemaValidator(new ObjectMapper()));
    }

    private static RawSignalingRecord record(String callId, int ordinal, String ts, String cmd, String user) {
        return new RawSignalingRecord(callId, ordinal, ts, "SVC1", "INFO", cmd,
                "csid1", "req-" + ordinal, user, "sess1", "MOBIFONE", "AS131429", "VN", null);
    }

    static List<RawSignalingRecord> healthySignaling(String callId) {
        return List.of(
                record(callId, 0, "2026-09-21T08:00:00.000000000Z", "INIT_CALL", "UCALLERUSER1"),
                record(callId, 1, "2026-09-21T08:00:01.000000000Z", "INVITE", "UCALLERUSER1"),
                record(callId, 2, "2026-09-21T08:00:02.000000000Z", "OK_ACK_OK", "UCALLERUSER1"),
                record(callId, 3, "2026-09-21T08:00:30.000000000Z", "BYE", "UCALLEEUSER2"));
    }

    /** End call log tối thiểu đúng định dạng thật (dòng đặc tả #H1 có cột #tag). */
    public static List<String> endCallLog(String role) {
        return List.of(
                "#H1\t#ts\t#tag\tcallId\trole\tplatform",
                "1\t1789700842905\tinfo\t" + CALL_ID + "\t" + role + "\tios");
    }
}
