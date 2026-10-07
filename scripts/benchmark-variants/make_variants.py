"""Tạo file biến thể cho ca F02 "tên file sai loại" của benchmark `dev`.

Nói gọn: chép một file log có sẵn rồi ĐẶT SAI TÊN — end call log của caller mang tên `caller_webrtc.log` —
để kiểm hệ thống nhận loại file theo NỘI DUNG chứ không theo tên (MVP mục 6.3 đòi tập `dev` có biến thể
"tên file sai loại"; MVP mục 6.4 có ca F02 "Tên file không khớp nội dung").

    nguồn : <thư mục log>/success/DE7DD314-…/caller_endcall.log
    đích  : <thư mục log>/_variants/F02-sai-loai/caller_webrtc.log     (nội dung giống hệt, chỉ khác tên)

Vì sao phải tạo file:
  - Data mẫu có nhãn không có sẵn file nào bị đặt sai loại.
  - Hai biến thể còn lại không cần file mới: "thiếu file" là không liệt kê file đó trong case; "file khác
    Call-ID" là trỏ đường dẫn sang thư mục cuộc gọi khác (xem benchmark/dev-cases.yaml).

Vì sao file nằm NGOÀI repo: nó là bản sao data mẫu, mà data mẫu chứa IP công cộng thật nên không commit.
Script thì nằm trong repo, nên tạo lại được ở bất kỳ máy nào:

    python scripts/benchmark-variants/make_variants.py ../ai20k_sample

Chạy MỘT LẦN trước khi chạy benchmark `dev`; thiếu bước này thì case-016 nằm ở mục "Case không chạy được"
của báo cáo runner (các case khác vẫn chạy). Chạy lại bao nhiêu lần cũng được: chỉ ghi đè đúng file đó.
Chỉ phục vụ tập `dev`; bộ `held-out` của Mentor không cần.
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
