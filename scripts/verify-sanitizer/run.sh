#!/usr/bin/env bash
# Kiểm chứng ĐỘC LẬP Sanitizer (T7): làm sạch toàn bộ log thô của success/ + fail/ bằng
# --sanitize-dir, rồi quét bản sạch bằng script Python KHÔNG dùng chung regex với sanitizer.
# Kết quả mong đợi: "còn sót=0" ở mọi loại, "không còn IP nào", mọi dòng mục 3 bằng 0.
#
#   scripts/verify-sanitizer/run.sh ../ai20k_sample
set -euo pipefail
DATA="${1:?Cách dùng: run.sh <thư-mục-ai20k_sample>}"
OUT="$(mktemp -d)"
export PYTHONIOENCODING=utf-8
for g in success fail; do
  ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--sanitize-dir=$DATA/$g --out=$OUT/$g"
done
python "$(dirname "$0")/verify_sanitized.py" "$DATA" "$OUT"
