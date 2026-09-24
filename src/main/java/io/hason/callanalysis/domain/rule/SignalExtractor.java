package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineNote;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Rút tín hiệu thô từ timeline. Không kết luận gì — việc đó thuộc RuleVerdictEngine. */
public class SignalExtractor {

    /**
     * Đường nền đo trên 844 mẫu stats của 6 leg thuộc success/ (tập có nhãn):
     * loss cao nhất 3,704%, MOS thấp nhất 4,335, RTT cao nhất 266 ms, jitter cao nhất 28 ms.
     * Ngưỡng cảnh báo đặt CAO HƠN đường nền để không gắn cờ nhầm cho cuộc gọi tốt.
     * Đây vẫn là PHỎNG ĐOÁN vì không có ca mẫu chất lượng kém — xem taxonomy.yaml.
     */
    private static final BigDecimal LOSS_THRESHOLD_PERCENT = new BigDecimal("5.0");
    private static final BigDecimal MOS_THRESHOLD = new BigDecimal("3.5");

    private static final String TURN_ALLOCATE_SUCCESS = "TURN allocate requested successfully";
    private static final String CANDIDATE_TIMEOUT = "_waitingCandidateTimer with error";
    private static final java.util.regex.Pattern CALL_ERROR_CODE =
            java.util.regex.Pattern.compile("\"callErrorCode\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern CALL_ERROR_KEY =
            java.util.regex.Pattern.compile("\"key\"\\s*:\\s*\"(call\\.[A-Za-z_.]+)\"");

    public RuleSignals extract(CallTimeline timeline) {
        Set<LogSource> sources = EnumSet.noneOf(LogSource.class);
        timeline.allEvents().forEach(e -> sources.add(e.source()));

        List<String> iceStates = timeline.allEvents().stream()
                .map(e -> e.attribute("iceStateTo"))
                .filter(java.util.Objects::nonNull)
                .toList();

        return new RuleSignals(
                timeline.countSignaling("INVITE") > 0,
                timeline.countSignaling("OK_ACK_OK") > 0,
                timeline.countSignaling("BYE") > 0,
                timeline.countSignaling("CANCEL") > 0,
                timeline.countSignaling("FAIL_HARD") > 0,
                iceStates.stream().anyMatch(s -> s.equalsIgnoreCase("connected")
                        || s.equalsIgnoreCase("completed")),
                iceStates.stream().anyMatch(s -> s.equalsIgnoreCase("failed")),
                hasFlag(timeline, "transport.hasMediaFail"),
                noMediaBytes(timeline),
                qualityDegraded(timeline),
                // Chỉ ghi nhận CẮT BỚT, không phải mọi thiếu hụt dữ liệu: kết luận
                // "bản export bị cắt" cho một timeline rỗng là nói sai sự thật.
                timeline.notes().stream().anyMatch(n ->
                        n.kind() == TimelineNote.Kind.SIGNALING_TRUNCATED),
                turnAllocationFailed(timeline),
                candidateTimeout(timeline),
                initCallRejection(timeline).orElse(null),
                sources);
    }

    /**
     * TURN hỏng hẳn trên một file WebRTC: có hoạt động TURN nhưng KHÔNG một lần
     * `TURN allocate requested successfully` nào.
     *
     * Xét theo vòng đời, không đếm dòng lỗi: `allocate error response` (401) là bước
     * bắt tay chuẩn và có ở mọi cuộc gọi thành công, `probe timeout` lẻ tẻ cũng vậy.
     * Trên data mẫu, 6 file thoả điều kiện này (không tạo được socket, error 65, probe
     * timeout liên tục) và 0/19 file có TURN còn lại.
     */
    private boolean turnAllocationFailed(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.WEBRTC && e.type() == EventType.TURN_EVENT)
                .collect(java.util.stream.Collectors.groupingBy(e -> e.sourceRef().fileName()))
                .values().stream()
                .anyMatch(events -> events.stream().noneMatch(e -> {
                    String message = e.attribute("message");
                    return message != null && message.contains(TURN_ALLOCATE_SUCCESS);
                }));
    }

    private boolean candidateTimeout(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .map(e -> e.attribute("msg"))
                .anyMatch(msg -> msg != null && msg.startsWith(CANDIDATE_TIMEOUT));
    }

    /**
     * Mã lỗi server trả trong ACK của INIT_CALL. Chỉ end call log phía caller mới thấy,
     * signaling.json không có trường text nào để biết lý do.
     */
    private Optional<String> initCallRejection(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL && e.type() == EventType.SIGNALING_COMMAND)
                .filter(e -> "INIT_CALL".equals(e.attribute("ackCmd")))
                .map(e -> e.attribute("payload"))
                .filter(java.util.Objects::nonNull)
                .map(SignalExtractor::describeCallError)
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** `"callErrorCode":428 … "key":"call.outgoing.error.privacy_restricted"` -> "428 call.outgoing.error.privacy_restricted". */
    private static Optional<String> describeCallError(String payload) {
        java.util.regex.Matcher code = CALL_ERROR_CODE.matcher(payload);
        if (!code.find()) {
            return Optional.empty();
        }
        java.util.regex.Matcher key = CALL_ERROR_KEY.matcher(payload);
        return Optional.of(key.find() ? code.group(1) + " " + key.group(1) : code.group(1));
    }

    private boolean hasFlag(CallTimeline timeline, String field) {
        return qualityRecords(timeline).anyMatch(e -> "1".equals(e.attribute(field)));
    }

    /**
     * Tín hiệu hỏng mạnh nhất và rẻ nhất để kiểm: không một byte audio nào đi qua.
     * Mạnh hơn MOS, vì MOS bằng 0 mơ hồ giữa "đo được và bằng 0" với "không đo được".
     * Chỉ xét khi đã có bản ghi chỉ số — không có bản ghi thì không kết luận.
     */
    private boolean noMediaBytes(CallTimeline timeline) {
        List<CanonicalEvent> records = qualityRecords(timeline).toList();
        if (records.isEmpty()) {
            return false;
        }
        return records.stream().allMatch(e ->
                isZero(e.attribute("audio.bytesReceived")) && isZero(e.attribute("audio.bytesSent")));
    }

    private boolean qualityDegraded(CallTimeline timeline) {
        return qualityRecords(timeline).anyMatch(e -> {
            boolean lossHigh = decimal(e.attribute("audio.packetLostPercent"))
                    .filter(v -> v.compareTo(LOSS_THRESHOLD_PERCENT) > 0).isPresent();
            boolean mosLow = decimal(e.attribute("audio.audioMos"))
                    .filter(v -> v.signum() > 0 && v.compareTo(MOS_THRESHOLD) < 0).isPresent();
            boolean poorFlag = "1".equals(e.attribute("transport.hasMediaPoor"));
            return lossHigh || mosLow || poorFlag;
        });
    }

    private java.util.stream.Stream<CanonicalEvent> qualityRecords(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> e.type() == EventType.CALL_SUMMARY || e.type() == EventType.MEDIA_STATS)
                .filter(e -> e.leg() == Leg.CALLER || e.leg() == Leg.CALLEE);
    }

    private static boolean isZero(String raw) {
        return decimal(raw).map(v -> v.signum() == 0).orElse(false);
    }

    private static Optional<BigDecimal> decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(raw.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
