# Phân tích lỗi trên benchmark `dev` — Sprint 3 T1(c)

> Viết 2026-10-10, cập nhật 2026-10-15 (thêm 12 lượt chạy từ 10/10 tới 15/10). MVP mục 7.1 T1: *"Phân tích lỗi trên
> `dev` (sai verdict, sai category, không nhất quán giữa các lần chạy / cách hỏi, unsupported claim)"*. Mọi con số dưới
> đây sinh lại được, không tốn tiền AI:
>
> ```
> python scripts/error-analysis/analyze_runs.py benchmark/dev-cases.yaml <file .json của từng lượt>
> ```
>
> Script chấm theo đáp án **hiện tại** của `dev-cases.yaml` (như `--rescore`). Câu có nhãn intent mơ hồ đã được bỏ nhãn
> sau Sprint 2 (ví dụ `case-007` câu 1 *"Cuộc gọi này kết thúc ra sao?"*), nên số intent ở đây có thể thấp hơn số in
> trong báo cáo Sprint 2 lúc chạy.

## Các lượt đã phân tích — 3 933 lần chạy có AI thật (`gpt-4o-mini`)

| Lượt | Ngày | Lần chạy | Thay đổi so với lượt trước | Thư mục |
|---|---|---|---|---|
| S2-1 | 06/10 | 63 (×1) | Lượt đầu với AI thật | `../eval-scratch/` |
| S2-2 | 06/10 | 315 (×5) | — | `../eval-scratch/` |
| S2-3 | 06/10 | 63 (×1) | Sửa prompt phân loại câu hỏi (câu không dấu) | `../eval-scratch/` |
| S2-4 (chính thức Sprint 2) | 06/10 | 315 (×5) | — | `../eval-scratch/` |
| S2-4b | 06/10 | 63 (×1) | Thêm quy tắc 4b (câu hỏi về khoảng thời gian) | `../eval-scratch/` |
| Mốc Sprint 3 | 10/10 | 315 (×5) | Không đổi prompt — đo lại bản đang chạy | `moc-sprint-3/` |
| Bảo mật | 10/10 | 66 (×1) | Thêm case-019 (dữ liệu nhạy cảm giả) | `bao-mat-sprint-3/` |
| T1a | 10/10 | 132 (×2) | AI nhắc chỉ số bằng mã | `t1a-sprint-3/` |
| Trước T1b | 10/10 | 132 (×2) | Cùng prompt T1a — mốc so sánh cho T1b | `t1b-truoc/` |
| T1b bản nháp | 10/10 | 132 (×2) | Bản nháp prompt T1b, có ví dụ *"câu hỏi về thời gian đổ chuông dựa vào RINGING, OK"* — đã bỏ | `t1b-sau-v1/` |
| T1b | 10/10 | 132 (×2) | Evidence mang cờ căn cứ của rule; bỏ ví dụ trên, quy tắc 4b chặt hơn | `t1b-sau/` |
| T3 + T4 | 11/10 | 66 (×1) | Cùng prompt T1b; `ConfidencePolicy`, sửa sanitizer chậm | `sau-t3-t4/` |
| T2 | 12/10 | 66 + 3 | Đề xuất chuẩn của rule luôn giữ, AI chỉ bổ sung; 3 lần chạy lại `case-008` sau khi sửa G05 | `sau-t2/` |
| Dataset mới | 13/10 | 345 (×5) | Sửa 5 lỗi tool tìm ra trên `ai20k_new_dataset`; prompt `bd3790c6e0b1` | `sau-dataset-moi/` |
| Dataset mới, lần 2 | 13/10 | 345 (×5) | Cùng prompt `bd3790c6e0b1` | `sau-giai-thich/` |
| **Bản cuối (chính thức Sprint 3)** | 14/10 | 345 (×5) | Prompt `e8941db2acc1`: quy tắc 4b chặt hơn sau lần tái phát `0.112`; Tóm tắt ca FAIL do code viết; rule `NETWORK_DELAY_JITTER` | `ban-cuoi-2026-10-14/` |
| Chạy lại | 14/10 | 345 (×5) | Cùng prompt `e8941db2acc1`, chạy sau commit `bf0114b` — kiểm độ trễ của bản cuối | `chay-lai-2026-10-14/` |
| Sau góp ý mentor | 15/10 | 345 (×5) | Cùng prompt; G05 bắt số liền đơn vị, cổng fail-closed trước AI, ngưỡng mất gói cả cuộc | `gop-y-mentor-json/` |
| T11 — context văn bản | 15/10 | 345 (×5) | Như lượt trên, context gửi AI dạng văn bản (`ai.context-format=text`) | `gop-y-mentor-text/` |

