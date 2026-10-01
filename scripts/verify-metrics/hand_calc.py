"""Tính tay độc lập các chỉ số Core còn thiếu, đọc thẳng file gốc trong ai20k_sample.

Không import gì từ dự án Java. Định nghĩa lấy từ MVP 4.3 + các quy ước đã ghi trong CLAUDE.md:
  - Với tới callee    = INVITE đầu tiên -> TRYING đầu tiên (signaling), trừ ở độ chính xác nano rồi cắt về ms
  - Gửi lại INVITE/BYE = số cụm thời gian (khoảng cách > 250 ms tách cụm) - 1
  - No sessions found  = đếm chuỗi này trong MỌI file của cuộc gọi
  - MOS / loss / RTT / jitter: bản ghi `endcall` (không có thì `stats` cuối) trong end call log
      MOS    = audio.audioMos
      loss   = packetsLost / (packetsLost + packetsReceived) * 100, làm tròn 2 chữ số; cao nhất = max packetLostPercent của stats
      RTT    = transport.currentRttMs
      jitter = audio.jitter (giây) * 1000
      packetsReceived = 0 -> N/A (MOS/loss/jitter); localStunResponse = 0 -> N/A (RTT)
  - ICE đạt được: WebRTC log, "IceConnectionState A => B"; từng connected/completed -> connected, không thì trạng thái cuối
    Leg của file WebRTC: "SetLocalDescription: offer" = caller, "answer" = callee
"""
import json
import re
import sys
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(sys.argv[1])
WINDOW_NS = 250_000_000


def ts_ns(value: str) -> int:
    m = re.match(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z$", value)
    base = datetime.strptime(m.group(1), "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
    frac = (m.group(2) or "").ljust(9, "0")[:9]
    return int(base.timestamp()) * 1_000_000_000 + int(frac)


def detect(path: Path) -> str:
    head = path.read_text(encoding="utf-8", errors="replace")[:2000]
    if path.name.endswith(".json"):
        return "signaling"
    if head.startswith("#H1"):
        return "endcall"
    if re.search(r"^(\S+\.(cc|mm|h): )?\[\d+:\d{3}\]\[\d+\]", head, re.M):
        return "webrtc"
    return "unknown"


def signaling_metrics(path: Path):
    events = json.loads(path.read_text(encoding="utf-8"))["events"]
    by_cmd = {}
    for e in events:
        by_cmd.setdefault(e.get("cmd"), []).append(ts_ns(e["@timestamp"]))
    out = {}

    inv, tr = sorted(by_cmd.get("INVITE", [])), sorted(by_cmd.get("TRYING", []))
    if not inv:
        out["reach"] = ("N/A", "không đạt tới INVITE")
    elif not tr:
        out["reach"] = ("N/A", "không đạt tới TRYING")
    else:
        out["reach"] = ((tr[0] - inv[0]) // 1_000_000, None)
        out["reach_raw"] = (inv[0], tr[0])

    for cmd in ("INVITE", "BYE"):
        t = sorted(by_cmd.get(cmd, []))
        if not t:
            out[cmd] = ("N/A", f"không đạt tới {cmd}")
            continue
        clusters = [[t[0]]]
        for a, b in zip(t, t[1:]):
            if b - a > WINDOW_NS:
                clusters.append([])
            clusters[-1].append(b)
        out[cmd] = (len(clusters) - 1, None)
        out[cmd + "_clusters"] = [(c[0], len(c)) for c in clusters]
    return out


def endcall_rows(path: Path):
    headers, rows = {}, []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        cols = line.split("\t")
        if cols[0].startswith("#H"):
            headers[cols[0][2:]] = cols
            continue
        hdr = headers.get(cols[0])
        if hdr:
            rows.append((dict(zip(hdr, cols + [""] * (len(hdr) - len(cols)))), line))
    return rows


def dec(v):
    try:
        return Decimal(v.strip())
    except Exception:
        return None


def quality(path: Path):
    rows = endcall_rows(path)
    info = next((r for r, _ in rows if r.get("#tag") == "info"), {})
    leg = info.get("role", "?")
    stats = [r for r, _ in rows if r.get("#tag") == "stats"]
    summary = next((r for r, _ in rows if r.get("#tag") == "endcall"), None) or (stats[-1] if stats else None)
    res = {"leg": leg}
    if summary is None:
        return res | {k: ("N/A", "không có bản ghi chỉ số") for k in ("mos", "loss", "rtt", "jitter")}
    recv = dec(summary.get("audio.packetsReceived", ""))
    lost = dec(summary.get("audio.packetsLost", ""))
    stun = dec(summary.get("transport.localStunResponse", ""))
    no_audio = recv is not None and recv == 0
    res["raw"] = {k: summary.get(k) for k in ("audio.audioMos", "audio.packetsLost", "audio.packetsReceived",
                                              "transport.currentRttMs", "transport.rttMs", "audio.jitter",
                                              "transport.localStunResponse", "audio.packetLostPercent")}
    res["mos"] = ("N/A", "packetsReceived = 0") if no_audio else (dec(summary["audio.audioMos"]), None)
    if no_audio:
        res["loss"] = ("N/A", "packetsReceived = 0")
    else:
        pct = (lost * 100 / (lost + recv)).quantize(Decimal("0.01"), ROUND_HALF_UP)
        peak = max((dec(s.get("audio.packetLostPercent", "")) or Decimal(0)) for s in stats) if stats else Decimal(0)
        res["loss"] = ((pct, peak if peak > 0 else None), None)
    res["rtt"] = ("N/A", "localStunResponse = 0") if (stun is not None and stun == 0) \
        else (dec(summary["transport.currentRttMs"]), None)
    res["jitter"] = ("N/A", "packetsReceived = 0") if no_audio else (dec(summary["audio.jitter"]) * 1000, None)
    return res


def ice(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    sdp = re.search(r"SetLocalDescription: (offer|answer)", text)
    leg = {"offer": "caller", "answer": "callee"}.get(sdp.group(1)) if sdp else None
    states = re.findall(r"IceConnectionState\s+(\w+)\s*=>\s*(\w+)", text)
    if not states:
        return leg, ("N/A", "không có chuyển trạng thái ICE"), states
    tos = [b for _, b in states]
    reached = "connected" if any(s in ("connected", "completed") for s in tos) else tos[-1]
    return leg, (reached, None), states


def main():
    result = {}
    for call in sorted(p for g in ROOT.iterdir() if g.is_dir() for p in g.iterdir() if p.is_dir()):
        r = {"group": call.parent.name, "files": {}}
        nsf = 0
        for f in sorted(call.iterdir()):
            kind = detect(f)
            r["files"][f.name] = kind
            nsf += len(re.findall(r"no sessions? found", f.read_text(encoding="utf-8", errors="replace"), re.I))
            if kind == "signaling":
                r["sig"] = signaling_metrics(f)
            elif kind == "endcall":
                q = quality(f)
                r.setdefault("q", {})[q["leg"]] = q | {"file": f.name}
            elif kind == "webrtc":
                leg, st, raw = ice(f)
                r.setdefault("ice", []).append({"file": f.name, "leg": leg, "state": st, "n": len(raw)})
        r["no_sessions_found_text"] = nsf
        result[call.name] = r
    print(json.dumps(result, default=str, ensure_ascii=False, indent=1))


main()
