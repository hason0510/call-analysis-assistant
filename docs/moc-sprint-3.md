# Mốc so sánh Sprint 3 (P0.3)

> Chạy ngày **2026-10-10**, trên nhánh `sprint-3/p0-baseline` (code `main` @ `4906595` + dấu vân tay prompt và
> `--rescore`, không đổi logic phân tích). Mọi cải tiến của Sprint 3 so với lượt này (MVP 7.1: *"so với kết quả Sprint 2"*).

## Cấu hình

| | |
|---|---|
| Lệnh | `--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample --repeat=5 --out=benchmark/results/moc-sprint-3` |
| Bộ case | `benchmark/dev-cases.yaml`: 18 case, 63 câu hỏi × 5 lần = **315 lần chạy** (280 lần ra report, 35 lần câu ngoài phạm vi) |
| Signaling | có cho 13/13 Call-ID (Elasticsearch 1 059 document) |
| AI | `gpt-4o-mini`, temperature 0 |
| **Prompt AI** | `analyze-call.system.txt 7e4465ebd5fa`, `ai-analysis.schema.json caf500c08777`, `parse-request.system.txt bd618652241b`, `request-intent.schema.json fe0dcba2e2a6` |
| Kết quả | `benchmark/results/moc-sprint-3/evaluation-20261010-171638.md` và `.json` |
| Thời gian · chi phí | 19 phút · 1 174 695 token vào + 46 939 token ra trên 595 lời gọi ≈ **0,20 USD** (giá `gpt-4o-mini` 0,15 / 0,60 USD mỗi triệu token) |

## So với lượt chính thức của Sprint 2

Lượt Sprint 2: `../eval-scratch/dev-new-5x/evaluation-20261006-112122.json` (2026-10-06, 315 lần chạy), **chấm lại**
bằng `--rescore` theo đáp án hiện tại để hai cột cùng thước đo. Lượt đó chạy trước khi thêm quy tắc 4b vào prompt
phân tích (câu hỏi về khoảng thời gian: chép đúng chỉ số, không tự trừ mốc thời gian).

| Metric (MVP 6.5) | Sprint 2 (06/10, chấm lại) | Mốc Sprint 3 (10/10) | Target |
|---|---|---|---|
| Verdict Accuracy | 100% (280/280) | 100% (280/280) | ≥ 85% (held-out) |
| Metric Correctness | 100% | 100% | 100% |
| Security Leakage input / output | 0/7 005 · 0 | 0/7 005 · 0 | 0 |
| Issue Category Accuracy | N/A — chưa có nhãn | N/A — chưa có nhãn | ≥ 70% |
| Consistency | 100%, 17/17 case giống hệt | 100%, 17/17 case giống hệt | ≥ 95% |
| Template Compliance | 100% | 100% | 100% |
| Intent Accuracy | 100% (290/290)¹ | 100% (290/290) | ≥ 90% |
| Unsupported Claim — AI trước Guardrails | **2,9% (8/280)** | **0% (0/280)** | 0% |
| Unsupported Claim — report cuối | 0% | 0% | 0% |
| Report lùi về rule (degraded) | 2,9% (8/280, G05) | 0% | — |
| Pipeline Success Rate | 100% | 100% | ≥ 95% |
| Latency P50 / P95 / max | 3 846 / 8 360 / 12 251 ms | 3 428 / 8 107 / 14 007 ms | — |
| Token mỗi lời gọi phân tích | không ghi (file cũ)² | 3 172 vào + 149 ra | — |

¹ Lúc chạy là 98,3%; nhãn intent của vài câu được sửa sau đó. Chấm lại theo nhãn hiện tại ra 290/290 — đúng con số
trong báo cáo Sprint 2.
² File Sprint 2 chưa có trường token; khi chấm lại, dòng Token ghi "AI chưa trả lời lần nào" — lý do N/A đó sai với
file cũ (AI có trả lời, chỉ là không lưu số token). Chỉ ảnh hưởng chấm lại file cũ.

