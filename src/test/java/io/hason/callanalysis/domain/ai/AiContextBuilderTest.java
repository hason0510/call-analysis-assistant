package io.hason.callanalysis.domain.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.ClockDomain;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.event.EventType;
import io.hason.callanalysis.domain.event.Leg;
import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.event.Severity;
import io.hason.callanalysis.domain.event.SourceRef;
import io.hason.callanalysis.domain.evidence.Evidence;
import io.hason.callanalysis.domain.evidence.EvidenceEngine;
import io.hason.callanalysis.domain.guardrail.GuardrailResult;
import io.hason.callanalysis.domain.guardrail.Guardrails;
import io.hason.callanalysis.domain.metrics.CallMetric;
import io.hason.callanalysis.domain.metrics.CallMetrics;
import io.hason.callanalysis.domain.metrics.MetricKey;
import io.hason.callanalysis.domain.metrics.MetricValue;
import io.hason.callanalysis.domain.rule.RuleVerdict;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.signaling.LegAssignment;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.IssueTaxonomy;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.RelativeTrack;
import io.hason.callanalysis.domain.timeline.TimelineBuilder;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Dựng Safe AI Context (MVP mục 6.1 T5, 6.2): minimum necessary, đã làm sạch, tất định. */
class AiContextBuilderTest {

    private static final String CALL_ID = "2D9057AA-0000-4000-8000-000000000001";
    private static final String APP_USER_ID = "UZFSCHDZTWR";
    private static final String RAW_MARKER = "DONG-LOG-GOC-KHONG-DUOC-GUI";

    private static final IssueTaxonomy TAXONOMY = new TaxonomyLoader().load();
    private static final SensitiveDataSanitizer SANITIZER = new SensitiveDataSanitizer(
            new SensitiveDataInventoryLoader().load(), new Pseudonymizer(new byte[32]));
    private static final RuleVerdict FAIL_ICE = new RuleVerdict(Verdict.FAIL, false, IssueCategory.ICE_FAILURE,
            ConfidenceLevel.MEDIUM, "ICE chuyển checking => failed", List.of());

    private final ObjectMapper mapper = new ObjectMapper();
    private final AiContextBuilder builder = new AiContextBuilder(SANITIZER);

