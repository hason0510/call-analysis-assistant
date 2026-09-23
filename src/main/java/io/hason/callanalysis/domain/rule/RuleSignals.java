package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;

import java.util.Set;

/**
 * Cac tin hieu THO rut tu timeline, tach khoi ket luan.
 *
 * Tach nhu vay de Sprint 2 khi Guardrails doi chieu verdict cua AI voi rule verdict
 * co the chi ra LECH O TIN HIEU NAO, thay vi chi bao "hai ben khac nhau".
 */
public record RuleSignals(
        boolean sentInvite,
        boolean reachedConfirmed,
        boolean terminatedNormally,
        boolean cancelled,
        boolean failHard,
        boolean iceEverConnected,
        boolean iceEverFailed,
        boolean mediaFailFlag,
        boolean noMediaBytes,
        boolean qualityDegraded,
        boolean signalingTruncated,
        Set<LogSource> availableSources
) {

    public RuleSignals {
        availableSources = availableSources == null ? Set.of() : Set.copyOf(availableSources);
    }

    public boolean hasSignaling() {
        return availableSources.contains(LogSource.SIGNALING);
    }

    public boolean hasClientLog() {
        return availableSources.contains(LogSource.ENDCALL)
                || availableSources.contains(LogSource.WEBRTC);
    }

    /** Co bang chung media that bai tu bat ky nguon nao. */
    public boolean mediaFailed() {
        return (iceEverFailed && !iceEverConnected) || mediaFailFlag || noMediaBytes;
    }
}