## Đọc kết quả thế nào

- **Kết luận không đổi**: verdict, category, consistency, intent giống hệt — đúng mong đợi, vì kết luận do rule quyết.
- **Unsupported Claim 2,9% → 0% là MỘT lượt**, chưa đủ để nói đã hết. 8 lần bị chặn ở Sprint 2 gồm: 5 lần `0.112`
  (case-007, AI tự trừ hai mốc thời gian) — đúng loại mà quy tắc 4b nhắm tới, và lượt kiểm hồi quy 2026-10-07 cũng
  0/56; 3 lần còn lại (`4.4` làm tròn MOS, `10.942` đổi đơn vị, `11.942` số bịa) là lỗi ngẫu nhiên, lượt này không gặp
  nhưng lượt sau có thể gặp. Sprint 3 T1(a) (AI trích mã chỉ số, code điền giá trị) mới loại bỏ chúng tận gốc.
- **Không đo được bằng runner**: phần chữ gán sai nghĩa con số (G05 không bắt), chất lượng đề xuất — vẫn cần bộ
  tiêu chí chấm tay (Sprint 3 T2).
- Latency là trên máy cá nhân, phụ thuộc OpenAI lúc chạy; chỉ so xu hướng.

## Dùng mốc này

```bash
# Có nhãn category của mentor → thêm expected_issue_category vào dev-cases.yaml rồi chấm lại, không tốn tiền:
./mvnw spring-boot:run -Dspring-boot.run.arguments="--rescore=benchmark/results/moc-sprint-3/evaluation-20261010-171638.json"
```

Mỗi thay đổi prompt ở Sprint 3: dòng **Prompt AI** của lượt mới sẽ khác dòng trên — ghi cả hai khi so.

## Sau T1a — mã chỉ số (2026-10-10)

`benchmark/results/t1a-sprint-3/evaluation-20261010-194651.{md,json}` — `dev` **×2** (132 lần chạy, không phải ×5),
prompt đã đổi (`analyze-call.system.txt e217a0b28a2d`, `ai-analysis.schema.json dc6e36d47853`): **không so trực tiếp
Consistency** với mốc ×5. Chi phí ≈ 0,10 USD (563 049 vào + 18 087 ra).

| | Mốc (×5) | Sau T1a (×2) |
|---|---|---|
| Verdict / Consistency / Intent | 100% / 100% / 100% | 100% / 100% / 100% |
| Unsupported Claim trước Guardrails | 0/280 | 0/118 |
| Fallback (degraded) | 0 | 0 |
| Chỉ số AI nhắc qua mã | — (chưa có) | 220/231 = 95,2%; 11 số tự chép ở 8/118 lần |
| Leakage input / output / log | 0/7 005 · 0 · — | 0/3 110 · 0 · 0 |
| Latency P50 / P95 | 3 428 / 8 107 ms | 3 215 / 7 340 ms |
| Token mỗi lời gọi phân tích | 3 172 + 149 | 3 753 + 135 |

## Sau T1b — evidence căn cứ (2026-10-10)

`benchmark/results/t1b-truoc/` (prompt T1a, đo thêm evidence) và `benchmark/results/t1b-sau/` (prompt `445a72ff32fd`),
cùng ×2. `t1b-sau-v1/` là bản nháp prompt có lỗi G05 `0.112` — giữ để đối chiếu, không dùng làm số chính thức.

| | Trước T1b | Sau T1b |
|---|---|---|
| Verdict / Consistency / Intent | 100% | 100% |
| Unsupported Claim · fallback | 0/118 · 0 | 0/118 · 0 |
| Evidence AI trích (trung bình / tổng) | 5,2 / 12,4 | 3,2 / 12,4 |
| Trích hết mọi evidence | 21/118 | 5/118 |
| Giống nhau giữa các lần lặp | 89,5% | 95,4% |
| AI nhắc chỉ số qua mã | 96,3% | 98,7% |
| Leakage input / output / log | 0/3 110 · 0 · 0 | 0/3 110 · 0 · 0 |
| Latency P50 / P95 | 3 819 / 14 125 ms | 3 742 / 15 828 ms |
| Token phân tích (vào + ra) | 3 753 + 133 | 4 075 + 124 |

