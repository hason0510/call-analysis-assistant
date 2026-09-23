package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Kết quả parse một file: những event đọc được, và những dòng không đọc được.
 *
 * MVP mục 3.3: "Raw log có thể malformed, thiếu field, trùng lặp, lệch thứ tự,
 * sai tên file; parser không được crash." Vì vậy lỗi được trả về như dữ liệu,
 * không ném ra ngoài.
 */
public record ParseResult(List<CanonicalEvent> events, List<ParseWarning> warnings) {

    public ParseResult {
        events = events == null ? List.of() : List.copyOf(events);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ParseResult empty() {
        return new ParseResult(List.of(), List.of());
    }

    public static ParseResult of(List<CanonicalEvent> events, List<ParseWarning> warnings) {
        return new ParseResult(events, warnings);
    }

    public ParseResult merge(ParseResult other) {
        List<CanonicalEvent> e = new ArrayList<>(events);
        List<ParseWarning> w = new ArrayList<>(warnings);
        e.addAll(other.events());
        w.addAll(other.warnings());
        return new ParseResult(e, w);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
