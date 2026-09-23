package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.parse.DetectedLogType;
import io.hason.callanalysis.domain.parse.EndCallLogParser;
import io.hason.callanalysis.domain.parse.FileTypeDetector;
import io.hason.callanalysis.domain.parse.ParseContext;
import io.hason.callanalysis.domain.parse.ParseResult;
import io.hason.callanalysis.domain.parse.WebRtcLogParser;
import io.hason.callanalysis.domain.signaling.SignalingNormalizer;
import io.hason.callanalysis.service.port.SignalingSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dieu phoi buoc chuan hoa: nhan noi dung cac file dinh kem + truy van signaling,
 * tra ve toan bo canonical event cua mot cuoc goi.
 *
 * Service nay khong doc dia — no nhan san noi dung file. Nho vay test duoc bang
 * Map viet tay, khong can thu muc mau.
 */
@Service
public class CallLogNormalizationService {

    private final SignalingSource signalingSource;

    private final FileTypeDetector detector = new FileTypeDetector();
    private final EndCallLogParser endCallParser = new EndCallLogParser();
    private final WebRtcLogParser webRtcParser = new WebRtcLogParser();
    private final SignalingNormalizer signalingNormalizer = new SignalingNormalizer();

    public CallLogNormalizationService(SignalingSource signalingSource) {
        this.signalingSource = signalingSource;
    }

    /** Ket qua parse mot file dinh kem, kem loai da nhan dien duoc. */
    public record FileOutcome(String fileName, DetectedLogType detectedType, Leg leg,
                              int lineCount, ParseResult result) {}

    public record CallOutcome(String callId, ParseResult signaling, List<FileOutcome> files) {

        public ParseResult combined() {
            ParseResult all = signaling;
            for (FileOutcome f : files) {
                all = all.merge(f.result());
            }
            return all;
        }
    }

    public CallOutcome normalize(String callId, Map<String, List<String>> attachedFiles) {
        ParseResult signaling = signalingNormalizer.normalize(signalingSource.fetchByCallId(callId));

        List<FileOutcome> outcomes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : orderedByName(attachedFiles).entrySet()) {
            String fileName = entry.getKey();
            List<String> lines = entry.getValue();

            DetectedLogType type = detector.detect(lines);
            // Signaling khong lay tu file dinh kem ma tu Elasticsearch (MVP muc 8.1)
            if (type == DetectedLogType.SIGNALING_JSON) {
                continue;
            }

            Leg leg = legHintFromFileName(fileName);
            ParseContext ctx = ParseContext.of(fileName, callId, leg);
            ParseResult result = switch (type) {
                case ENDCALL_LOG -> endCallParser.parse(lines, ctx);
                case WEBRTC_IOS, WEBRTC_ANDROID -> webRtcParser.parse(lines, ctx);
                case SIGNALING_JSON, UNKNOWN -> ParseResult.empty();
            };
            outcomes.add(new FileOutcome(fileName, type, leg, lines.size(), result));
        }
        return new CallOutcome(callId, signaling, List.copyOf(outcomes));
    }

    private static Map<String, List<String>> orderedByName(Map<String, List<String>> files) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        files.keySet().stream().sorted().forEach(k -> ordered.put(k, files.get(k)));
        return ordered;
    }

    /**
     * Suy leg tu ten file — chi la GOI Y. Data mau co `calleer_webrtc.log` thuc chat
     * la log cua caller; xac dinh dung chu so huu can doi chieu format (iOS/Android)
     * voi cot platform cua ban ghi #H1, thuoc buoc correlate cua T4.
     */
    private static Leg legHintFromFileName(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.startsWith("caller")) {
            return Leg.CALLER;
        }
        if (lower.startsWith("callee")) {
            return Leg.CALLEE;
        }
        return Leg.UNKNOWN;
    }
}