Latency cả hai lượt tối 10/10 cao hơn lượt T1a chiều cùng ngày (P95 7 340 ms) dù prompt T1a giống hệt lượt "trước",
nên không do thay đổi của T1b. Nguyên nhân chưa tách được giữa OpenAI và máy bận (xem mục "Sau T4").

## Sau T4 (2026-10-11): latency trước đây gồm ~7 s sanitizer chậm ở `DE7DD314`

Thử 10 request đồng thời tìm ra `SensitiveDataSanitizer.policyForKey` duyệt cả inventory cho mọi thuộc tính của mọi event
(design-decisions mục 34). Request `DE7DD314` 4 file, không khoá AI: 9 405 ms → 977 ms; report trùng từng byte.

Cả 4 case chậm nhất ở mọi lượt có AI đều là `DE7DD314`:

| Lượt | P95 | Case chậm nhất (trung vị) |
|---|---|---|
| Mốc (×5) | 7 784 ms | case-002 8 340 · case-016 5 723 · case-015 5 426 · case-017 4 710 |
| Trước T1b (×2) | 10 336 ms | case-016 14 771 · case-015 9 407 · case-017 9 199 · case-002 6 270 |
| Sau T1b (×2) | 14 786 ms | case-002 15 974 · case-016 13 946 · case-015 9 001 · case-017 8 759 |

(P95 ở đây tính lại từ file `.json`, cách lấy phân vị khác runner một chút.)

Sau khi sửa, `dev` ×1 không khoá AI: latency pipeline P50 163 ms · P95 890 ms (case-002 trung vị 1 023 ms). Phần chi phí
sanitizer là việc của CPU, chạy dưới `spring-boot:run` (`-XX:TieredStopAtLevel=1`, chỉ JIT cấp 1) nên càng chậm khi
máy bận. So sánh trước / sau giữa các lượt cũ vẫn đúng (lượt nào cũng chịu cùng chi phí), nhưng P50 / P95 tuyệt đối
của các lượt cũ không còn đại diện. Cần một lượt có AI để có số mới.

### Lượt có AI sau khi sửa (2026-10-11 16:51)

`dev` ×1, cùng prompt `445a72ff32fd` với lượt "Sau T1b", ~0,05 USD. Kết quả: `benchmark/results/sau-t3-t4/`.

| | Sau T1b (×2, trước sửa) | Sau T3 + T4 (×1) |
|---|---|---|
| Latency P50 / P95 | 3 742 / 15 828 ms | 2 695 / 4 455 ms |
| case-002 (`DE7DD314` đủ 4 file), trung vị | 15 974 ms | 4 039 ms |
| Verdict / Consistency / Intent | 100% | 100% |
| Unsupported Claim · fallback · leakage | 0 · 0 · 0 | 0 · 0 · 0 |

Mức tin cậy từng câu (59) trùng lượt "Sau T1b": nhánh có AI của `ConfidencePolicy` (T3) không đổi kết quả.

## Sau T2 (2026-10-12)

`dev` ×1, prompt `d1f62cfe52ec` (đổi: quy tắc 9-10, context mang bộ đề xuất chuẩn), ~0,05 USD. Kết quả:
`benchmark/results/sau-t2/`.

| | Sau T3 + T4 (×1) | Sau T2 (×1) |
|---|---|---|
| Verdict / Consistency / Intent / Template | 100% | 100% |
| Unsupported Claim AI thô · fallback | 0 · 0 | 3/59 · 3 (case-008, G05 "3478") → sửa, chạy lại case-008: 0 · 0 |
| Latency P50 / P95 | 2 695 / 4 455 ms | 2 702 / 4 327 ms |
| Token phân tích vào / ra (trung bình) | 4 072 / 124 | 4 296 / 142 |