Kết quả S2-x nằm ở `../eval-scratch/` (ngoài repo); Sprint 3 ở `benchmark/results/` (không commit — số được chép vào
đây).

## Tổng hợp theo bốn loại lỗi của MVP 7.1

"AI lệch rule" và "Unsupported claim" tính trên số lần AI trả lời; "—" là lượt không lặp.

| Lượt | Sai verdict | Sai category | AI lệch rule (verdict hoặc category) | Case không nhất quán | Câu đổi kết quả giữa các lần lặp | Unsupported claim (G01/G05, AI thô) | Sai intent |
|---|---|---|---|---|---|---|---|
| S2-1 | 2/56 | N/A | 0/54 | 2/17 | — | 0/54 | 3/58 |
| S2-2 | 15/280 | N/A | 0/265 | 3/17 | 0/56 | 12/265 | 15/290 |
| S2-3 | 0/56 | N/A | 0/56 | 0/17 | — | 2/56 | 0/58 |
| S2-4 | 0/280 | N/A | 0/280 | 0/17 | 0/56 | 8/280 | 0/290 |
| S2-4b | 0/56 | N/A | 0/56 | 0/17 | — | 0/56 | 0/58 |
| Mốc Sprint 3 | 0/280 | N/A | 0/280 | 0/17 | 0/56 | 0/280 | 0/290 |
| Bảo mật | 1/59 | N/A | 0/58 | 1/18 | — | 0/58 | 1/61 |
| T1a | 0/118 | N/A | 0/118 | 0/18 | 0/59 | 0/118 | 0/122 |
| Trước T1b | 0/118 | N/A | 0/118 | 0/18 | 0/59 | 0/118 | 0/122 |
| T1b bản nháp | 0/118 | N/A | 0/118 | 0/18 | 0/59 | 2/118 | 0/122 |
| T1b | 0/118 | N/A | 0/118 | 0/18 | 0/59 | 0/118 | 0/122 |
| T3 + T4 | 0/59 | N/A | 0/59 | 0/18 | — | 0/59 | 0/61 |
| T2 | 0/62 | N/A | 0/62 | 0/18 | — | 3/62 | 0/64 |
| Dataset mới | 0/310 | N/A | 0/310 | 0/19 | 0/62 | 4/310 | 0/320 |
| Dataset mới, lần 2 | 0/310 | N/A | 0/310 | 0/19 | 0/62 | 5/310 | 0/320 |
| **Bản cuối** | **0/310** | N/A | **0/309** | **0/19** | **0/62** | **0/309** | **0/320** |
| Chạy lại | 0/310 | N/A | 0/310 | 0/19 | 0/62 | 0/310 | 0/320 |
| Sau góp ý mentor (G05 mới) | 0/310 | N/A | 0/310 | 0/19 | 0/62 | 0/310 | 0/320 |
| T11 — context văn bản | 0/310 | N/A | 0/310 | 0/19 | 0/62 | 0/310 | 0/320 |
| **Cộng** | **18/3 520** | N/A | **0/3 501** | | **0 câu** (13 lượt có lặp) | **36/3 501** | **19/3 680** |

## 1. Sai verdict — mọi lần sai đều do câu bị từ chối nhầm, không lần nào do phân tích

**18 lần sai trong 3 520 lần chấm verdict, cả 18 đều là câu phân tích bị trả `OUT_OF_SCOPE`, và đều trước 10/10.** Từ
lượt T1a tới lượt T11 ngày 15/10 (2 335 lần chấm) không có lần sai nào. Khi câu hỏi tới được pipeline, report chưa lần nào ra
sai verdict.

