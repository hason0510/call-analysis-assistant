"""Quét success/ + fail/ tìm dữ liệu nhạy cảm. Chỉ in số đếm + mẫu đã che một phần."""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(sys.argv[1])
FOLDERS = [p for g in ("success", "fail") for p in sorted((ROOT / g).iterdir()) if p.is_dir()]

CATS = {
    "ipv4": r"\b\d{1,3}(?:\.\d{1,3}){3}\b",
    "ipv6_full": r"\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){7}\b",
    "ipv6_short": r"\b(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,5}\b",
    "phone_vn": r"(?<![\w.])(?:\+?84|0)\d{9,10}(?![\w])",
    "email": r"[\w.+-]+@[\w-]+\.[\w.]+",
    "jwt": r"eyJ[\w-]+\.[\w-]+\.[\w-]+",
    "bearer": r"(?i)bearer\s+[\w.-]+",
    "ufrag": r"ufrag [\w+/]+",
    "u_p": r"u/p=[\w+/]+",
    "ice_pwd_sdp": r"a=ice-pwd:\S+",
    "ice_ufrag_sdp": r"a=ice-ufrag:\S+",
    "fingerprint": r"(?i)fingerprint[:= ]\s*(?:sha-\d+\s+)?[0-9A-F]{2}(?::[0-9A-F]{2}){15,}",
    "cname": r"(?i)cname[:= ]\S+",
    "candidate_str": r"candidate:\d+ \d+ \w+ \d+ [\da-fA-F:.]+ \d+ typ \w+",
    "raddr": r"raddr [\da-fA-F:.]+ rport \d+",
    "turn_url": r"(?i)turns?:[\w.\-\[\]:]+",
    "stun_url": r"(?i)stun:[\w.\-\[\]:]+",
    "http_url": r"https?://[^\s\"']+",
    "mac": r"\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b",
    "uuid": r"\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b",
    "kw_token": r"(?i)\b\w*(?:token|apikey|api_key|secret|password|passwd|credential|authorization|auth_key|private_key)\w*\b",
    "kw_device": r"(?i)\b(?:deviceId|device_id|imei|udid|idfa|idfv|serial|deviceName|deviceModel|model)\b",
}
PAT = {k: re.compile(v) for k, v in CATS.items()}


def mask(s):
    s = s.strip()
    return s if len(s) <= 6 else s[:4] + "…" + s[-2:]


counts = defaultdict(Counter)        # cat -> loại file -> số lần
samples = defaultdict(set)
files_with = defaultdict(set)
endcall_cols = Counter()
sig_keys = Counter()
json_keys_in_payload = Counter()

for call in FOLDERS:
    for f in sorted(call.iterdir()):
        text = f.read_text(encoding="utf-8", errors="replace")
        kind = "signaling" if f.name == "signaling.json" else ("endcall" if text.startswith("#H") else "webrtc")
        if kind == "endcall":
            for line in text.splitlines():
                if line.startswith("#H"):
                    endcall_cols.update(line.split("\t")[1:])
            for m in re.finditer(r'"([A-Za-z_][\w.]*)"\s*:', text):
                json_keys_in_payload[m.group(1)] += 1
        if kind == "signaling":
            for e in json.loads(text).get("events", []):
                sig_keys.update(e.keys())
        for cat, p in PAT.items():
            hits = p.findall(text) if p.groups == 0 else [m.group(0) for m in p.finditer(text)]
            if hits:
                counts[cat][kind] += len(hits)
                files_with[cat].add(f"{call.name[:8]}/{f.name}")
                for h in hits[:200]:
                    samples[cat].add(mask(h) if cat not in ("kw_token", "kw_device") else h)

print("=== SỐ LẦN XUẤT HIỆN (success/ + fail/, 13 cuộc) ===")
for cat in CATS:
    c = counts[cat]
    if c:
        print(f"{cat:15} tổng={sum(c.values()):6}  {dict(c)}  files={len(files_with[cat])}")
        print(f"{'':15} mẫu: {sorted(samples[cat])[:8]}")
    else:
        print(f"{cat:15} 0")

print("\n=== CỘT END CALL LOG (theo #Hn) ===")
print(sorted(endcall_cols))
print("\n=== KHOÁ JSON TRONG END CALL LOG (payload) — top 120 ===")
print(sorted(k for k, _ in json_keys_in_payload.most_common(400)))
print("\n=== KHOÁ CỦA EVENT SIGNALING ===")
print(dict(sig_keys))
