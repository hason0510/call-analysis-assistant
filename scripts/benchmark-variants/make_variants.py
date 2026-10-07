"""Sinh biến thể file cho benchmark `dev` (MVP mục 6.3: "tên file sai loại").

Biến thể thiếu file và khác Call-ID viết thẳng trong benchmark/dev-cases.yaml (bỏ bớt file, hoặc trỏ
đường dẫn sang thư mục cuộc khác) nên không cần file mới. Chỉ ca "tên file sai loại" cần một bản sao đổi
tên — script này tạo nó trong thư mục log, NGOÀI repo (data mẫu chứa IP công cộng thật, không commit).

    python scripts/benchmark-variants/make_variants.py ../ai20k_sample

Chạy lại bao nhiêu lần cũng được: ghi đè đúng các file đó.
"""
import shutil
import sys
from pathlib import Path

DE7DD314 = "success/DE7DD314-F432-45CB-BCB4-AE9103CC0919"

# (file nguồn, file đích) — tương đối với thư mục log
VARIANTS = [
    # End call log của caller mang tên WebRTC log: loại file phải nhận theo NỘI DUNG (T3, ca F02).
    (f"{DE7DD314}/caller_endcall.log", "_variants/F02-sai-loai/caller_webrtc.log"),
]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    root = Path(sys.argv[1]).resolve()
    for src, dst in VARIANTS:
        source = root / src
        if not source.is_file():
            print(f"Không có {source}", file=sys.stderr)
            return 1
        target = root / dst
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        print(f"{dst}  <=  {src}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
