package io.hason.callanalysis.domain.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    @DisplayName("end call log nhan ra qua dong dac ta #H1")
    void detectsEndCallLog() {
        assertThat(detector.detect(List.of(
                "#H1\t#ts\t#tag\tappUserId\tcallId\tcallMode",
                "#H2\t#ts\t#tag\tmsg\tstatus\ttype",
                "1\t1789700842873\tinfo\tUS43EGOIN7G\tCALL-1\tDIRECT")))
                .isEqualTo(DetectedLogType.ENDCALL_LOG);
    }

    @Test
    @DisplayName("WebRTC iOS: khoi [giay:mili][thread] dung dau dong")
    void detectsWebRtcIos() {
        assertThat(detector.detect(List.of(
                "[4712:147][260115] (RTCLogging.mm:34): (RTCAudioSession.mm:680 -[RTCAudioSession x]): Incrementing.")))
                .isEqualTo(DetectedLogType.WEBRTC_IOS);
    }

    @Test
    @DisplayName("WebRTC Android: ten file .cc dung truoc khoi thoi gian")
    void detectsWebRtcAndroid() {
        assertThat(detector.detect(List.of(
                "peer_connection_factory.cc: [6652:953][12107] (line 398): Using default network controller factory")))
                .isEqualTo(DetectedLogType.WEBRTC_ANDROID);
    }

    @Test
    @DisplayName("truong giay co do rong bien thien — [000:000] toi [6652:953] deu nhan ra")
    void handlesVariableWidthSecondsField() {
        assertThat(detector.detect(List.of("[000:000][259] (RTCLogging.mm:34): khoi dong")))
                .isEqualTo(DetectedLogType.WEBRTC_IOS);
        assertThat(detector.detect(List.of("[6652:953][260115] (RTCLogging.mm:34): sau gan 2 gio")))
                .isEqualTo(DetectedLogType.WEBRTC_IOS);
    }

    @Test
    @DisplayName("signaling.json nhan ra qua callId + events o cap ngoai")
    void detectsSignalingJson() {
        assertThat(detector.detect(List.of(
                "{",
                "  \"callId\": \"CALL-1\",",
                "  \"environment\": \"production\",",
                "  \"events\": [")))
                .isEqualTo(DetectedLogType.SIGNALING_JSON);
    }

    @Test
    @DisplayName("ten file sai van nhan dung loai — `calleer_webrtc.log` la log Android")
    void ignoresFileNameEntirely() {
        // Detector khong he nhan ten file lam tham so: ket luan hoan toan tu noi dung.
        List<String> androidContent = List.of(
                "turn_port.cc: [15:652][8429] (line 1687): TurnPort(...): Received TURN probe error response");

        assertThat(detector.detect(androidContent)).isEqualTo(DetectedLogType.WEBRTC_ANDROID);
        assertThat(DetectedLogType.WEBRTC_ANDROID.isWebRtc()).isTrue();
    }

    @Test
    @DisplayName("input rac / rong tra ve UNKNOWN thay vi doan bua")
    void unrecognisedInputIsUnknown() {
        assertThat(detector.detect(List.of())).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(null)).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(List.of("", "   ", "\t"))).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(List.of("day khong phai log", "chi la van ban thuong")))
                .isEqualTo(DetectedLogType.UNKNOWN);
    }

    @Test
    @DisplayName("JSON khong phai signaling thi khong nhan nham")
    void otherJsonIsNotSignaling() {
        assertThat(detector.detect(List.of("{", "  \"name\": \"gi do khac\"", "}")))
                .isEqualTo(DetectedLogType.UNKNOWN);
    }
}
