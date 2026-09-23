package io.hason.callanalysis.domain.event;

import java.util.Objects;

/** Truy nguyen mot event ve dung dong log goc. */
public record SourceRef(String fileName, int lineNumber, String rawLine) {

    public SourceRef {
        Objects.requireNonNull(fileName, "fileName");
    }

    /** Dang trich dan dung trong report, vi du "callee_endcall.log:142". */
    public String citation() {
        return lineNumber > 0 ? fileName + ":" + lineNumber : fileName;
    }
}