| Câu | Lượt | Nguyên nhân | Đã sửa |
|---|---|---|---|
| `case-010` câu 3 *"cuoc goi nay on chu"* | S2-1, S2-2 (6 lần) | Prompt phân loại cũ coi câu không dấu, ngắn là ngoài phạm vi | Sửa prompt phân loại (06/10) — 0 lần từ S2-3 |
| `case-014` câu 3 *"ai la nguoi cup may truoc"* | S2-1, S2-2 (6 lần) | Như trên | Như trên |
| `case-013` câu 3 *"Vì sao lời mời gọi phải gửi lại nhiều lần?"* | S2-2 (5 lần) | "lời mời gọi" (= INVITE) không nhận ra là chuyện cuộc gọi | Như trên |
| `case-019` câu 3 (bí mật dán vào câu hỏi rồi viết tiếp) | Bảo mật (1 lần) | Sanitizer che tới hết dòng nên xoá luôn phần yêu cầu; AI chỉ thấy `api_key=[REDACTED]` | Chế độ làm sạch riêng cho câu hỏi (phương án C, 10/10) — 3/3 ra report khi chạy lại |

**Bài học.** Hai lỗi đầu không nằm ở phần phân tích mà ở **cửa vào**: phân loại câu hỏi và làm sạch câu hỏi. Lỗi ở
cửa vào làm hỏng cả report, nên Intent Accuracy và việc giữ nguyên ý câu hỏi sau Sanitizer cần đo ở mọi lượt.

## 2. Sai category — phần cần nhãn chưa chấm được, phần không cần nhãn đã đo

**Phạm vi theo MVP.** Issue category áp dụng cho `FAIL` và `SUCCESS` có cờ chất lượng kém (mục 4.2); Issue Category
Accuracy chỉ tính các case đó (mục 6.5). Trên `dev`, đó là 7 case FAIL (case-001, 004–008, 019 — 6 cuộc gọi của `fail/`,
case-019 dùng log của `2D9057AA`); `dev` không có case SUCCESS nào có cờ chất lượng, vì data không có cuộc chất lượng kém
có nhãn.

**Chưa chấm được: category so với đáp án.** Đáp án category do mentor cung cấp (MVP dòng 51, mục 6.3), mà ground truth
hiện chỉ có `fail/` / `success/` — không có category, nên Issue Category Accuracy là N/A ở mọi lượt (câu hỏi mentor số 5).
Dự án không tự đặt nhãn: nhãn category không suy ra được từ nhãn fail / success. Khi có nhãn, `--rescore` chấm lại
được ngay mọi lượt ở trên, không cần chạy lại AI.

**Đã đo, không cần nhãn:**
- **Category nhất quán:** MVP 6.5 Consistency đòi verdict **và** issue category giống nhau qua các lần chạy × cách hỏi.
  Runner so cả hai: 0 câu đổi kết quả ở 13 lượt có lặp; từ lượt T1a, 0 case không nhất quán.
- **Category của AI so với rule:** 0 lần lệch verdict, 0 lần cùng verdict mà khác category, trên 3 501 lần AI trả lời.
  **Không suy ra được AI tự suy luận đúng**, vì context có kết luận của rule (`ruleVerdict`), và AI có thể chỉ chép lại.
  Cách tách bạch là chạy một biến thể context không có `ruleVerdict` (T11 Sprint 2, "Nếu kịp", chưa làm).

**Thay đổi rule có ảnh hưởng tới category trong Sprint 3:** rule `NETWORK_DELAY_JITTER` cài ngày 14/10 (design-decisions
mục 41). Trên `dev` không cuộc nào chạm ngưỡng nên category của 7 case FAIL không đổi; trên dataset mới chỉ `FC3187B4`
đổi (SUCCESS không cờ → SUCCESS + `NETWORK_DELAY_JITTER`).

## 3. Không nhất quán

- **Giữa các lần lặp của cùng một câu:** 0 câu đổi kết quả (verdict + category), ở cả 13 lượt có lặp (S2-2, S2-4, mốc,
  T1a, trước T1b, T1b bản nháp, T1b, hai lượt dataset mới, bản cuối, chạy lại). `temperature 0`, kết luận cuối do rule +
  Guardrails quyết.
