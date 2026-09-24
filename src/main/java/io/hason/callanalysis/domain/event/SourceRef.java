package io.hason.callanalysis.domain.event;

import java.util.Objects;

/** Truy nguyên một event về đúng dòng log gốc. */
public record SourceRef(String fileName, int lineNumber, String rawLine) {

    /**
     * Nguồn của event signaling. Signaling KHÔNG đến từ file mà từ kết quả truy vấn
     * Elasticsearch, nên "số dòng" của nó là THỨ TỰ sự kiện (1, 2, 3…, tức ordinal + 1),
     * không phải số dòng của một file nào.
     *
     * Vì vậy trích dẫn dùng dấu `#` thay cho `:` — `signaling#87` là sự kiện signaling thứ 87.
     * Dạng cũ `signaling.json:87` bị đọc thành "dòng 87 của signaling.json": với 2D9057AA,
     * dòng 87 của file đó là `"service": ...`, còn BYE được trích nằm ở dòng 1 128.
     */
    public static final String SIGNALING = "signaling";

    public SourceRef {
        Objects.requireNonNull(fileName, "fileName");
    }

    /**
     * Dạng trích dẫn dùng trong report: "callee_endcall.log:142" (file, số dòng) hoặc
     * "signaling#87" (sự kiện signaling thứ 87). Không có vị trí thì chỉ nêu nguồn.
     */
    public String citation() {
        if (lineNumber <= 0) {
            return fileName;
        }
        return fileName + (SIGNALING.equals(fileName) ? "#" : ":") + lineNumber;
    }
}
