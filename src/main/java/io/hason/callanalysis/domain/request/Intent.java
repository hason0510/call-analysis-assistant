package io.hason.callanalysis.domain.request;

/** Intent của câu hỏi (MVP mục 4.4). */
public enum Intent {

    /** "Phân tích cuộc gọi này giúp mình" → report đầy đủ. */
    ANALYZE_CALL,

    /** "Vì sao bên nhận không nghe được?" → report đầy đủ, phần phân tích ưu tiên khía cạnh được hỏi. */
    ANALYZE_WITH_FOCUS,

    /** "Viết giúp mình một email" → từ chối theo mẫu cố định, không chạy pipeline. */
    OUT_OF_SCOPE
}
