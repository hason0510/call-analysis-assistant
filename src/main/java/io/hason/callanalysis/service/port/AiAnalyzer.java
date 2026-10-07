package io.hason.callanalysis.service.port;

import io.hason.callanalysis.domain.ai.AiAnalysis;
import io.hason.callanalysis.domain.ai.AiContext;
import io.hason.callanalysis.domain.ai.AiUnavailableException;

/**
 * Cổng ra phía AI provider — cùng khuôn mẫu với {@link SignalingSource}. Domain và service
 * không biết provider nào tồn tại; đổi OpenAI sang Ollama chỉ là viết thêm một adapter.
 */
public interface AiAnalyzer {

    /**
     * @throws AiUnavailableException khi không có kết quả dùng được (timeout, lỗi provider,
     *         sai format). Không bao giờ trả null.
     */
    AiAnalysis analyze(AiContext context);
}
