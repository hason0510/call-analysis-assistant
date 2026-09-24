package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.SourceRef;

/** Một dòng không đọc được. Parser ghi lại rồi đi tiếp, KHÔNG bao giờ throw. */
public record ParseWarning(String fileName, int lineNumber, String reason) {

    /** Vị trí viết theo cùng quy tắc trích dẫn với evidence (SourceRef.citation). */
    public String describe() {
        return new SourceRef(fileName, lineNumber, null).citation() + " — " + reason;
    }
}
