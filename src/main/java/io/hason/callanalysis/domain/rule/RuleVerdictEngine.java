package io.hason.callanalysis.domain.rule;

import io.hason.callanalysis.domain.event.LogSource;
import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Suy verdict tu tin hieu tho.
 *
 * Thu tu kiem tra co chu dich, va ca quan trong nhat la ca thu tu:
 * cuoc goi 2D9057AA trong data mau co signaling HOAN HAO (OK_ACK_OK, BYE, PAIR_PING
 * deu dan 33 giay) nhung ground truth la FAIL vi ICE that bai va 0 byte audio.
 * Rule chi xet signaling se ket luan SUCCESS va sai ngay tren tap dev.
 */
public class RuleVerdictEngine {

    public RuleVerdict decide(RuleSignals signals) {
        List<String> limitations = new ArrayList<>();

        if (!signals.hasSignaling()) {
            limitations.add("Khong co du lieu signaling nen khong dung duoc timeline cuoc goi");
            return unknown("Thieu nguon signaling", limitations);
        }
        if (signals.signalingTruncated()) {
            limitations.add("Ban export signaling bi cat bot, co the thieu su kien");
        }
        if (!signals.availableSources().contains(LogSource.ENDCALL)) {
            limitations.add("Khong co end call log nen khong kiem chung duoc chat luong media");
        }

        // 1. Chua bao gio goi toi callee
        if (!signals.sentInvite()) {
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.HIGH,
                    "Khong ton tai INVITE: server chua tung goi toi callee", limitations);
        }

        // 2. Loi cung o tang signaling
        if (signals.failHard()) {
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.HIGH,
                    "Xuat hien FAIL_HARD truoc khi thiet lap xong", limitations);
        }

        // 3. Huy truoc khi bat tay xong
        if (signals.cancelled() && !signals.reachedConfirmed()) {
            limitations.add("CANCEL chua phan biet duoc la nguoi dung chu dong huy"
                    + " hay he thong timeout — xem knownAmbiguity cua SIGNALING_FAILURE");
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.MEDIUM,
                    "Co CANCEL va khong dat OK_ACK_OK", limitations);
        }

        // 4. Bat tay khong hoan tat vi ly do khac
        if (!signals.reachedConfirmed()) {
            return fail(IssueCategory.SIGNALING_FAILURE, ConfidenceLevel.MEDIUM,
                    "Khong dat OK_ACK_OK nen cuoc goi chua duoc thiet lap", limitations);
        }

        // 5. Da thiet lap nhung media khong chay — ca 2D9057AA
        if (signals.mediaFailed()) {
            return fail(IssueCategory.ICE_FAILURE,
                    signals.iceEverFailed() ? ConfidenceLevel.HIGH : ConfidenceLevel.MEDIUM,
                    mediaFailureReason(signals), limitations);
        }

        // 6. Thiet lap duoc, media chay, nhung chat luong kem
        if (signals.qualityDegraded()) {
            return new RuleVerdict(Verdict.SUCCESS, true, IssueCategory.NETWORK_PACKET_LOSS,
                    ConfidenceLevel.LOW,
                    "Cuoc goi thiet lap va ket thuc binh thuong nhung chi so chat luong vuot nguong",
                    withUnvalidatedThresholdNote(limitations));
        }

        // 7. Binh thuong
        if (!signals.terminatedNormally()) {
            limitations.add("Khong thay BYE nen chua xac nhan duoc cuoc goi ket thuc binh thuong");
            return new RuleVerdict(Verdict.SUCCESS, false, null, ConfidenceLevel.MEDIUM,
                    "Da dat OK_ACK_OK va khong co dau hieu loi media", limitations);
        }

        return new RuleVerdict(Verdict.SUCCESS, false, null,
                confidenceForSuccess(signals),
                "Da dat OK_ACK_OK, ket thuc bang BYE, khong co dau hieu loi media", limitations);
    }

    private static String mediaFailureReason(RuleSignals signals) {
        if (signals.iceEverFailed() && !signals.iceEverConnected()) {
            return "ICE chuyen sang failed va khong bao gio dat connected";
        }
        if (signals.noMediaBytes()) {
            return "Khong mot byte audio nao duoc truyen du da thiet lap cuoc goi";
        }
        return "transport.hasMediaFail bat co bao loi media";
    }

    /**
     * Do tin cay suy bang logic tat dinh (MVP muc 7.2), khong de AI sinh so.
     * Du ca ba nguon log va khong co gioi han du lieu thi moi HIGH.
     */
    private static ConfidenceLevel confidenceForSuccess(RuleSignals signals) {
        if (signals.availableSources().size() == 3 && signals.iceEverConnected()) {
            return ConfidenceLevel.HIGH;
        }
        return signals.hasClientLog() ? ConfidenceLevel.MEDIUM : ConfidenceLevel.LOW;
    }

    private static List<String> withUnvalidatedThresholdNote(List<String> limitations) {
        List<String> all = new ArrayList<>(limitations);
        all.add("Nguong phat hien chat luong kem CHUA kiem chung duoc:"
                + " tap data mau khong co cuoc goi nao bi suy giam chat luong");
        return all;
    }

    private static RuleVerdict fail(IssueCategory category, ConfidenceLevel confidence,
                                    String reasoning, List<String> limitations) {
        return new RuleVerdict(Verdict.FAIL, false, category, confidence, reasoning, limitations);
    }

    private static RuleVerdict unknown(String reasoning, List<String> limitations) {
        return new RuleVerdict(Verdict.UNKNOWN, false, IssueCategory.UNKNOWN,
                ConfidenceLevel.LOW, reasoning, limitations);
    }
}
