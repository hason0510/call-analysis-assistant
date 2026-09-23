package io.hason.callanalysis.domain.timeline;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Xac dinh file log thuoc ve ben nao.
 *
 * Ten file khong dang tin: data mau co `calleer_webrtc.log` (thua chu 'e') ma thuc chat
 * la log cua CALLER. Cach xac dinh dung la doi chieu noi dung:
 *
 *   1. End call log tu khai bao — ban ghi #H1 co cot `role` (caller/callee) va `platform`.
 *   2. WebRTC log khong co `role`, nhung format cua no cho biet nen tang
 *      (Format 1 = iOS, Format 2 = Android). Ghep platform do voi platform cua end call log
 *      la ra chu so huu.
 *   3. Hai ben cung nen tang thi khong phan biet duoc — luc do moi quay ve goi y tu ten file,
 *      va danh dau do tin cay thap hon.
 */
public class LegCorrelator {

    private static final String ROLE = "role";
    private static final String PLATFORM = "platform";

    /** Ket qua: leg cua tung file, va do tin cay cua ket luan do. */
    public record FileLeg(String fileName, Leg leg, String platform,
                          RelativeTrack.LegConfidence confidence) {}

    public Map<String, FileLeg> correlate(List<CanonicalEvent> events) {
        Map<String, FileLeg> endCallFiles = legsFromEndCallMetadata(events);
        Map<String, FileLeg> result = new LinkedHashMap<>(endCallFiles);

        for (Map.Entry<String, String> entry : webRtcPlatformByFile(events).entrySet()) {
            String fileName = entry.getKey();
            String platform = entry.getValue();
            result.put(fileName, resolveWebRtcFile(fileName, platform, endCallFiles));
        }
        return Map.copyOf(result);
    }

    private FileLeg resolveWebRtcFile(String fileName, String platform,
                                      Map<String, FileLeg> endCallFiles) {
        List<FileLeg> samePlatform = endCallFiles.values().stream()
                .filter(f -> platform != null && platform.equalsIgnoreCase(f.platform()))
                .toList();

        if (samePlatform.size() == 1) {
            return new FileLeg(fileName, samePlatform.getFirst().leg(), platform,
                    RelativeTrack.LegConfidence.MATCHED_BY_PLATFORM);
        }

        Leg hint = legFromFileName(fileName);
        return new FileLeg(fileName, hint, platform,
                hint == Leg.UNKNOWN
                        ? RelativeTrack.LegConfidence.UNRESOLVED
                        : RelativeTrack.LegConfidence.FILE_NAME_ONLY);
    }

    /** Ban ghi #H1 cua end call log tu khai bao role va platform — nguon chac chan nhat. */
    private Map<String, FileLeg> legsFromEndCallMetadata(List<CanonicalEvent> events) {
        Map<String, FileLeg> byFile = new LinkedHashMap<>();
        for (CanonicalEvent e : events) {
            if (e.source() != LogSource.ENDCALL || e.type() != EventType.CALL_METADATA) {
                continue;
            }
            String fileName = e.sourceRef().fileName();
            if (byFile.containsKey(fileName)) {
                continue;
            }
            Leg leg = legFromRole(e.attribute(ROLE));
            if (leg == Leg.UNKNOWN) {
                continue;
            }
            byFile.put(fileName, new FileLeg(fileName, leg, e.attribute(PLATFORM),
                    RelativeTrack.LegConfidence.MATCHED_BY_PLATFORM));
        }
        return byFile;
    }

    private Map<String, String> webRtcPlatformByFile(List<CanonicalEvent> events) {
        Map<String, String> byFile = new LinkedHashMap<>();
        for (CanonicalEvent e : events) {
            if (e.source() == LogSource.WEBRTC) {
                byFile.putIfAbsent(e.sourceRef().fileName(), e.attribute(PLATFORM));
            }
        }
        return byFile;
    }

    private static Leg legFromRole(String role) {
        if (role == null) {
            return Leg.UNKNOWN;
        }
        return switch (role.toLowerCase()) {
            case "caller" -> Leg.CALLER;
            case "callee" -> Leg.CALLEE;
            default -> Leg.UNKNOWN;
        };
    }

    /** Chi dung khi khong con cach nao khac. `calleer_webrtc.log` se bi doan SAI o day. */
    private static Leg legFromFileName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.startsWith("caller")) {
            return Leg.CALLER;
        }
        if (lower.startsWith("callee")) {
            return Leg.CALLEE;
        }
        return Leg.UNKNOWN;
    }

    public static Optional<Leg> legOfFile(Map<String, FileLeg> correlation, String fileName) {
        FileLeg fileLeg = correlation.get(fileName);
        return fileLeg == null ? Optional.empty() : Optional.of(fileLeg.leg());
    }
}