    /** INIT_CALL mang appUserId ở thuộc tính; `_emitBye` nhắc lại nó và một IP trong văn bản tự do. */
    private static CallTimeline timeline() {
        CanonicalEvent initCall = new CanonicalEvent("signaling#1", CALL_ID, Leg.CALLER, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:00Z"), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, "INIT_CALL", Map.of("appUserId", APP_USER_ID), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, 1, RAW_MARKER));
        CanonicalEvent ringing = new CanonicalEvent("signaling#2", CALL_ID, Leg.CALLEE, LogSource.SIGNALING,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:05Z"), ClockDomain.SERVER),
                EventType.SIGNALING_COMMAND, "RINGING", Map.of(), Severity.INFO,
                new SourceRef(SourceRef.SIGNALING, 2, RAW_MARKER));
        CanonicalEvent bye = new CanonicalEvent("caller_endcall.log#40", CALL_ID, Leg.CALLER, LogSource.ENDCALL,
                EventTime.absolute(Instant.parse("2026-09-21T08:00:30Z"), ClockDomain.CLIENT_CALLER),
                EventType.LOG_MESSAGE, "log_detail",
                Map.of("msg", "_emitBye user " + APP_USER_ID + " from 203.0.113.7 duration: 26"), Severity.INFO,
                new SourceRef("caller_endcall.log", 40, RAW_MARKER));
        CanonicalEvent ice = new CanonicalEvent("callee_webrtc.log#9", CALL_ID, Leg.CALLEE, LogSource.WEBRTC,
                EventTime.relative(Duration.ofMillis(1200)), EventType.LOG_MESSAGE, "IceConnectionState",
                Map.of("iceStateFrom", "checking", "iceStateTo", "failed"), Severity.INFO,
                new SourceRef("callee_webrtc.log", 9, RAW_MARKER));
        CallTimeline base = new TimelineBuilder().build(CALL_ID, List.of(initCall, ringing, bye), LegAssignment.unknown());
        return new CallTimeline(base.callId(), base.mainTrack(),
                List.of(new RelativeTrack("callee_webrtc.log", Leg.CALLEE, "android",
                        RelativeTrack.LegConfidence.MATCHED_BY_SDP_ROLE, List.of(ice))),
                base.legs(), base.clockOffsets(), base.notes());
    }

    private static final CallMetrics METRICS = new CallMetrics(CALL_ID, List.of(
            CallMetric.of(MetricKey.RTT, Leg.CALLER, MetricValue.millis(63)),
            CallMetric.of(MetricKey.MOS, Leg.CALLEE, MetricValue.unavailable("audio.packetsReceived = 0")),
            CallMetric.of(MetricKey.MAX_PAIR_PING_GAP, Leg.CALLER, MetricValue.millis(1505))));

    private AiContext build(String question, RuleVerdict rule) {
        CallTimeline timeline = timeline();
        List<Evidence> evidence = new EvidenceEngine().collect(timeline);
        return builder.build("req-1", question, null, timeline, METRICS, evidence, rule, TAXONOMY);
    }

    private String json(AiContext context) throws Exception {
        return mapper.writeValueAsString(context.payload());
    }

    @Test
    @DisplayName("định danh có cấu trúc bị thay cả trong văn bản tự do; IP trong log và SĐT trong câu hỏi bị che")
    void identifiersAndAddressesAreSanitized() throws Exception {
        AiContext context = build("Máy " + APP_USER_ID + ", số 0912345678, vì sao không nghe được?", FAIL_ICE);
        String all = json(context) + context.question();

        assertThat(all).doesNotContain(APP_USER_ID).doesNotContain("203.0.113.7").doesNotContain("0912345678");
        String pseudonym = SANITIZER.replacement(SANITIZER.policyForKey("appUserId").orElseThrow(), APP_USER_ID);
        assertThat(context.question()).contains(pseudonym);           // cùng mã ở câu hỏi …
        assertThat(json(context)).contains(pseudonym);                // … và ở evidence: AI vẫn ghép được
        assertThat(context.redactions()).containsKeys("user-and-device-id", "phone-and-email");
    }

    @Test
    @DisplayName("minimum necessary: không gửi dòng log gốc, Call-ID, hay phần hiệu chỉnh của taxonomy")
    void onlyNormalizedDataIsSent() throws Exception {
        String json = json(build("Phân tích giúp mình", FAIL_ICE));

        assertThat(json).doesNotContain(RAW_MARKER).doesNotContain(CALL_ID).doesNotContain(CALL_ID.substring(0, 8));
        // calibration.evidence của taxonomy nêu Call-ID của data mẫu (1B009D42, 703100CF…)
        assertThat(json).doesNotContain("1B009D42").doesNotContain("703100CF");
        assertThat(json).contains("\"id\":\"ICE_FAILURE\"").contains("\"validated\":");
    }

    @Test
    @DisplayName("evidence giữ ID, nguồn, mốc và trích dẫn; mốc WebRTC ghi rõ là tương đối")
    void evidenceKeepsIdsAndCitations() {
        AiContext context = build(null, FAIL_ICE);

        assertThat(context.evidenceIds()).containsExactly("EV01", "EV02", "EV03", "EV04");
        assertThat(context.payload().evidence()).extracting(AiContext.EvidenceItem::citation)
                .containsExactly("signaling#1", "signaling#2", "caller_endcall.log:40", "callee_webrtc.log:9");
        assertThat(context.payload().evidence().getLast().time()).contains("tương đối");
        assertThat(context.payload().evidence().getLast().description()).isEqualTo("ICE chuyển checking => failed");
    }

    @Test
    @DisplayName("chỉ số N/A giữ N/A kèm lý do, không thành 0; chỉ số proxy ghi rõ [proxy]")
    void metricsKeepNotAvailableAndProxy() {
        List<AiContext.MetricItem> metrics = build(null, FAIL_ICE).payload().metrics();

        assertThat(metrics).extracting(AiContext.MetricItem::value)
                .containsExactly("63 ms", "N/A (audio.packetsReceived = 0)", "1505 ms [proxy]");
        assertThat(metrics.getFirst().name()).isEqualTo("RTT (caller)");
    }

    @Test
    @DisplayName("danh sách log: signaling không gắn một leg, WebRTC ghi là mốc tương đối")
    void logsDescribeWhatIsPresent() {
        assertThat(build(null, FAIL_ICE).payload().logs()).containsExactly(
                new AiContext.LogFile("signaling", "SIGNALING", null, "ABSOLUTE"),
                new AiContext.LogFile("caller_endcall.log", "ENDCALL", "CALLER", "ABSOLUTE"),
                new AiContext.LogFile("callee_webrtc.log", "WEBRTC", "CALLEE", "RELATIVE"));
    }

    @Test
    @DisplayName("giới hạn dữ liệu trong context đúng là danh sách report sẽ in (thiếu end call log callee…)")
    void dataLimitationsMatchReport() {
        assertThat(build(null, FAIL_ICE).payload().dataLimitations())
                .anyMatch(l -> l.contains("Thiếu end call log của callee"));
    }

    @Test
    @DisplayName("rule SUCCESS không vấn đề -> issueCategory NONE, cùng từ vựng với schema đầu ra AI")
    void successWithoutIssueIsNone() {
        RuleVerdict success = new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.HIGH,
                "kết thúc bằng BYE", List.of());

        assertThat(build(null, success).payload().ruleVerdict().issueCategory()).isEqualTo(AiAnalysis.NO_ISSUE);
    }

