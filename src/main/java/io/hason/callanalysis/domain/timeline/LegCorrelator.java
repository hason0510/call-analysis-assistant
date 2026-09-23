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
 * Xác định file log thuộc về bên nào.
 *
 * Tên file không đáng tin: data mẫu có `calleer_webrtc.log` (thừa chữ 'e') mà thực chất
 * là log của CALLER. Cách xác định đúng là đối chiếu nội dung:
 *
 *   1. End call log tự khai báo — bản ghi #H1 có cột `role` (caller/callee) và `platform`.
 *   2. WebRTC log không có `role`, nhưng format của nó cho biết nền tảng
 *      (Format 1 = iOS, Format 2 = Android). Ghép platform đó với platform của end call log
 *      là ra chủ sở hữu.
 *   3. Hai bên cùng nền tảng thì không phân biệt được — lúc đó mới quay về gợi ý từ tên file,
 *      và đánh dấu độ tin cậy thấp hơn.
 */
public class LegCorrelator {

    private static final String ROLE = "role";
    private static final String PLATFORM = "platform";

    /** Kết quả: leg của từng file, và độ tin cậy của kết luận đó. */
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

    /** Bản ghi #H1 của end call log tự khai báo role và platform — nguồn chắc chắn nhất. */
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

    /** Chỉ dùng khi không còn cách nào khác. `calleer_webrtc.log` sẽ bị đoán SAI ở đây. */
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
