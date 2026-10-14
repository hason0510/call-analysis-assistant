#!/usr/bin/env bash
# Kiểm chứng ĐỘC LẬP Sanitizer (T7): làm sạch toàn bộ log thô của success/ + fail/ bằng
# --sanitize-dir, rồi quét bản sạch bằng script Python KHÔNG dùng chung regex với sanitizer.
# Kết quả mong đợi: "còn sót=0" ở mọi loại, "không còn IP nào", mọi dòng mục 3 bằng 0.
#
#   scripts/verify-sanitizer/run.sh ../ai20k_sample
set -euo pipefail
DATA="${1:?Cách dùng: run.sh <thư-mục-ai20k_sample>}"
# Thư mục ra TƯƠNG ĐỐI trong target/: trên Git Bash (Windows), "$(mktemp -d)" cho /tmp/…, mà Java nhận nguyên chuỗi đó
# (nằm trong -Dspring-boot.run.arguments) và hiểu là D:	mp\…, còn Python nhận bản đã bị Git Bash đổi sang thư mục
# Temp của Windows — hai bên nhìn hai thư mục khác nhau, script báo "còn sót=0" trên 0 file.
OUT="target/verify-sanitizer"
rm -rf "$OUT"
export PYTHONIOENCODING=utf-8
for g in success fail; do
  ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--sanitize-dir=$DATA/$g --out=$OUT/$g"
done
python "$(dirname "$0")/verify_sanitized.py" "$DATA" "$OUT"