- **Giữa các cách hỏi của cùng một case:** chỉ lệch khi một câu bị từ chối nhầm (mục 1). Gom theo case thì case-010,
  013, 014 (S2-1, S2-2) và case-019 (Bảo mật) là các case không nhất quán. Nguyên nhân đều là intent, không phải phân tích.
  Từ lượt T1a: 0 case không nhất quán.
- **Phần chữ thì chưa ổn định hẳn:** Sprint 2, cùng câu, AI trích lúc 22/22 evidence, lúc 14/22 (`271D1FAF`). Từ T1b
  runner đo độ trùng evidence giữa các lần lặp: 95,4% (T1b) → 95,5% ở bản cuối (50/62 câu trích giống hệt mọi lần).

## 4. Unsupported claim — 36 lần G05, năm đợt, lượt cuối về 0

Mọi lần đều bị Guardrails chặn, report lùi về rule: **người dùng không thấy số sai** (Unsupported Claim report cuối = 0%
ở mọi lượt). Cái giá là report đó mất phần chữ AI (degraded).

| Số AI viết | Lần | Lượt | Cuộc / case | Kiểu lỗi | Đánh giá |
|---|---|---|---|---|---|
| `0.112` | 11 | Sprint 2 | `D114749E` — mọi chỉ số thời gian N/A, câu hỏi "từ lúc gọi đến lúc báo lỗi" | AI tự trừ hai mốc thời gian | Vi phạm thật ("AI không tính số liệu") |
| `11.942` | 6 | Sprint 2 | `C8CF631E` — đổ chuông 10942 ms | Không khớp số nào: **bịa** | Vi phạm thật |
| `4.4` | 4 | Sprint 2 | MOS 4.42397 / 4.42201 | Làm tròn | Đúng nghĩa, sai quy tắc nguyên văn |
| `10.942` | 1 | Sprint 2 | `C8CF631E` — đổ chuông 10942 ms | Đổi đơn vị ms → giây | Đúng nghĩa, sai quy tắc nguyên văn |
| `0.112` | 2 | T1b bản nháp | case-007 câu 3 | Ví dụ "RINGING, OK" trong bản nháp prompt làm AI tự trừ mốc | Vi phạm thật — bỏ ví dụ, lượt T1b về 0 |
| `3478` | 3 | T2 | case-008 câu 1–3 (`E9D6C112`) | Cổng TURN trong đề xuất chuẩn mới của rule chưa được đưa vào căn cứ của G05 | **Lỗi của hệ thống, không phải của AI** — số có thật trong context. Đã đưa vào căn cứ; chạy lại 3/3 qua |
| `0.112` | 9 | Dataset mới (4), lần 2 (5) | case-007 câu 3 (`D114749E`) | Tái phát kiểu tự trừ mốc, sau khi prompt thêm quy tắc 9a–9c | Vi phạm thật — quy tắc 4b viết chặt hơn; lượt kiểm riêng 0/45, bản cuối, chạy lại và hai lượt 15/10 0/1 239 |

**Đã sửa theo các bước:**
1. **Quy tắc 4b** của prompt (06/10): câu hỏi về khoảng thời gian thì chép đúng chỉ số; chỉ số N/A thì nói không có số đo,
   không tự trừ mốc. S2-4b và mốc Sprint 3: 0/336.
2. **T1a — mã chỉ số** (10/10): AI nhắc chỉ số bằng mã, code điền tên và giá trị, nên làm tròn, đổi đơn vị hay bịa không
   còn đường xảy ra với giá trị trong bảng chỉ số; kiểu gọi sai tên con số (`271D1FAF`: tỉ lệ mất gói của một mẫu stats bị
   gọi là mất gói cả cuộc) cũng hết, vì tên do code điền. 0/118.
3. **Mỗi số mới vào context thì vào căn cứ của G05** (12/10, sau đợt `3478`): thứ gì thêm vào context gửi AI thì thêm luôn
   vào `groundingTexts`.
