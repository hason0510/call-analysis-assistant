package io.hason.callanalysis.domain.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTypeDetectorTest {

    private final FileTypeDetector detector = new FileTypeDetector();

    @Test
    @DisplayName("end call log nhận ra qua dòng đặc tả #H1")
    void detectsEndCallLog() {
        assertThat(detector.detect(List.of(
                "#H1\t#ts\t#tag\tappUserId\tcallId\tcallMode",
                "#H2\t#ts\t#tag\tmsg\tstatus\ttype",
                "1\t1789700842873\tinfo\tUS43EGOIN7G\tCALL-1\tDIRECT")))
                .isEqualTo(DetectedLogType.ENDCALL_LOG);
    }

    @Test
    @DisplayName("WebRTC Android: tên file .cc đứng trước khối thời gian")
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
    @DisplayName("signaling.json nhận ra qua callId + events ở cấp ngoài")
    void detectsSignalingJson() {
        assertThat(detector.detect(List.of(
                "{",
                "  \"callId\": \"CALL-1\",",
                "  \"environment\": \"production\",",
                "  \"events\": [")))
                .isEqualTo(DetectedLogType.SIGNALING_JSON);
    }

    @Test
    @DisplayName("input rác / rỗng trả về UNKNOWN thay vì đoán bừa")
    void unrecognisedInputIsUnknown() {
        assertThat(detector.detect(List.of())).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(null)).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(List.of("", "   ", "\t"))).isEqualTo(DetectedLogType.UNKNOWN);
        assertThat(detector.detect(List.of("day khong phai log", "chi la van ban thuong")))
                .isEqualTo(DetectedLogType.UNKNOWN);
    }

    @Test
    @DisplayName("JSON không phải signaling thì không nhận nhầm")
    void otherJsonIsNotSignaling() {
        assertThat(detector.detect(List.of("{", "  \"name\": \"gi do khac\"", "}")))
                .isEqualTo(DetectedLogType.UNKNOWN);
    }
}
