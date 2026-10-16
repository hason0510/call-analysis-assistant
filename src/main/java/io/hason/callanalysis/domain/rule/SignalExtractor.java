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
     *
     * Package-private để RuleVerdictEngine in câu căn cứ từ CHÍNH hai hằng số này: viết cứng
     * "> 5 %" ở đó thì đổi ngưỡng ở đây xong report sẽ in câu tự mâu thuẫn.
     */
    static final BigDecimal LOSS_THRESHOLD_PERCENT = new BigDecimal("5.0");
    static final BigDecimal MOS_THRESHOLD = new BigDecimal("3.5");

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
        Set<Leg> legsWithClientLog = EnumSet.noneOf(Leg.class);
        timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL || e.source() == LogSource.WEBRTC)
                .filter(e -> e.leg() == Leg.CALLER || e.leg() == Leg.CALLEE)
                .forEach(e -> legsWithClientLog.add(e.leg()));

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
                qualityByLeg(timeline).stream().anyMatch(LegQuality::degraded),
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
                legsWithClientLog,
                qualityByLeg(timeline),
                !partnerLostNetworkEvents(timeline).isEmpty());
    }

    /** Bản ghi `signal` của end call log báo bên kia mất mạng (`network_lost`, originator remote). */
    private static List<CanonicalEvent> partnerLostNetworkEvents(CallTimeline timeline) {
        return timeline.allEvents().stream()
                .filter(e -> e.source() == LogSource.ENDCALL && e.type() == EventType.USER_ACTION)
                .filter(e -> "network_lost".equals(e.name()) && "remote".equalsIgnoreCase(e.attribute("originator")))
                .toList();
    }

    /**
     * Căn cứ đo được của cờ chất lượng cho từng leg. Cờ NETWORK_PACKET_LOSS chỉ dựa vào mất gói CẢ CUỘC
     * ({@link LegQuality#degraded()}); các số đếm theo mẫu còn lại chỉ để in phần "Đối chiếu".
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
            // Độ trễ / jitter: trung bình chỉ trên mẫu ĐÃ có âm thanh — trước khi có gói, RTT/jitter là 0 điền chỗ
            // trống (bẫy #11), và máy không bắt máy của leg nhiều thiết bị không có mẫu nào như vậy (bẫy #21).
            List<CanonicalEvent> live = samples.stream()
                    .filter(e -> decimal(e.attribute(PACKETS_RECEIVED)).filter(v -> v.signum() > 0).isPresent())
                    .toList();
            long packets = live.stream().map(e -> decimal(e.attribute(PACKETS_RECEIVED)))
                    .flatMap(Optional::stream).mapToLong(BigDecimal::longValue).max().orElse(0);
            result.add(new LegQuality(leg, samples.size(), lossOver, maxLoss, mosLow, minMos, mediaPoor, packets,
                    average(live, "transport.currentRttMs", BigDecimal.ONE),
                    average(live, "audio.jitter", BigDecimal.valueOf(1000)),
                    countersRecord(live).flatMap(SignalExtractor::wholeCallLoss).orElse(null)));
        }
        return result;
    }

    private static final String PACKETS_RECEIVED = "audio.packetsReceived";
    private static final String PACKETS_LOST = "audio.packetsLost";

    /**
     * Mẫu mang hai bộ đếm cộng dồn lớn nhất của leg (packetsReceived cao nhất; hoà thì mẫu sớm nhất) — mẫu dùng để tính
     * mất gói cả cuộc, cũng là dòng căn cứ của cờ. Leg nhiều thiết bị: máy không bắt máy không có mẫu có âm thanh nào, nên
     * mẫu này luôn của máy có âm thanh (bẫy #21).
     */
    private static Optional<CanonicalEvent> countersRecord(List<CanonicalEvent> live) {
        return live.stream().max(Comparator.comparing(
                (CanonicalEvent e) -> decimal(e.attribute(PACKETS_RECEIVED)).orElse(BigDecimal.ZERO)));
    }

    /** packetsLost ÷ (packetsLost + packetsReceived) × 100, 4 chữ số thập phân; trống khi thiếu bộ đếm. */
    private static Optional<BigDecimal> wholeCallLoss(CanonicalEvent e) {
        Optional<BigDecimal> lost = decimal(e.attribute(PACKETS_LOST));
        Optional<BigDecimal> received = decimal(e.attribute(PACKETS_RECEIVED));
        if (lost.isEmpty() || received.isEmpty() || lost.get().add(received.get()).signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(lost.get().multiply(BigDecimal.valueOf(100))
                .divide(lost.get().add(received.get()), 4, java.math.RoundingMode.HALF_UP));
    }

    /** Trung bình một trường trên các mẫu có trường đó, nhân hệ số đổi đơn vị; null nếu không mẫu nào có. */
    private static BigDecimal average(List<CanonicalEvent> samples, String field, BigDecimal unit) {
        List<BigDecimal> values = samples.stream().map(e -> decimal(e.attribute(field))).flatMap(Optional::stream).toList();
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add).multiply(unit)
                .divide(BigDecimal.valueOf(values.size()), 1, java.math.RoundingMode.HALF_UP);
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
        basis.addAll(wholeCallLossRecordPerLeg(timeline));
        basis.addAll(worstDelayRecordPerLeg(timeline));
        // ICE hỏng / không có media: Evidence Engine vốn đã chọn các dòng này làm mốc, nhưng phải liệt kê ở đây thì
        // evidence mới mang cờ ruleBasis — thiếu thì ca ICE_FAILURE (2D9057AA) không có dòng căn cứ nào (Sprint 3 T1b).
        timeline.allEvents().stream()
                .filter(e -> "failed".equalsIgnoreCase(e.attribute("iceStateTo")))
                .forEach(basis::add);
        if (noMediaBytes(timeline)) {
            qualityRecords(timeline).filter(e -> e.type() == EventType.CALL_SUMMARY).forEach(basis::add);
        }
        // Kết luận dựa trên cờ hasMediaFail thì dòng bật cờ ĐẦU TIÊN là căn cứ; trước đây ca FE4EF5E7 kết luận FAIL
        // mà evidence không có dòng nào mang mediaFail=1. Kèm dòng app báo bên kia mất mạng, nếu có.
        if (hasFlag(timeline, "transport.hasMediaFail")) {
            qualityRecords(timeline).filter(e -> "1".equals(e.attribute("transport.hasMediaFail")))
                    .findFirst().ifPresent(basis::add);
            basis.addAll(partnerLostNetworkEvents(timeline));
        }
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

    /**
     * Dòng căn cứ của cờ độ trễ: mỗi leg vượt ngưỡng Teams CQD một mẫu stats có RTT hiện tại cao nhất (hoà thì mẫu
     * sớm nhất). Cờ dựa trên TRUNG BÌNH nên không có "dòng bật cờ"; mẫu tệ nhất là dòng người đọc cần mở ra xem.
     */
    private List<CanonicalEvent> worstDelayRecordPerLeg(CallTimeline timeline) {
        List<CanonicalEvent> basis = new ArrayList<>();
        for (LegQuality q : qualityByLeg(timeline)) {
            if (!q.delayDegraded()) {
                continue;
            }
            qualityRecords(timeline)
                    .filter(e -> e.leg() == q.leg() && e.type() == EventType.MEDIA_STATS)
                    .filter(e -> decimal(e.attribute(PACKETS_RECEIVED)).filter(v -> v.signum() > 0).isPresent())
                    .sorted(Comparator.comparing((CanonicalEvent e) -> decimal(e.attribute("transport.currentRttMs"))
                            .orElse(BigDecimal.ZERO)).reversed())   // sort ổn định: hoà thì mẫu sớm nhất
                    .findFirst()
                    .ifPresent(basis::add);
        }
        return basis;
    }

    /**
     * Dòng căn cứ của cờ mất gói: mỗi leg vượt ngưỡng một mẫu — mẫu mang bộ đếm dùng để tính mất gói cả cuộc. Trước đây
     * là mẫu có audio.packetLostPercent cao nhất, nhưng con số đó là tỉ lệ TRONG KHOẢNG giữa hai lần đo, không phải căn cứ
     * của cờ nữa.
     */
    private List<CanonicalEvent> wholeCallLossRecordPerLeg(CallTimeline timeline) {
        List<CanonicalEvent> basis = new ArrayList<>();
        for (LegQuality q : qualityByLeg(timeline)) {
            if (!q.degraded()) {
                continue;
            }
            List<CanonicalEvent> live = qualityRecords(timeline)
                    .filter(e -> e.leg() == q.leg() && e.type() == EventType.MEDIA_STATS)
                    .filter(e -> decimal(e.attribute(PACKETS_RECEIVED)).filter(v -> v.signum() > 0).isPresent())
                    .toList();
            if (live.isEmpty()) {
                live = qualityRecords(timeline).filter(e -> e.leg() == q.leg())
                        .filter(e -> decimal(e.attribute(PACKETS_RECEIVED)).filter(v -> v.signum() > 0).isPresent())
                        .toList();
            }
            countersRecord(live).ifPresent(basis::add);
        }
        return basis;
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
