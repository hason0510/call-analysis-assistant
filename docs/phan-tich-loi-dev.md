# Phân tích lỗi trên benchmark `dev` — Sprint 3 T1(c)

> Viết 2026-10-10. MVP mục 7.1 T1: *"Phân tích lỗi trên `dev` (sai verdict, sai category, không nhất quán giữa các lần
> chạy / cách hỏi, unsupported claim)"*. Mọi con số dưới đây sinh lại được, không tốn tiền AI:
>
> ```
> python scripts/error-analysis/analyze_runs.py benchmark/dev-cases.yaml <file .json của từng lượt>
> ```
>
> Script chấm theo đáp án **hiện tại** của `dev-cases.yaml` (như `--rescore`). Câu có nhãn intent mơ hồ đã được bỏ nhãn
> sau Sprint 2 (ví dụ `case-007` câu 1 *"Cuộc gọi này kết thúc ra sao?"*), nên số intent ở đây có thể thấp hơn số in
> trong báo cáo Sprint 2 lúc chạy.

## Các lượt đã phân tích — 1 332 lần chạy có AI thật (`gpt-4o-mini`)

| Lượt | Ngày | Lần chạy | Thay đổi so với lượt trước |
|---|---|---|---|
| S2-1 | 06/10 | 63 (×1) | Lượt đầu với AI thật |
| S2-2 | 06/10 | 315 (×5) | — |
| S2-3 | 06/10 | 63 (×1) | Sửa prompt phân loại câu hỏi (câu không dấu) |
| S2-4 (chính thức Sprint 2) | 06/10 | 315 (×5) | — |
| S2-4b | 06/10 | 63 (×1) | Thêm quy tắc 4b (câu hỏi về khoảng thời gian) |
| Mốc Sprint 3 | 10/10 | 315 (×5) | Không đổi prompt — đo lại bản đang chạy |
| Bảo mật | 10/10 | 66 (×1) | Thêm case-019 (dữ liệu nhạy cảm giả) |
| T1a | 10/10 | 132 (×2) | AI nhắc chỉ số bằng mã |

Kết quả S2-x nằm ở `../eval-scratch/` (ngoài repo); Sprint 3 ở `benchmark/results/`.

## Tổng hợp theo bốn loại lỗi của MVP 7.1

| Loại lỗi | S2-1 | S2-2 | S2-3 | S2-4 | S2-4b | Mốc | Bảo mật | T1a |
|---|---|---|---|---|---|---|---|---|
| Sai verdict | 2/56 | 15/280 | 0/56 | 0/280 | 0/56 | 0/280 | 1/59 | 0/118 |
| Sai category | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |
| AI lệch rule (verdict hoặc category) | 0/54 | 0/265 | 0/56 | 0/280 | 0/56 | 0/280 | 0/58 | 0/118 |
| Case không nhất quán | 2/17 | 3/17 | 0/17 | 0/17 | 0/17 | 0/17 | 1/18 | 0/18 |
| Câu không nhất quán giữa các lần lặp | — | 0/56 | — | 0/56 | — | 0/56 | — | 0/59 |
| Unsupported claim (G01/G05, AI thô) | 0/54 | 12/265 | 2/56 | 8/280 | 0/56 | 0/280 | 0/58 | 0/118 |
| Sai intent | 3/58 | 15/290 | 0/58 | 0/290 | 0/58 | 0/290 | 1/61 | 0/122 |

## 1. Sai verdict — mọi lần sai đều do câu bị từ chối nhầm, không lần nào do phân tích

**18 lần sai trong 1 185 lần chấm verdict, cả 18 đều là câu phân tích bị trả `OUT_OF_SCOPE`.** Khi câu hỏi tới được
pipeline, report chưa lần nào ra sai verdict.

| Câu | Lượt | Nguyên nhân | Đã sửa |
|---|---|---|---|
| `case-010` câu 3 *"cuoc goi nay on chu"* | S2-1, S2-2 (6 lần) | Prompt phân loại cũ coi câu không dấu, ngắn là ngoài phạm vi | Sửa prompt phân loại (06/10) — 0 lần từ S2-3 |
| `case-014` câu 3 *"ai la nguoi cup may truoc"* | S2-1, S2-2 (6 lần) | Như trên | Như trên |
| `case-013` câu 3 *"Vì sao lời mời gọi phải gửi lại nhiều lần?"* | S2-2 (5 lần) | "lời mời gọi" (= INVITE) không nhận ra là chuyện cuộc gọi | Như trên |
| `case-019` câu 3 (bí mật dán vào câu hỏi rồi viết tiếp) | Bảo mật (1 lần) | Sanitizer che tới hết dòng nên xoá luôn phần yêu cầu; AI chỉ thấy `api_key=[REDACTED]` | Chế độ làm sạch riêng cho câu hỏi (phương án C, 10/10) — 3/3 ra report khi chạy lại |

**Bài học.** Hai lỗi đầu không nằm ở phần phân tích mà ở **cửa vào**: phân loại câu hỏi và làm sạch câu hỏi. Lỗi ở
cửa vào làm hỏng cả report, nên Intent Accuracy và việc giữ nguyên ý câu hỏi sau Sanitizer cần đo ở mọi lượt.

## 2. Sai category — chưa chấm được

Ground truth chỉ có `fail/` / `success/`, không có category, nên **Issue Category Accuracy là N/A** ở mọi lượt (câu hỏi
mentor số 5; `--rescore` chấm lại được ngay khi có nhãn, không cần chạy lại AI).

