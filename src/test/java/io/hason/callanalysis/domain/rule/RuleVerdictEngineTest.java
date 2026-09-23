package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVerdictEngineTest {

    private final RuleVerdictEngine engine = new RuleVerdictEngine();

    private static final Set<LogSource> ALL_SOURCES =
            Set.of(LogSource.SIGNALING, LogSource.ENDCALL, LogSource.WEBRTC);

    /** Builder gon cho tung kich ban; mac dinh la cuoc goi khoe manh. */
    private static RuleSignals signals(java.util.function.Consumer<Builder> tweak) {
        Builder b = new Builder();
        tweak.accept(b);
        return b.build();
    }

    private static final class Builder {
        boolean sentInvite = true;
        boolean reachedConfirmed = true;
        boolean terminatedNormally = true;
        boolean cancelled = false;
        boolean failHard = false;
        boolean iceEverConnected = true;
        boolean iceEverFailed = false;
        boolean mediaFailFlag = false;
        boolean noMediaBytes = false;
        boolean qualityDegraded = false;
        boolean signalingTruncated = false;
        Set<LogSource> sources = ALL_SOURCES;

        RuleSignals build() {
            return new RuleSignals(sentInvite, reachedConfirmed, terminatedNormally, cancelled,
                    failHard, iceEverConnected, iceEverFailed, mediaFailFlag, noMediaBytes,
                    qualityDegraded, signalingTruncated, sources);
        }
    }

    @Test
    @DisplayName("cuoc goi khoe manh du ba nguon -> SUCCESS, do tin cay HIGH")
    void healthyCallIsSuccess() {
        RuleVerdict v = engine.decide(signals(b -> { }));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.qualityFlag()).isFalse();
        assertThat(v.issueCategory()).isNull();
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("chua tung gui INVITE -> FAIL + SIGNALING_FAILURE")
    void neverSentInviteIsSignalingFailure() {
        // Tai hien 1B009D42 va D114749E: chi co INIT_CALL roi im lang.
        RuleVerdict v = engine.decide(signals(b -> {
            b.sentInvite = false;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
            b.sources = Set.of(LogSource.SIGNALING);
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(v.reasoning()).contains("INVITE");
    }

    @Test
    @DisplayName("FAIL_HARD -> FAIL + SIGNALING_FAILURE")
    void failHardIsSignalingFailure() {
        // Tai hien 0EC7B700: INVITE gui 15 lan roi FAIL_HARD.
        RuleVerdict v = engine.decide(signals(b -> {
            b.failHard = true;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.SIGNALING_FAILURE);
    }

    @Test
    @DisplayName("CANCEL truoc khi bat tay xong -> FAIL, do tin cay MEDIUM vi con mo ho")
    void cancelBeforeConfirmedIsAmbiguousFailure() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.cancelled = true;
            b.reachedConfirmed = false;
            b.terminatedNormally = false;
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("CANCEL"));
    }

    @Test
    @DisplayName("SIGNALING HOAN HAO nhung ICE that bai -> van phai FAIL")
    void perfectSignalingWithFailedIceIsStillFailure() {
        // Day la ca 2D9057AA: OK_ACK_OK + BYE + PAIR_PING deu 33 giay, nhung ICE failed
        // va 0 byte audio. Rule chi xet signaling se ket luan SUCCESS va sai.
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.iceEverFailed = true;
            b.mediaFailFlag = true;
            b.noMediaBytes = true;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    @DisplayName("khong mot byte audio nao du da thiet lap -> FAIL du chua thay ICE failed")
    void zeroMediaBytesAloneIsEnoughToFail() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.iceEverConnected = false;
            b.noMediaBytes = true;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.ICE_FAILURE);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
    }

    @Test
    @DisplayName("chat luong kem -> SUCCESS kem co, kem ghi chu nguong chua kiem chung")
    void degradedQualityIsSuccessWithFlag() {
        RuleVerdict v = engine.decide(signals(b -> b.qualityDegraded = true));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.qualityFlag()).isTrue();
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.NETWORK_PACKET_LOSS);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("CHUA kiem chung"));
    }

    @Test
    @DisplayName("khong co signaling -> UNKNOWN, khong doan bua")
    void missingSignalingIsUnknown() {
        RuleVerdict v = engine.decide(signals(b -> b.sources = Set.of(LogSource.WEBRTC)));

        assertThat(v.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(v.issueCategory()).isEqualTo(IssueCategory.UNKNOWN);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("signaling"));
    }

    @Test
    @DisplayName("thieu end call log -> SUCCESS nhung ha do tin cay va ghi gioi han du lieu")
    void missingClientLogLowersConfidence() {
        RuleVerdict v = engine.decide(signals(b -> {
            b.sources = Set.of(LogSource.SIGNALING);
            b.iceEverConnected = false;
        }));

        assertThat(v.verdict()).isEqualTo(Verdict.SUCCESS);
        assertThat(v.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("end call log"));
    }

    @Test
    @DisplayName("ban export signaling bi cat bot duoc ghi vao gioi han du lieu")
    void truncatedExportIsRecorded() {
        // Cuoc goi DE7DD314: truncated=true, tra ve 200/201 event.
        RuleVerdict v = engine.decide(signals(b -> b.signalingTruncated = true));

        assertThat(v.dataLimitations()).anyMatch(s -> s.contains("cat bot"));
    }
}