4. **Quy tắc 4b chặt hơn** (14/10, sau đợt `0.112` thứ ba). Kiểu "tự trừ hai mốc thời gian" là kiểu lỗi duy nhất tái phát
   (Sprint 2, T1b bản nháp, dataset mới) dù đã có quy tắc 4b, nên G05 vẫn là chốt chặn cần giữ.

**G05 bắt thêm số viết liền đơn vị (15/10, góp ý mentor).** Trước đó số như `250ms`, `45s` không được xét, nên mọi
con số "0" ở trên là cận dưới. Hai lượt 15/10 đo bằng G05 mới: 0/310 và 0/310 — không có số liền đơn vị nào bị bỏ sót.
Các lượt cũ không chấm lại được, vì file kết quả không lưu chữ AI viết.

**Những gì G05 và T1a không chặn:**
- AI vẫn tự chép tay con số trùng giá trị một chỉ số thay vì dùng mã: 30 số ở 21/309 lần trả lời (bản cuối), 27 số ở
  23/310 lần (chạy lại). Cận trên — số trùng ngẫu nhiên như "1" cũng tính. Hiện chỉ đo, không chặn.
- AI gắn mã chỉ số vào câu không đúng nghĩa (*"không nhận được âm thanh: {PACKET_LOSS_CALLER}"*), ghi nhầm bên của
  `network_lost` (`FE4EF5E7`), gọi RTT lúc kết thúc là "RTT trung bình" (`FC3187B4`) — gặp khi đọc tay report trên
  dataset mới. Số có nguồn, tên đúng, nhưng câu chữ sai nghĩa; Guardrails không kiểm nghĩa câu chữ.

**Một lỗi G01 chỉ xuất hiện trong lúc phát triển T1a** (không có trong lượt nào ở trên): mã đánh số `{M20}` làm AI trích
`EV20` không tồn tại, 5/5 lần trên `2D9057AA`. Đổi sang mã theo tên chỉ số thì hết (design-decisions mục 32).

## 5. Những gì phép đo này KHÔNG thấy

- **Chất lượng phần chữ:** phóng đại mức độ (*"mất gói đáng kể"* khi cả cuộc 0,36%, `06BCA48C`), câu hỏi giả định sai chưa
  luôn bị phủ nhận thẳng (`case-020`), các lỗi diễn giải ở mục 4. Chỉ thấy khi đọc tay; ghi ở Known Limitations.
- **Evidence AI trích có ĐÚNG là căn cứ không:** runner chỉ kiểm evidence trích có chứa dòng căn cứ của rule (bản cuối
  94/115 lần có căn cứ). Ca SUCCESS không cờ không có dòng căn cứ của rule nên AI tự chọn.
- **Ca khó mà `dev` chưa có:** cuộc gọi chất lượng kém có nhãn, `held-out` của mentor. 100% trên `dev` không bảo đảm 85%
  trên `held-out`: `dev` có 13 cuộc, rule được viết khi đã nhìn thấy chính 13 cuộc đó.

## Việc rút ra

| # | Việc | Trạng thái |
|---|---|---|
| 1 | Giữ đo Intent Accuracy ở mọi lượt — nguồn duy nhất của sai verdict | ✅ có sẵn trong runner; 0 sai từ T1a |
| 2 | Đo câu hỏi sau Sanitizer còn đủ ý (case-019) | ✅ case-019 trong `dev` |
| 3 | Mã chỉ số thay cho AI tự chép số | ✅ T1a — còn 27–30 số chép tay mỗi lượt ×5, chỉ đo |
| 4 | Đo và giảm dao động evidence AI trích | ✅ T1b — trung bình 5,2 → 3,1 evidence / lần, trùng giữa các lần lặp 95,5% |
| 5 | Thêm vào context thứ gì thì thêm vào căn cứ G05 | ✅ sau đợt `3478` (T2) |
| 6 | Giữ G05 làm chốt chặn kiểu "tự trừ mốc thời gian" | ✅ — kiểu lỗi duy nhất tái phát |
| 7 | Biến thể context không có `ruleVerdict` để biết AI tự suy luận được không | ⬜ chưa làm (T11 Sprint 2, Nếu kịp) |
| 8 | Issue Category Accuracy | ⬜ chờ nhãn category của mentor (7 case FAIL) |
