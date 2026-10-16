"""Bảng độ nhạy của ngưỡng chất lượng (góp ý mentor Sprint 2: "hiệu chỉnh ngưỡng packet loss và jitter bằng phân bố
trên các cuộc khỏe, đánh dấu UNVALIDATED, kèm bảng độ nhạy").

Đọc thẳng end call log, không import gì từ dự án Java — để đối chiếu độc lập với rule. Mỗi leg lấy một máy: máy có
nhiều gói âm thanh nhất (leg nhiều thiết bị, CLAUDE.md bẫy #21). Định nghĩa:
  - mất gói cả cuộc  = packetsLost / (packetsLost + packetsReceived) ở mẫu cuối có âm thanh (hai bộ đếm cộng dồn, bẫy #3)
  - mất gói một mẫu  = audio.packetLostPercent (tỉ lệ TRONG KHOẢNG giữa hai lần đo)
  - jitter / RTT TB  = trung bình audio.jitter (giây -> ms) / transport.currentRttMs trên các mẫu đã có âm thanh
  - luồng đủ dài     = packetsReceived > 500 (như Microsoft Teams CQD)

    python scripts/quality-thresholds/sensitivity.py ../ai20k_sample ../ai20k_new_dataset

In bảng Markdown: từng leg, rồi số leg / số cuộc bị gắn cờ theo từng phương án ngưỡng, tách theo nhóm thư mục
(success / fail / for_test / dataset khác). Chỉ success/ có nhãn "cuộc khỏe" — nhóm khác chỉ để quan sát.
"""
import sys
from decimal import Decimal
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

MIN_PACKETS = 500


def rows(path):
    headers, out = {}, []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        cols = line.split("\t")
        if cols[0].startswith("#H"):
            headers[cols[0][2:]] = cols
        elif cols[0] in headers:
            hdr = headers[cols[0]]
            out.append(dict(zip(hdr, cols + [""] * (len(hdr) - len(cols)))))
    return out


def dec(v):
    try:
        return Decimal((v or "").strip())
    except Exception:
        return None


def device(path):
    rs = rows(path)
    info = next((r for r in rs if r.get("#tag") == "info"), {})
    stats = [r for r in rs if r.get("#tag") == "stats"]
    live = [s for s in stats if (dec(s.get("audio.packetsReceived")) or 0) > 0]
    d = {"leg": info.get("role", "?"), "file": path.name, "samples": len(stats), "packets": 0}
    if not live:
        return d
    last = live[-1]
    lost, recv = dec(last.get("audio.packetsLost")) or 0, dec(last.get("audio.packetsReceived")) or 0
    sample_loss = [v for v in (dec(s.get("audio.packetLostPercent")) for s in stats) if v is not None]
    mos = [v for v in (dec(s.get("audio.audioMos")) for s in stats) if v is not None and v > 0]
    jit = [v * 1000 for v in (dec(s.get("audio.jitter")) for s in live) if v is not None]
    rtt = [v for v in (dec(s.get("transport.currentRttMs")) for s in live) if v is not None]
    d.update(packets=int(recv), whole_loss=lost * 100 / (lost + recv) if lost + recv else None,
             max_sample_loss=max(sample_loss) if sample_loss else None,
             samples_over5=sum(1 for v in sample_loss if v > 5),
             min_mos=min(mos) if mos else None, mos_low=sum(1 for v in mos if v < Decimal("3.5")),
             media_poor=sum(1 for s in stats if s.get("transport.hasMediaPoor") == "1"),
             avg_jitter=sum(jit) / len(jit) if jit else None, avg_rtt=sum(rtt) / len(rtt) if rtt else None)
    return d


def calls(roots):
    for root in roots:
        for call_dir in sorted(p for p in Path(root).rglob("*") if p.is_dir()):
            if "_variants" in call_dir.parts:
                continue
            logs = sorted(call_dir.glob("*endcall*.log"))
            if not logs:
                continue
            group = call_dir.parent.name if call_dir.parent != Path(root) else Path(root).name
            legs = {}
            for log in logs:
                d = device(log)
                if d["leg"] not in legs or d["packets"] > legs[d["leg"]]["packets"]:
                    legs[d["leg"]] = d
            yield group, call_dir.name[:8].upper(), [legs[k] for k in sorted(legs)]


def fmt(v, digits=2):
    return "—" if v is None else f"{v:.{digits}f}"


# Phương án ngưỡng: tên -> hàm(leg) trả True nếu leg bị gắn cờ. Chỉ leg có âm thanh mới xét.
def current_rule(d):
    return d["samples_over5"] > 0 or d["mos_low"] > 0 or d["media_poor"] > 0


def whole(th):
    return lambda d: d["whole_loss"] is not None and d["whole_loss"] >= th


def jitter(th):
    return lambda d: d["packets"] > MIN_PACKETS and d["avg_jitter"] is not None and d["avg_jitter"] >= th


RULES = [
    ("Hiện tại: một mẫu > 5% / MOS < 3,5 / hasMediaPoor", current_rule),
    ("Mất gói cả cuộc ≥ 1%", whole(1)),
    ("Mất gói cả cuộc ≥ 2%", whole(2)),
    ("Mất gói cả cuộc ≥ 3%", whole(3)),
    ("**Mất gói cả cuộc ≥ 5% (mentor)**", whole(5)),
    ("Mất gói cả cuộc ≥ 10% (Teams CQD)", whole(10)),
    ("Jitter TB ≥ 20 ms", jitter(20)),
    ("**Jitter TB ≥ 30 ms (mentor, Teams CQD)**", jitter(30)),
    ("Jitter TB ≥ 50 ms", jitter(50)),
]


def main(roots):
    data = list(calls(roots))
    print("## Từng leg (máy có nhiều gói âm thanh nhất)\n")
    print("| Nhóm | Cuộc | Leg | Gói nhận | Mất gói cả cuộc % | Mẫu cao nhất % | Mẫu > 5% | MOS thấp nhất | "
          "hasMediaPoor | Jitter TB ms | RTT TB ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for group, call, legs in data:
        for d in legs:
            if d["packets"] == 0:
                print(f"| {group} | {call} | {d['leg']} | 0 (chưa có âm thanh) | — | — | — | — | — | — | — |")
                continue
            print(f"| {group} | {call} | {d['leg']} | {d['packets']} | {fmt(d['whole_loss'])} | "
                  f"{fmt(d['max_sample_loss'], 1)} | {d['samples_over5']}/{d['samples']} | {fmt(d['min_mos'])} | "
                  f"{d['media_poor']} | {fmt(d['avg_jitter'], 1)} | {fmt(d['avg_rtt'], 0)} |")

    groups = sorted({g for g, _, _ in data}, key=lambda g: ["success", "fail", "for_test"].index(g)
                    if g in ("success", "fail", "for_test") else 9)
    print("\n## Số cuộc bị gắn cờ theo từng phương án\n")
    print("Một cuộc bị gắn cờ khi có ít nhất một leg vượt ngưỡng. Trong ngoặc: tên cuộc.\n")
    print("| Phương án | " + " | ".join(f"{g} ({sum(1 for x, _, _ in data if x == g)} cuộc)" for g in groups) + " |")
    print("|---|" + "---|" * len(groups))
    for name, rule in RULES:
        cells = []
        for g in groups:
            hit = [c for x, c, legs in data if x == g and any(d["packets"] > 0 and rule(d) for d in legs)]
            cells.append(f"{len(hit)}" + (f" ({', '.join(hit)})" if hit else ""))
        print(f"| {name} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main(sys.argv[1:] or ["../ai20k_sample"])
