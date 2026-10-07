package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.request.AiIntent;
import io.hason.callanalysis.domain.request.SafeQuestion;

/**
 * Cổng ra tới AI phân loại câu hỏi (Request Parser, MVP mục 3.2: "Hiểu câu hỏi của người dùng → AI").
 * Cùng khuôn với {@link AiAnalyzer}: chỉ nhận câu hỏi đã làm sạch, đổi provider chỉ cần adapter mới.
 */
public interface IntentClassifier {

    /**
     * @throws AiUnavailableException khi không có kết quả dùng được. Không bao giờ trả null.
     */
    AiIntent classify(String requestId, SafeQuestion question);
}
