package io.hason.callanalysis.domain.parse;

/** Mot dong khong doc duoc. Parser ghi lai roi di tiep, KHONG bao gio throw. */
public record ParseWarning(String fileName, int lineNumber, String reason) {

    public String describe() {
        return fileName + ":" + lineNumber + " — " + reason;
    }
}