Đo được là **AI có lệch rule không**: 0 lần lệch verdict, 0 lần cùng verdict mà khác category, trên 1 167 lần AI trả lời.
**Không suy ra được AI tự suy luận đúng**, vì context có kết luận của rule (`ruleVerdict`), và AI có thể chỉ chép lại.
Cách tách bạch là chạy một biến thể context không có `ruleVerdict` (T11 Sprint 2, "Nếu kịp").

Category của rule tự kiểm được trên 13 cuộc có nhãn fail/success (`docs/acceptance-criteria.md`). Còn hai giới hạn:
không có cuộc nào chất lượng kém có nhãn, và rule `NETWORK_DELAY_JITTER` chưa cài.

## 3. Không nhất quán

- **Giữa các lần lặp của cùng một câu:** 0 câu đổi kết quả, ở cả bốn lượt có lặp (S2-2, S2-4, mốc, T1a). `temperature 0`,
  kết luận cuối do rule + Guardrails quyết.
- **Giữa các cách hỏi của cùng một case:** chỉ lệch khi một câu bị từ chối nhầm (mục 1). Gom theo case thì case-010,
  013, 014 (S2-1, S2-2) và case-019 (Bảo mật) là các case không nhất quán. Nguyên nhân đều là intent, không phải phân tích.
- **Phần chữ thì chưa ổn định:** cùng câu, AI trích lúc 22/22 evidence, lúc 14/22 (Sprint 2, `271D1FAF`). Trước Sprint 3
  chưa đo. Runner nay đo được: dòng "Evidence AI trích — giống nhau giữa các lần lặp" (T1b).

## 4. Unsupported claim — 22 lần G05, bốn kiểu, đã giảm về 0

| Số AI viết | Lần | Cuộc | Kiểu lỗi | Đánh giá |
|---|---|---|---|---|
| `0.112` | 11 | `D114749E` — mọi chỉ số thời gian N/A, câu hỏi "từ lúc gọi đến lúc báo lỗi" | AI tự trừ hai mốc thời gian | Vi phạm thật ("AI không tính số liệu") |
| `11.942` | 6 | `C8CF631E` — đổ chuông 10942 ms | Không khớp số nào: **bịa** | Vi phạm thật |
| `4.4` | 4 | MOS 4.42397 / 4.42201 | Làm tròn | Đúng nghĩa, sai quy tắc nguyên văn |
| `10.942` | 1 | `C8CF631E` — đổ chuông 10942 ms | Đổi đơn vị ms → giây | Đúng nghĩa, sai quy tắc nguyên văn |

Mọi lần đều bị Guardrails chặn, report lùi về rule: **người dùng không thấy số sai** (Unsupported Claim report cuối = 0%
ở mọi lượt). Cái giá là report đó mất phần chữ AI (degraded).

**Đã sửa theo hai bước:**
1. **Quy tắc 4b** của prompt (06/10): câu hỏi về khoảng thời gian thì chép đúng chỉ số; chỉ số N/A thì nói không có số đo,
   không tự trừ mốc. S2-4b và mốc Sprint 3: 0/336.
2. **T1a — mã chỉ số** (10/10): AI không còn tự chép giá trị chỉ số, nên làm tròn, đổi đơn vị hay bịa đều không còn đường xảy
   ra với chỉ số trong bảng. 0/118. Kiểu lỗi mà **G05 không bắt được**, chép đúng số nhưng gán sai nghĩa (`271D1FAF`:
   tỉ lệ mất gói của một mẫu stats bị gọi là mất gói cả cuộc), cũng được chặn từ gốc, vì tên chỉ số do code điền.

**Một lỗi G01 mới chỉ xuất hiện trong lúc phát triển T1a** (không có trong lượt chính thức nào): mã đánh số `{M20}` làm AI
trích `EV20` không tồn tại, 5/5 lần trên `2D9057AA`. Đổi sang mã theo tên chỉ số thì hết (design-decisions mục 32).

## 5. Những gì phép đo này KHÔNG thấy

- **Chất lượng phần chữ:** đề xuất chung chung, phân tích chép lại lý do của rule, bỏ bằng chứng ngược chiều. Đây là các
  nhận xét tay ở `docs/ket-qua-for-test.md` và `docs/sprint-2-tien-do.md`, thuộc T2 Sprint 3.
- **Evidence AI trích có đúng là căn cứ không:** T1b.
- **Ca khó mà `dev` chưa có:** cuộc gọi chất lượng kém có nhãn, jitter cao, `held-out` của mentor. 100% trên `dev` không
  bảo đảm 85% trên `held-out`: `dev` có 13 cuộc, rule được viết khi đã nhìn thấy chính 13 cuộc đó.

## Việc rút ra

| # | Việc | Trạng thái |
|---|---|---|
| 1 | Giữ đo Intent Accuracy ở mọi lượt — nguồn duy nhất của sai verdict | Có sẵn trong runner |
| 2 | Đo câu hỏi sau Sanitizer còn đủ ý (case-019) | Có — case-019 trong `dev` |
| 3 | Mã chỉ số thay cho AI tự chép số | ✅ T1a |
| 4 | Đo và giảm dao động evidence AI trích | T1b |
| 5 | Biến thể context không có `ruleVerdict` để biết AI tự suy luận được không | Chưa làm (T11, Nếu kịp) |
| 6 | Issue Category Accuracy | Chờ nhãn category |
