"""Kiểm chứng ĐỘC LẬP thư mục đã làm sạch: không dùng regex của sanitizer.

1. Lấy mọi giá trị nhạy cảm GỐC từ dữ liệu thô (bằng parser riêng: JSON của signaling, cột TSV,
   tên=giá trị, SDP, Cand[]) rồi tra xem còn giá trị nào xuất hiện trong bản sạch không.
2. Quét bản sạch bằng ipaddress của Python (không phải regex IP của sanitizer).
3. Quét các dạng bí mật / địa chỉ còn sót.
"""
import ipaddress
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

RAW, CLEAN = Path(sys.argv[1]), Path(sys.argv[2])
GROUPS = ("success", "fail")

ID_KEYS = {"appUserId": "userId", "callUserId": "userId", "partnerAppUserId": "userId",
           "partnerCallUserId": "userId", "deviceId": "deviceId", "csid": "session",
           "sessionId": "session", "callSessionId": "session", "fromTag": "session",
           "toTag": "session", "service": "service"}

secrets = defaultdict(set)    # loại -> tập giá trị gốc


def add(kind, v):
    v = (v or "").strip()
    if len(v) >= 6 and not v.startswith("["):
        secrets[kind].add(v)


for g in GROUPS:
    for f in (RAW / g).rglob("*"):
        if not f.is_file():
            continue
        t = f.read_text(encoding="utf-8", errors="replace")
        if f.name == "signaling.json":
            for e in json.loads(t)["events"]:
                for k, kind in ID_KEYS.items():
                    if k in e:
                        add(kind, str(e[k]))
        if t.startswith("#H"):
            headers = {}
            for line in t.split("\n"):
                cols = line.rstrip("\r").split("\t")
                if cols[0].startswith("#H"):
                    headers[cols[0][2:]] = cols
                elif cols[0] in headers:
                    for name, val in zip(headers[cols[0]][1:], cols[1:]):
                        if name in ID_KEYS:
                            add(ID_KEYS[name], val)
        for m in re.finditer(r"\b(deviceId|csid)=([A-Za-z0-9]+)", t):
            add(ID_KEYS[m.group(1)], m.group(2))
        for m in re.finditer(r"a=ice-pwd:([A-Za-z0-9+/]+)", t):
            add("ice-pwd", m.group(1))
        for m in re.finditer(r"u/p=([A-Za-z0-9+/]+)", t):
            add("ice-pwd", m.group(1))
        for m in re.finditer(r"Cand\[[^\]]*:([A-Za-z0-9+/]{4,}):([A-Za-z0-9+/]{16,}):\d+:\d+:\d+\]", t):
            add("ice-pwd", m.group(2))
        for m in re.finditer(r"fingerprint:sha-256 ([0-9A-F:]+)", t):
            add("fingerprint", m.group(1))

clean_text = {f: f.read_text(encoding="utf-8") for g in GROUPS for f in (CLEAN / g).rglob("*") if f.is_file()}
blob = "\n".join(clean_text.values())

print(f"Bản sạch: {len(clean_text)} file")
print("=== 1. Giá trị nhạy cảm GỐC còn sót trong bản sạch (so theo ranh giới chữ/số) ===")
for kind, values in sorted(secrets.items()):
    # "C8CF631E-0" (fromTag) là chuỗi con của Call-ID "C8CF631E-0C6B-…" — không tính là sót
    left = [v for v in values if re.search(r"(?<![A-Za-z0-9])" + re.escape(v) + r"(?![A-Za-z0-9])", blob)]
    print(f"  {kind:12} gốc={len(values):5}  còn sót={len(left)}  {[v[:4] + '…' for v in left[:5]]}")

print("\n=== 2. Quét IP bằng thư viện ipaddress (không dùng regex của sanitizer) ===")
kinds = Counter()
samples = defaultdict(list)
token = re.compile(r"[0-9A-Fa-f:.]{7,}")
for f, t in clean_text.items():
    for m in token.finditer(t):
        s = m.group(0).strip(".:")
        # "7::4:900:0" trong Cand[…:ufrag::netId:cost:gen] parse được thành IPv6 nhưng không phải địa
        # chỉ: chỉ nhận chuỗi có ít nhất một nhóm hex >= 2 ký tự trước "::".
        try:
            ip = ipaddress.ip_address(s.split("/")[0])
        except ValueError:
            continue
        if ip.version == 6 and not re.match(r"^[0-9A-Fa-f]{2,4}:", s):
            continue
        k = f"ipv{ip.version}-{'private' if ip.is_private else 'loopback/unspec' if ip.is_unspecified or ip.is_loopback else 'public'}"
        kinds[k] += 1
        if len(samples[k]) < 3:
            samples[k].append(f"{f.parent.name[:8]}/{f.name}: …{t[max(0, m.start()-30):m.end()+5]}…")
print("  ", dict(kinds) or "không còn IP nào")
for k, v in samples.items():
    for s in v:
        print("   ", k, s.replace("\n", "\\n"))

print("\n=== 3. Bí mật / địa chỉ dạng chuỗi còn sót ===")
checks = {
    "JWT": r"eyJ[\w-]+\.[\w-]+\.[\w-]+",
    "Bearer": r"(?i)bearer\s+[A-Za-z0-9]",
    "a=ice-pwd giá trị": r"a=ice-pwd:(?!\[)\S",
    "ufrag giá trị": r"ufrag (?!\[)[\w+/]+",
    "u/p= giá trị": r"u/p=(?!\[)[\w+/]+",
    "Cand[] ufrag:pwd": r":(?:host|srflx|prflx|relay):[^\]\n]*?:\d+:(?!\[)[A-Za-z0-9+/]{4,}:[A-Za-z0-9+/]{16,}:",
    "URL ngoài webrtc/ietf": r"https?://(?!(?:www\.)?(?:webrtc|ietf)\.org)[^\s\"]+",
    "fingerprint hex": r"(?:[0-9A-F]{2}:){15,}[0-9A-F]{2}",
    "IPv4 che dở a.b.c.x": r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.x\b",
    "IPv6 che dở …:x:x": r"\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4})+(?::x)+\b",
}
for name, pat in checks.items():
    n = len(re.findall(pat, blob))
    print(f"  {name:22} {n}")
