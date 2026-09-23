package io.hason.callanalysis.domain.signaling;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.parse.ParseResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SignalingNormalizerTest {

    private final SignalingNormalizer normalizer = new SignalingNormalizer();

    private static RawSignalingRecord record(int ordinal, String ts, String cmd, String user, String level) {
        return new RawSignalingRecord("CALL-1", ordinal, ts, "SVC1", level, cmd,
                "csid1", "req-" + ordinal, user, "sess1", "MOBIFONE", "AS131429", "VN", null);
    }

    private static SignalingFetch fetch(List<RawSignalingRecord> records) {
        return new SignalingFetch("CALL-1", records, false, records.size(), records.size());
    }

    @Test
    @DisplayName("timestamp giu nguyen 9 chu so nano, khong lam tron")
    void nanosecondPrecisionSurvives() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.953756952Z", "INIT_CALL", "U-CALLER", "INFO"))));

        EventTime time = result.events().getFirst().time();
        EventTime.Absolute absolute = (EventTime.Absolute) time;
        assertThat(absolute.instant()).isEqualTo(Instant.parse("2026-09-21T08:44:28.953756952Z"));
        assertThat(absolute.instant().getNano()).isEqualTo(953_756_952);
        assertThat(absolute.clock()).isEqualTo(ClockDomain.SERVER);
    }

    @Test
    @DisplayName("leg suy ra tu appUserId cua INIT_CALL dau tien — signaling khong co truong leg")
    void legDerivedFromFirstInitCall() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U-CALLER", "INFO"),
                record(1, "2026-09-21T08:44:29.000000000Z", "RINGING", "U-CALLEE", "INFO"),
                record(2, "2026-09-21T08:44:30.000000000Z", "OK", "U-CALLEE", "INFO"))));

        assertThat(result.events()).extracting(CanonicalEvent::leg)
                .containsExactly(Leg.CALLER, Leg.CALLEE, Leg.CALLEE);
    }

    @Test
    @DisplayName("cuoc goi chet som chi co INIT_CALL van xac dinh duoc caller")
    void earlyDeathCallStillIdentifiesCaller() {
        LegAssignment legs = LegAssignment.fromFirstInitCall(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U-CALLER", "INFO")));

        assertThat(legs.derivedFrom()).isEqualTo(LegAssignment.Source.FIRST_INIT_CALL);
        assertThat(legs.legOf("U-CALLER")).isEqualTo(Leg.CALLER);
        assertThat(legs.legOf("U-NGUOI-LA")).isEqualTo(Leg.UNKNOWN);
    }

    @Test
    @DisplayName("khong co INIT_CALL thi tra UNKNOWN thay vi doan bua")
    void noInitCallMeansUnknown() {
        LegAssignment legs = LegAssignment.fromFirstInitCall(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "BYE", "U-AI-DO", "INFO")));

        assertThat(legs.derivedFrom()).isEqualTo(LegAssignment.Source.NONE);
        assertThat(legs.legOf("U-AI-DO")).isEqualTo(Leg.UNKNOWN);
    }

    @Test
    @DisplayName("ban export bi cat bot sinh canh bao de bao vao Gioi han du lieu")
    void truncatedExportProducesWarning() {
        // Cuoc goi DE7DD314 trong data mau: truncated=true, tra ve 200/201 event.
        SignalingFetch truncated = new SignalingFetch("CALL-1",
                List.of(record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U1", "INFO")),
                true, 200, 201);

        ParseResult result = normalizer.normalize(truncated);

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason())
                .contains("cat bot").contains("200/201").contains("thieu 1");
    }

    @Test
    @DisplayName("WARN duoc giu nguyen muc do, KHONG bi nang thanh loi")
    void warnIsNotTreatedAsError() {
        // 223/1059 event trong data mau la WARN, co ca o cuoc goi thanh cong.
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U1", "WARN"))));

        assertThat(result.events().getFirst().severity()).isEqualTo(Severity.WARN);
    }

    @Test
    @DisplayName("truong vang mat khong tao attribute rong")
    void missingFieldsAreOmittedNotBlank() {
        RawSignalingRecord sparse = new RawSignalingRecord("CALL-1", 0,
                "2026-09-21T08:44:28.000000000Z", "SVC", "INFO", "INIT_CALL",
                null, null, "U1", null, null, null, "VN", 5);

        CanonicalEvent e = normalizer.normalize(fetch(List.of(sparse))).events().getFirst();

        assertThat(e.attributes()).containsKeys("service", "appUserId", "countryCode", "latencyMs");
        assertThat(e.attributes()).doesNotContainKeys("csid", "isp", "asn", "callSessionId");
        assertThat(e.attribute("latencyMs")).isEqualTo("5");
    }

    @Test
    @DisplayName("timestamp hong -> bo ban ghi do kem canh bao, khong throw")
    void malformedTimestampIsWarnedNotThrown() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "khong-phai-thoi-gian", "INIT_CALL", "U1", "INFO"),
                record(1, "2026-09-21T08:44:29.000000000Z", "INVITE", "U1", "INFO"))));

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("timestamp");
    }

    @Test
    @DisplayName("fetch rong / null khong lam crash")
    void emptyFetchIsSafe() {
        assertThatCode(() -> {
            assertThat(normalizer.normalize(null).events()).isEmpty();
            assertThat(normalizer.normalize(fetch(List.of())).events()).isEmpty();
            assertThat(LegAssignment.fromFirstInitCall(null)).isNotNull();
        }).doesNotThrowAnyException();
    }
}
