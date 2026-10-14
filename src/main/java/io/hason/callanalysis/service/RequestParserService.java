package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.TokenUsage;
import io.hason.callanalysis.domain.request.AiIntent;
import io.hason.callanalysis.domain.request.CallIdExtractor;
import io.hason.callanalysis.domain.request.GarbledText;
import io.hason.callanalysis.domain.request.IntentClassification;
import io.hason.callanalysis.domain.request.KeywordIntentClassifier;
import io.hason.callanalysis.domain.request.ParsedRequest;
import io.hason.callanalysis.domain.request.SafeQuestion;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.service.port.IntentClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Request Parser (MVP mục 6.1 T4): câu hỏi → intent, trọng tâm, Call-ID.
 *
 * - Call-ID: regex, bằng code (MVP mục 3.2 — tính được bằng code thì không giao AI).
 * - Intent + trọng tâm: AI trên câu hỏi ĐÃ làm sạch; đầu ra AI qua kiểm và Output Sanitizer.
 * - AI lỗi hay trả sai thì lùi về {@link KeywordIntentClassifier}: Request Parser không bao giờ làm
 *   hỏng pipeline (MVP mục 3.3), giống fallback của bước phân tích.
 *
 * Log chỉ gồm request_id, result_status, fallback_reason — không log câu hỏi.
 */
@Service
public class RequestParserService {

    private static final Logger log = LoggerFactory.getLogger(RequestParserService.class);

    private final IntentClassifier classifier;
    private final SensitiveDataSanitizer sanitizer;
    private final KeywordIntentClassifier keywords = new KeywordIntentClassifier();

    public RequestParserService(IntentClassifier classifier, SensitiveDataSanitizer sanitizer) {
        this.classifier = classifier;
        this.sanitizer = sanitizer;
    }

    public ParsedRequest parse(String requestId, String question) {
        CallIdExtractor.Result callId = CallIdExtractor.extract(question);
        List<String> notes = new ArrayList<>();
        if (callId.note() != null) {
            notes.add(callId.note());
        }
        boolean garbled = GarbledText.isGarbled(question);
        if (garbled) {
            notes.add(GarbledText.NOTE);
        }
        SafeQuestion safe = SafeQuestion.of(question, sanitizer);
        if (safe.isBlank()) {
            // Chỉ đính kèm log: không có gì để AI hiểu, khỏi tốn một lời gọi.
            return result(safe, keywords.classify(null), callId, ParsedRequest.Source.KEYWORD, null, notes, null,
                    garbled);
        }

        AiIntent ai;
        try {
            ai = classifier.classify(requestId, safe);
        } catch (AiUnavailableException e) {
            return fallback(requestId, safe, callId, e.reason().name(), notes, null, garbled);
        } catch (RuntimeException e) {
            return fallback(requestId, safe, callId, "UNEXPECTED_ERROR", notes, null, garbled);
        }
        Optional<IntentClassification> checked = IntentClassification.fromAi(sanitizeOutput(ai));
        if (checked.isEmpty()) {
            // AI đã trả lời (đã tốn token) nhưng câu trả lời không dùng được: vẫn ghi số token.
            return fallback(requestId, safe, callId, "INVALID_INTENT", notes, ai.usage(), garbled);
        }
        log.atInfo().addKeyValue("request_id", requestId).addKeyValue("result_status", "OK")
                .addKeyValue("intent", checked.get().intent()).addKeyValue("garbled", garbled).log("Request parsed");
        return result(safe, checked.get(), callId, ParsedRequest.Source.AI, null, notes, ai.usage(), garbled);
    }

    private ParsedRequest fallback(String requestId, SafeQuestion safe, CallIdExtractor.Result callId,
                                   String reason, List<String> notes, TokenUsage usage, boolean garbled) {
        IntentClassification byKeywords = keywords.classify(safe.text());
        log.atWarn().addKeyValue("request_id", requestId).addKeyValue("result_status", "FALLBACK")
                .addKeyValue("fallback_reason", reason).addKeyValue("intent", byKeywords.intent())
                .addKeyValue("garbled", garbled).log("Request parsed");
        return result(safe, byKeywords, callId, ParsedRequest.Source.KEYWORD, reason, notes, usage, garbled);
    }

    private static ParsedRequest result(SafeQuestion safe, IntentClassification c, CallIdExtractor.Result callId,
                                        ParsedRequest.Source source, String fallbackReason, List<String> notes,
                                        TokenUsage usage, boolean garbled) {
        return new ParsedRequest(safe.text(), c.intent(), c.focus(), callId.callId(), source, fallbackReason, notes,
                usage, garbled);
    }

    /** Câu hỏi đã sạch nhưng model vẫn có thể bịa ra một chuỗi nhạy cảm trong trọng tâm (ca S07). */
    private AiIntent sanitizeOutput(AiIntent ai) {
        if (ai == null || ai.focus() == null) {
            return ai;
        }
        return new AiIntent(ai.intent(), sanitizer.sanitizeQuestion(ai.focus()).text(), ai.usage());
    }
}
