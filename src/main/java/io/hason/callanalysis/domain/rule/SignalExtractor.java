package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.TimelineNote;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String CLIENT_FAILURE = "_emitFailed";
    private static final Pattern CALL_ERROR_CODE =
            Pattern.compile("\"callErrorCode\"\\s*:\\s*(\\d+)");
    private static final Pattern CALL_ERROR_KEY =
            Pattern.compile("\"key\"\\s*:\\s*\"(call\\.[A-Za-z_.]+)\"");
    /** `_emitFailed with originator: 0 reason: call.outgoing.error.network_check endReason: 0 code: 421 ...` */
    private static final Pattern EMIT_FAILED_CODE = Pattern.compile("\\bcode:\\s*(\\d+)");
    private static final Pattern EMIT_FAILED_REASON = Pattern.compile("\\breason:\\s*(\\S+)");
    /** Chữ báo lỗi NGUYÊN VĂN trong dòng TURN; chỉ dùng để chọn dòng trích dẫn, không để kết luận. */
    private static final Pattern TURN_ERROR_WORDING =
            Pattern.compile("fail|error|timeout", Pattern.CASE_INSENSITIVE);
    /** Server đòi credential (RFC 8656): bước bắt tay chuẩn, không phải lỗi. */
    private static final String TURN_AUTH_CHALLENGE = "code=401";
    private static final String TURN_REQUEST_SENT = "TURN allocate request sent";
    /** Mọi phản hồi lỗi của allocate, kể cả 401: có dòng này nghĩa là request đã tới server. */
    private static final String TURN_RESPONSE = "allocate error response";
    private static final String TURN_SOCKET_FAILED = "Failed to create TURN client socket";
    private static final Pattern TURN_SEND_FAILED =
            Pattern.compile("Failed to send TURN message, (error: -?\\d+)");
    /** `-Remote[<địa chỉ server>:3478/udp]` — chỉ lấy cổng và giao thức, bỏ địa chỉ. */
    private static final Pattern TURN_TRANSPORT =
            Pattern.compile("-Remote\\[[^\\]]*:(\\d+/(?:udp|tcp|tls))]");
    /**
     * Giao diện VPN mà libwebrtc liệt kê, ở một trong hai dạng của Android:
     * `NetInfo[name tun0; handle …; type 9; underlying_type_for_vpn 2]` (network monitor) và
     * `Net[tun0:172.16.0.x/32:VPN/Wifi:id=4]` (port allocator). Dạng iOS chưa gặp trong data.
     */
    private static final Pattern VPN_INTERFACE = Pattern.compile(
            "NetInfo\\[name ([\\w.-]+);[^\\]]*underlying_type_for_vpn|Net\\[([\\w.-]+):[^\\]]*:VPN\\b");

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
                !degradedRecords(timeline).isEmpty(),
                // Chỉ ghi nhận CẮT BỚT, không phải mọi thiếu hụt dữ liệu: kết luận
                // "bản export bị cắt" cho một timeline rỗng là nói sai sự thật.
                timeline.notes().stream().anyMatch(n ->
                        n.kind() == TimelineNote.Kind.SIGNALING_TRUNCATED),
                turnFailure(timeline),
                candidateTimeoutEvent(timeline).isPresent(),
                initCallRejectionEvent(timeline)
                        .flatMap(e -> describeCallError(e.attribute("payload")))
                        .orElse(null),
                clientFailureEvent(timeline).flatMap(SignalExtractor::describeClientFailure).orElse(null),
                sources,
                qualityByLeg(timeline));
    }

    /**
     * Căn cứ đo được của cờ chất lượng cho từng leg — cùng ngưỡng với degradedRecords.
     *
     * Đếm trên chuỗi mẫu stats, không đếm bản ghi summary vì nó trùng đúng mẫu stats cuối
     * (8/8 leg có media trong data mẫu); chỉ dùng summary khi leg không có mẫu stats nào.
     */
    private List<LegQuality> qualityByLeg(CallTimeline timeline) {
        List<LegQuality> result = new ArrayList<>();
        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            List<CanonicalEvent> records = qualityRecords(timeline).filter(e -> e.leg() == leg).toList();
            List<CanonicalEvent> samples = records.stream()
                    .filter(e -> e.type() == EventType.MEDIA_STATS).toList();
            if (samples.isEmpty()) {
                samples = records;
            }
            if (samples.isEmpty()) {
                continue;
            }
            int lossOver = 0;
            int mosLow = 0;
            int mediaPoor = 0;
            BigDecimal maxLoss = null;
            BigDecimal minMos = null;
            for (CanonicalEvent e : samples) {
                Optional<BigDecimal> loss = decimal(e.attribute("audio.packetLostPercent"));
                if (loss.isPresent()) {
                    lossOver += loss.get().compareTo(LOSS_THRESHOLD_PERCENT) > 0 ? 1 : 0;
                    maxLoss = maxLoss == null || loss.get().compareTo(maxLoss) > 0 ? loss.get() : maxLoss;
                }
                Optional<BigDecimal> mos = decimal(e.attribute("audio.audioMos")).filter(v -> v.signum() > 0);
                if (mos.isPresent()) {
                    mosLow += mos.get().compareTo(MOS_THRESHOLD) < 0 ? 1 : 0;
                    minMos = minMos == null || mos.get().compareTo(minMos) < 0 ? mos.get() : minMos;
                }
                mediaPoor += "1".equals(e.attribute("transport.hasMediaPoor")) ? 1 : 0;
            }
            result.add(new LegQuality(leg, samples.size(), lossOver, maxLoss, mosLow, minMos, mediaPoor));
        }
        return result;
    }

    /**
     * Các dòng log làm CĂN CỨ cho những tín hiệu lỗi đang bật, để Evidence Engine đưa vào
     * report (MVP mục 3.3: "Mọi kết luận phải trace được về evidence").
     *
     * Dùng chung đúng các hàm tìm kiếm với {@link #extract}, nên dòng được trích luôn là
     * dòng mà tín hiệu thật sự dựa vào — không có bộ ngưỡng thứ hai để lệch nhau.
     * Tín hiệu không bật thì không trích gì.
     */
    public List<CanonicalEvent> basis(CallTimeline timeline) {
        List<CanonicalEvent> basis = new ArrayList<>();
        initCallRejectionEvent(timeline).ifPresent(basis::add);
        candidateTimeoutEvent(timeline).ifPresent(basis::add);
        turnFailedFiles(timeline).forEach((file, events) -> {
            TurnFailure failure = classifyTurnFailure(file, events, timeline);
            basis.add(turnCitation(events, failure.kind()));
            // Câu đề xuất nhắc tới VPN thì dòng log ghi nhận VPN cũng phải trích được
            vpnEvent(timeline, file).ifPresent(basis::add);
        });
        basis.addAll(worstDegradedRecordPerLeg(timeline));
        return List.copyOf(basis);
    }

    /**
     * Kiểu TURN hỏng của file hỏng đầu tiên theo tên; {@link TurnFailure#NONE} nếu mọi file
     * có hoạt động TURN đều cấp phát được ít nhất một lần.
     */
    private TurnFailure turnFailure(CallTimeline timeline) {
        return turnFailedFiles(timeline).entrySet().stream()
                .findFirst()
                .map(entry -> classifyTurnFailure(entry.getKey(), entry.getValue(), timeline))
                .orElse(TurnFailure.NONE);
    }

    /**
     * Xếp kiểu theo hai câu hỏi: request có rời máy không, và server có trả lời không.
     * Thứ tự xét quan trọng: `Failed to send` chỉ có nghĩa khi CHƯA có phản hồi nào —
     * có phản hồi tức là đã có ít nhất một request tới được server.
     */
    private static TurnFailure classifyTurnFailure(String file, List<CanonicalEvent> turnEvents,
                                                   CallTimeline timeline) {
        int requestsSent = countMessages(turnEvents, TURN_REQUEST_SENT);
        int responses = countMessages(turnEvents, TURN_RESPONSE);
        boolean socketFailed = countMessages(turnEvents, TURN_SOCKET_FAILED) > 0;
        String sendError = firstMatch(turnEvents, TURN_SEND_FAILED).orElse(null);
        String transport = firstMatch(turnEvents, TURN_TRANSPORT).orElse(null);
        String vpn = vpnEvent(timeline, file)
                .flatMap(e -> vpnInterfaceOf(e.attribute("message")))
                .orElse(null);

        TurnFailure.Kind kind;
        if (socketFailed && requestsSent == 0) {
            kind = TurnFailure.Kind.SOCKET_NOT_CREATED;
        } else if (sendError != null && responses == 0) {
            kind = TurnFailure.Kind.SEND_FAILED_ON_DEVICE;
        } else if (responses > 0) {
            kind = TurnFailure.Kind.NOT_ALLOCATED_AFTER_RESPONSE;
        } else if (requestsSent > 0) {
            kind = TurnFailure.Kind.NO_RESPONSE;
        } else {
            kind = TurnFailure.Kind.UNCLASSIFIED;
        }
        return new TurnFailure(kind, requestsSent, responses, sendError, transport, vpn);
    }

    private static int countMessages(List<CanonicalEvent> events, String text) {
        return (int) events.stream()
                .map(e -> e.attribute("message"))
                .filter(m -> m != null && m.contains(text))
                .count();
    }

    private static Optional<String> firstMatch(List<CanonicalEvent> events, Pattern pattern) {
        for (CanonicalEvent e : events) {
            String message = e.attribute("message");
            if (message == null) {
                continue;
            }
            Matcher m = pattern.matcher(message);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    /** Dòng đầu tiên của file WebRTC có liệt kê giao diện VPN. */
    private static Optional<CanonicalEvent> vpnEvent(CallTimeline timeline, String file) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.WEBRTC && file.equals(e.sourceRef().fileName()))
                .filter(e -> vpnInterfaceOf(e.attribute("message")).isPresent())
                .findFirst();
    }

    /** `…NetInfo[name tun0; …; underlying_type_for_vpn 2]` hoặc `Net[tun0:…:VPN/Wifi:id=4]` -> "tun0". */
    public static Optional<String> vpnInterfaceOf(String message) {
        if (message == null) {
            return Optional.empty();
        }
        Matcher m = VPN_INTERFACE.matcher(message);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(1) != null ? m.group(1) : m.group(2));
    }

    /**
     * File WebRTC có hoạt động TURN nhưng KHÔNG một lần `TURN allocate requested successfully`,
     * xếp theo tên file để thứ tự tất định.
     *
     * Xét theo vòng đời, không đếm dòng lỗi: `allocate error response` (401) là bước
     * bắt tay chuẩn và có ở mọi cuộc gọi thành công, `probe timeout` lẻ tẻ cũng vậy.
     * Trên data mẫu, 25/27 file WebRTC có dòng `turn_port`; 6 file thoả điều kiện này
     * (3 ở fail/, 3 ở for_test/ — kiểu của từng file xem {@link TurnFailure})
     * và 0/19 file còn lại, gồm cả 13 file của success/.
     */
    private Map<String, List<CanonicalEvent>> turnFailedFiles(CallTimeline timeline) {
        Map<String, List<CanonicalEvent>> byFile = new TreeMap<>();
        timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.WEBRTC && e.type() == EventType.TURN_EVENT)
                .forEach(e -> byFile.computeIfAbsent(e.sourceRef().fileName(), f -> new ArrayList<>()).add(e));
        byFile.values().removeIf(events -> events.stream().anyMatch(e -> {
            String message = e.attribute("message");
            return message != null && message.contains(TURN_ALLOCATE_SUCCESS);
        }));
        return byFile;
    }

    /**
     * Dòng trích dẫn cho một file TURN hỏng — đúng dòng làm nên kiểu của file đó:
     *   SOCKET_NOT_CREATED     dòng `Failed to create TURN client socket` đầu tiên
     *   SEND_FAILED_ON_DEVICE  dòng `Failed to send TURN message` đầu tiên
     *   NO_RESPONSE            dòng `TURN allocate request sent` đầu tiên — request đi mà không về
     * Hai kiểu còn lại: dòng TURN đầu tiên có chữ báo lỗi nguyên văn, bỏ qua
     * `allocate error response ... code=401` vì đó là bước bắt tay chuẩn; không có thì
     * dòng TURN cuối cùng của file.
     *
     * Những chữ báo lỗi này cũng có ở cuộc gọi thành công (dòng 401 ở 13/13 file WebRTC
     * của success/, probe timeout ở 5/13), nên chúng KHÔNG quyết định file có hỏng hay
     * không — việc đó do turnFailedFiles làm. Ở đây chỉ chọn dòng đại diện để trích.
     */
    private static CanonicalEvent turnCitation(List<CanonicalEvent> events, TurnFailure.Kind kind) {
        String marker = switch (kind) {
            case SOCKET_NOT_CREATED -> TURN_SOCKET_FAILED;
            case SEND_FAILED_ON_DEVICE -> "Failed to send TURN message";
            case NO_RESPONSE -> TURN_REQUEST_SENT;
            default -> null;
        };
        if (marker != null) {
            Optional<CanonicalEvent> exact = events.stream()
                    .filter(e -> {
                        String message = e.attribute("message");
                        return message != null && message.contains(marker);
                    })
                    .findFirst();
            if (exact.isPresent()) {
                return exact.get();
            }
        }
        return events.stream()
                .filter(e -> {
                    String message = e.attribute("message");
                    return message != null && TURN_ERROR_WORDING.matcher(message).find()
                            && !message.contains(TURN_AUTH_CHALLENGE);
                })
                .findFirst()
                .orElse(events.getLast());
    }

    private Optional<CanonicalEvent> candidateTimeoutEvent(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> {
                    String msg = e.attribute("msg");
                    return msg != null && msg.startsWith(CANDIDATE_TIMEOUT);
                })
                .findFirst();
    }

    /**
     * ACK của INIT_CALL có mang mã lỗi server trả về. Chỉ end call log phía caller mới thấy,
     * signaling.json không có trường text nào để biết lý do.
     */
    private Optional<CanonicalEvent> initCallRejectionEvent(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL && e.type() == EventType.SIGNALING_COMMAND)
                .filter(e -> "INIT_CALL".equals(e.attribute("ackCmd")))
                .filter(e -> describeCallError(e.attribute("payload")).isPresent())
                .findFirst();
    }

    /** `"callErrorCode":428 … "key":"call.outgoing.error.privacy_restricted"` -> "428 call.outgoing.error.privacy_restricted". */
    public static Optional<String> describeCallError(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        Matcher code = CALL_ERROR_CODE.matcher(payload);
        if (!code.find()) {
            return Optional.empty();
        }
        Matcher key = CALL_ERROR_KEY.matcher(payload);
        return Optional.of(key.find() ? code.group(1) + " " + key.group(1) : code.group(1));
    }

    /** Dòng `_emitFailed` đầu tiên của end call log: app tự ghi mã và lý do thất bại. */
    private Optional<CanonicalEvent> clientFailureEvent(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL)
                .filter(e -> {
                    String msg = e.attribute("msg");
                    return msg != null && msg.startsWith(CLIENT_FAILURE);
                })
                .findFirst();
    }

    /** `... reason: call.outgoing.error.network_check endReason: 0 code: 421 ...` -> "421 call.outgoing.error.network_check". */
    private static Optional<String> describeClientFailure(CanonicalEvent event) {
        String msg = event.attribute("msg");
        Matcher code = EMIT_FAILED_CODE.matcher(msg);
        if (!code.find()) {
            return Optional.empty();
        }
        Matcher reason = EMIT_FAILED_REASON.matcher(msg);
        return Optional.of(reason.find() ? code.group(1) + " " + reason.group(1) : code.group(1));
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

    /** Các bản ghi chỉ số vượt ngưỡng chất lượng — quét cả chuỗi stats, không chỉ summary. */
    private List<CanonicalEvent> degradedRecords(CallTimeline timeline) {
        return qualityRecords(timeline).filter(e -> {
            boolean lossHigh = decimal(e.attribute("audio.packetLostPercent"))
                    .filter(v -> v.compareTo(LOSS_THRESHOLD_PERCENT) > 0).isPresent();
            boolean mosLow = decimal(e.attribute("audio.audioMos"))
                    .filter(v -> v.signum() > 0 && v.compareTo(MOS_THRESHOLD) < 0).isPresent();
            boolean poorFlag = "1".equals(e.attribute("transport.hasMediaPoor"));
            return lossHigh || mosLow || poorFlag;
        }).toList();
    }

    /**
     * Mỗi leg một dòng: bản ghi vượt ngưỡng có loss cao nhất (hoà thì lấy bản ghi sớm nhất).
     * Không trích bản ghi cuối như phần evidence chung, vì bản ghi cuối có thể đã hồi về 0%
     * — 271D1FAF là ví dụ: loss cuối 0% nhưng caller có 18/80 giây mất gói trên 5%.
     */
    private List<CanonicalEvent> worstDegradedRecordPerLeg(CallTimeline timeline) {
        List<CanonicalEvent> degraded = degradedRecords(timeline);
        Comparator<CanonicalEvent> worstFirst = Comparator
                .comparing((CanonicalEvent e) -> decimal(e.attribute("audio.packetLostPercent"))
                        .orElse(BigDecimal.ZERO))
                .reversed();
        List<CanonicalEvent> worst = new ArrayList<>();
        for (Leg leg : List.of(Leg.CALLER, Leg.CALLEE)) {
            degraded.stream()
                    .filter(e -> e.leg() == leg)
                    .sorted(worstFirst)   // sort ổn định: hoà thì giữ thứ tự timeline
                    .findFirst()
                    .ifPresent(worst::add);
        }
        return worst;
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
