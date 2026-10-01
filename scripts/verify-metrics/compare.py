import json, re, sys
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
S = Path(sys.argv[1]); hand = json.loads((S/"hand.json").read_text(encoding="utf-8"))
def machine(cid):
    f = next((S/"machine").glob(cid[:8] + "*.txt"))
    m = {}
    for line in f.read_text(encoding="utf-8-sig").splitlines():
        # bỏ tiền tố log "17:28:22 INFO  MetricsCommand   " (pattern console trong application.yml)
        line = re.sub(r"^\d\d:\d\d:\d\d\s+[A-Z]+\s+\w+\s+", "", line)
        mm = re.match(r"^(.+?)\s{2,}(\S.*)$", line)
        if mm: m[mm.group(1).strip()] = mm.group(2).strip()
    return m
nums = lambda s: [Decimal(x) for x in re.findall(r"-?\d+(?:\.\d+)?", s)]
r2 = lambda d: Decimal(d).quantize(Decimal("0.01"), ROUND_HALF_UP)
rows, bad = [], 0
for cid, h in hand.items():
    m = machine(cid); sig = h.get("sig", {})
    exp = {}
    def put(label, val):  # val: ("N/A", reason) | list of Decimals | text
        exp[label] = val
    for label, key in (("Thời gian với tới callee","reach"),("Số lần gửi lại INVITE","INVITE"),("Số lần gửi lại BYE","BYE")):
        v, why = sig[key]; put(label, ("N/A", why) if v == "N/A" else [Decimal(v)])
    put("Số lần No sessions found", ("N/A", f"chuỗi xuất hiện {h['no_sessions_found_text']} lần trong mọi file"))
    for leg in ("caller","callee"):
        q = h.get("q", {}).get(leg)
        for label, k in (("MOS","mos"),("Packet loss","loss"),("RTT","rtt"),("Jitter","jitter")):
            L = f"{label} ({leg})"
            if not q: put(L, ("N/A","không có end call log")); continue
            v, why = q[k]
            if v == "N/A": put(L, ("N/A", why))
            elif k == "loss": put(L, [r2(v[0])] + ([r2(v[1])] if v[1] else []))
            else: put(L, [Decimal(v)])
        ices = [i for i in h.get("ice", []) if i["leg"] == leg]
        L = f"Trạng thái ICE đạt được ({leg})"
        if ices: st = ices[0]["state"]; put(L, ("N/A", st[1]) if st[0]=="N/A" else st[0])
        else:
            nosdp = [i for i in h.get("ice", []) if i["leg"] is None]
            put(L, ("N/A", "không có WebRTC log leg này" + (" (có file WebRTC không có SDP, 0 chuyển trạng thái)" if nosdp else "")))
    for L, e in exp.items():
        mv = m.get(L, "<thiếu>")
        if isinstance(e, tuple): ok = mv.startswith("N/A")
        elif isinstance(e, list): ok = (not mv.startswith("N/A")) and [Decimal(x).normalize() for x in e] == [x.normalize() for x in nums(mv)]
        else: ok = mv == e
        bad += not ok
        rows.append((cid[:8], L, e if not isinstance(e, list) else "/".join(str(x.normalize()) for x in e), mv, "OK" if ok else "LỆCH"))
(S/"compare.json").write_text(json.dumps(rows, default=str, ensure_ascii=False), encoding="utf-8")
print("tổng so sánh:", len(rows), " lệch:", bad)
for r in rows:
    if r[4] != "OK": print(r)
from collections import Counter
print(Counter((r[1].split(" (")[0], "N/A" if str(r[3]).startswith("N/A") else "có số") for r in rows))