    @Test
    @DisplayName("căn cứ G05 gồm số liệu của context, KHÔNG gồm số người dùng tự nêu trong câu hỏi")
    void groundingExcludesQuestion() {
        AiContext context = build("Gọi 7 phút thì rớt, vì sao?", FAIL_ICE);

        assertThat(context.groundingTexts()).contains("63 ms", "1505 ms [proxy]")
                .noneMatch(t -> t.contains("7 phút"));
    }

    @Test
    @DisplayName("Guardrails kiểm theo đúng context đã gửi: chép số của context -> qua, lặp số của câu hỏi -> G05")
    void guardrailsUseTheContextThatWasSent() {
        AiContext context = build("Gọi 7 phút thì rớt, vì sao?", FAIL_ICE);
        Guardrails guardrails = new Guardrails();

        AiAnalysis grounded = new AiAnalysis("FAIL", false, "ICE_FAILURE", "MEDIUM",
                "ICE thất bại phía callee.", List.of("EV04"), "RTT caller 63 ms nhưng ICE chuyển sang failed.", List.of());
        AiAnalysis echoesQuestion = new AiAnalysis("FAIL", false, "ICE_FAILURE", "MEDIUM",
                "Cuộc gọi rớt sau 7 phút.", List.of("EV04"), "ICE chuyển sang failed.", List.of());

        assertThat(guardrails.check(grounded, FAIL_ICE, context.evidenceIds(), METRICS, context.groundingTexts())
                .status()).isEqualTo(GuardrailResult.Status.PASSED);
        assertThat(guardrails.check(echoesQuestion, FAIL_ICE, context.evidenceIds(), METRICS, context.groundingTexts())
                .violations()).extracting(GuardrailResult.Violation::code).containsExactly("G05");
    }

    @Test
    @DisplayName("cùng input -> cùng context từng byte (Consistency, MVP mục 6.5)")
    void deterministic() throws Exception {
        assertThat(json(build("Phân tích giúp mình", FAIL_ICE))).isEqualTo(json(build("Phân tích giúp mình", FAIL_ICE)));
    }
}
