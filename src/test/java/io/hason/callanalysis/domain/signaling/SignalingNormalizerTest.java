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
    @DisplayName("timestamp giữ nguyên 9 chữ số nano, không làm tròn")
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
    @DisplayName("sự kiện thứ N của bản export (ordinal N - 1) trích dẫn là signaling#N")
    void citationIsOrdinalPlusOne() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U-CALLER", "INFO"),
                record(86, "2026-09-21T08:45:08.000000000Z", "BYE", "U-CALLEE", "INFO"))));

        assertThat(result.events()).extracting(e -> e.sourceRef().citation())
                .containsExactly("signaling#1", "signaling#87");
    }

    @Test
    @DisplayName("leg suy ra từ appUserId của INIT_CALL đầu tiên — signaling không có trường leg")
    void legDerivedFromFirstInitCall() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U-CALLER", "INFO"),
                record(1, "2026-09-21T08:44:29.000000000Z", "RINGING", "U-CALLEE", "INFO"),
                record(2, "2026-09-21T08:44:30.000000000Z", "OK", "U-CALLEE", "INFO"))));

        assertThat(result.events()).extracting(CanonicalEvent::leg)
                .containsExactly(Leg.CALLER, Leg.CALLEE, Leg.CALLEE);
    }

    @Test
    @DisplayName("cuộc gọi chết sớm chỉ có INIT_CALL vẫn xác định được caller")
    void earlyDeathCallStillIdentifiesCaller() {
        LegAssignment legs = LegAssignment.fromFirstInitCall(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U-CALLER", "INFO")));

        assertThat(legs.derivedFrom()).isEqualTo(LegAssignment.Source.FIRST_INIT_CALL);
        assertThat(legs.legOf("U-CALLER")).isEqualTo(Leg.CALLER);
        assertThat(legs.legOf("U-NGUOI-LA")).isEqualTo(Leg.UNKNOWN);
    }

    @Test
    @DisplayName("không có INIT_CALL thì trả UNKNOWN thay vì đoán bừa")
    void noInitCallMeansUnknown() {
        LegAssignment legs = LegAssignment.fromFirstInitCall(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "BYE", "U-AI-DO", "INFO")));

        assertThat(legs.derivedFrom()).isEqualTo(LegAssignment.Source.NONE);
        assertThat(legs.legOf("U-AI-DO")).isEqualTo(Leg.UNKNOWN);
    }

    @Test
    @DisplayName("bản export bị cắt bớt sinh cảnh báo để báo vào Giới hạn dữ liệu")
    void truncatedExportProducesWarning() {
        // Cuộc gọi DE7DD314 trong data mẫu: truncated=true, trả về 200/201 event.
        SignalingFetch truncated = new SignalingFetch("CALL-1",
                List.of(record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U1", "INFO")),
                true, 200, 201);

        ParseResult result = normalizer.normalize(truncated);

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason())
                .contains("cắt bớt").contains("200/201").contains("thiếu 1");
        // nêu nguồn signaling, không nêu số dòng giả (signaling đến từ ES, không từ file)
        assertThat(result.warnings().getFirst().describe()).startsWith("signaling — ");
    }

    @Test
    @DisplayName("WARN được giữ nguyên mức độ, KHÔNG bị nâng thành lỗi")
    void warnIsNotTreatedAsError() {
        // 223/1059 event trong data mẫu là WARN, có cả ở cuộc gọi thành công.
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "2026-09-21T08:44:28.000000000Z", "INIT_CALL", "U1", "WARN"))));

        assertThat(result.events().getFirst().severity()).isEqualTo(Severity.WARN);
    }

    @Test
    @DisplayName("trường vắng mặt không tạo attribute rỗng")
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
    @DisplayName("timestamp hỏng -> bỏ bản ghi đó kèm cảnh báo, không throw")
    void malformedTimestampIsWarnedNotThrown() {
        ParseResult result = normalizer.normalize(fetch(List.of(
                record(0, "khong-phai-thoi-gian", "INIT_CALL", "U1", "INFO"),
                record(1, "2026-09-21T08:44:29.000000000Z", "INVITE", "U1", "INFO"))));

        assertThat(result.events()).hasSize(1);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().getFirst().reason()).contains("timestamp");
    }

    @Test
    @DisplayName("fetch rỗng / null không làm crash")
    void emptyFetchIsSafe() {
        assertThatCode(() -> {
            assertThat(normalizer.normalize(null).events()).isEmpty();
            assertThat(normalizer.normalize(fetch(List.of())).events()).isEmpty();
            assertThat(LegAssignment.fromFirstInitCall(null)).isNotNull();
        }).doesNotThrowAnyException();
    }
}
