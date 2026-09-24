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
 *   1. End call log tự khai báo — bản ghi `info` có cột `role` (caller/callee) và `platform`.
 *   2. WebRTC log tự cho biết VAI qua SDP: caller tạo offer, callee tạo answer, nên dòng
 *      `DoSetLocalDescription: offer` / `answer` đầu tiên quyết định leg. Trên data mẫu,
 *      22/27 file có dòng này và nó khớp 100% với vai DTLS (offer ↔ server, answer ↔ client).
 *   3. File không có SDP (cuộc gọi dừng trước khi tạo offer/answer): ghép nền tảng của log
 *      (Format 1 = iOS, Format 2 = Android) với nền tảng của end call log. Chỉ dùng khi có
 *      ĐÚNG MỘT end call log cùng nền tảng.
 *   4. Không cách nào ở trên dùng được thì quay về gợi ý từ tên file, độ tin cậy thấp hơn.
 *
 * Luật 2 phải đứng TRƯỚC luật 3. Luật 3 giả định leg kia khác nền tảng, nhưng nếu leg kia
 * không có end call log thì không biết nền tảng của nó: C8CF631E chỉ có end call log của
 * caller (iOS) trong khi cả hai file WebRTC đều iOS, và luật 3 từng gán nhầm cả hai file
 * cho caller. Luật 3 vẫn được giữ làm dự phòng vì trên data nó đúng ở mọi file không có
 * SDP — kể cả D114749E, nơi file tên `callee_webrtc.log` thật ra do máy caller ghi ra
 * (cùng deviceId với cuộc 1B009D42, hai lần gọi cách nhau 8,085 s ở cả đồng hồ tuyệt đối
 * lẫn đồng hồ tương đối của log).
 */
public class LegCorrelator {

    private static final String ROLE = "role";
    private static final String PLATFORM = "platform";
    private static final String MESSAGE = "message";
    private static final String LOCAL_OFFER = "DoSetLocalDescription: offer";
    private static final String LOCAL_ANSWER = "DoSetLocalDescription: answer";

    /** Kết quả: leg của từng file, và độ tin cậy của kết luận đó. */
    public record FileLeg(String fileName, Leg leg, String platform,
                          RelativeTrack.LegConfidence confidence) {}

    public Map<String, FileLeg> correlate(List<CanonicalEvent> events) {
        Map<String, FileLeg> endCallFiles = legsFromEndCallMetadata(events);
        Map<String, FileLeg> result = new LinkedHashMap<>(endCallFiles);
        Map<String, Leg> sdpRoles = webRtcSdpRoleByFile(events);

        for (Map.Entry<String, String> entry : webRtcPlatformByFile(events).entrySet()) {
            String fileName = entry.getKey();
            String platform = entry.getValue();
            Leg sdpRole = sdpRoles.get(fileName);
            result.put(fileName, sdpRole != null
                    ? new FileLeg(fileName, sdpRole, platform, RelativeTrack.LegConfidence.MATCHED_BY_SDP_ROLE)
                    : resolveWebRtcFile(fileName, platform, endCallFiles));
        }
        return Map.copyOf(result);
    }

    /**
     * Vai của từng file WebRTC theo dòng `DoSetLocalDescription` ĐẦU TIÊN (số dòng nhỏ nhất):
     * offer → caller, answer → callee. Lấy dòng đầu tiên vì nếu có đàm phán lại giữa cuộc
     * gọi thì bên nào cũng có thể tạo offer mới; vai ban đầu mới phản ánh ai là người gọi.
     */
    private Map<String, Leg> webRtcSdpRoleByFile(List<CanonicalEvent> events) {
        Map<String, CanonicalEvent> firstByFile = new LinkedHashMap<>();
        for (CanonicalEvent e : events) {
            if (e.source() != LogSource.WEBRTC || sdpRoleOf(e.attribute(MESSAGE)) == Leg.UNKNOWN) {
                continue;
            }
            firstByFile.merge(e.sourceRef().fileName(), e, (a, b) ->
                    a.sourceRef().lineNumber() <= b.sourceRef().lineNumber() ? a : b);
        }
        Map<String, Leg> roles = new LinkedHashMap<>();
        firstByFile.forEach((file, e) -> roles.put(file, sdpRoleOf(e.attribute(MESSAGE))));
        return roles;
    }

    private static Leg sdpRoleOf(String message) {
        if (message == null) {
            return Leg.UNKNOWN;
        }
        if (message.contains(LOCAL_OFFER)) {
            return Leg.CALLER;
        }
        if (message.contains(LOCAL_ANSWER)) {
            return Leg.CALLEE;
        }
        return Leg.UNKNOWN;
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
