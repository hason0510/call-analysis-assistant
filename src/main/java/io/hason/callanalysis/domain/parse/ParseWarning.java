package io.hason.callanalysis.domain.parse;

/** Một dòng không đọc được. Parser ghi lại rồi đi tiếp, KHÔNG bao giờ throw. */
public record ParseWarning(String fileName, int lineNumber, String reason) {

    public String describe() {
        return fileName + ":" + lineNumber + " — " + reason;
    }
}
