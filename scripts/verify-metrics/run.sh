#!/usr/bin/env bash
# Kiểm chứng độc lập bộ chỉ số: tính tay từ file gốc (hand_calc.py, KHÔNG dùng code Java)
# rồi so với output `--metrics` của máy (compare.py). Kết quả mong đợi: "tổng so sánh: 280  lệch: 0".
#
# Cần: Elasticsearch đang chạy và đã import data (signaling lấy từ ES), Python 3.10+, JDK 21.
#   scripts/verify-metrics/run.sh ../ai20k_sample
set -euo pipefail
DATA="${1:?Cách dùng: run.sh <thư-mục-ai20k_sample>}"
OUT="$(mktemp -d)"
mkdir -p "$OUT/machine"
export PYTHONIOENCODING=utf-8

python "$(dirname "$0")/hand_calc.py" "$DATA" > "$OUT/hand.json"
for dir in "$DATA"/*/*/; do
  ./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--metrics=$dir" \
    > "$OUT/machine/$(basename "$dir").txt" 2>&1
done
python "$(dirname "$0")/compare.py" "$OUT"
echo "Chi tiết từng phép so: $OUT/compare.json"
