package io.hason.callanalysis.domain.event;

import java.util.Objects;

/** Truy nguyên một event về đúng dòng log gốc. */
public record SourceRef(String fileName, int lineNumber, String rawLine) {

    public SourceRef {
        Objects.requireNonNull(fileName, "fileName");
    }

    /** Dạng trích dẫn dùng trong report, ví dụ "callee_endcall.log:142". */
    public String citation() {
        return lineNumber > 0 ? fileName + ":" + lineNumber : fileName;
    }
}
