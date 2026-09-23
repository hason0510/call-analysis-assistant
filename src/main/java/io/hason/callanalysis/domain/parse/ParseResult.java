package io.hason.callanalysis.domain.parse;

import io.hason.callanalysis.domain.event.CanonicalEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Ket qua parse mot file: nhung event doc duoc, va nhung dong khong doc duoc.
 *
 * MVP muc 3.3: "Raw log co the malformed, thieu field, trung lap, lech thu tu,
 * sai ten file; parser khong duoc crash." Vi vay loi duoc tra ve nhu du lieu,
 * khong nem ra ngoai.
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
