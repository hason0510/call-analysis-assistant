# Final Evaluation Report & Known Limitations — Sprint 3

> Tài liệu bàn giao số 3 của MVP mục 7.4: accuracy, consistency, AI vs Rule, latency trên `dev`; kết quả `held-out` do
> Mentor gửi lại. Cách tính từng metric: [`evaluation-metrics.md`](evaluation-metrics.md). Các quyết định thiết kế nhắc
> tới ở đây: [`design-decisions.md`](design-decisions.md) (số mục trong ngoặc).
>
> **Số đo chính thức của bản nộp: mục 9** (lượt chạy lại trước khi nộp, 2026-10-19), tóm tắt ngay dưới đây. Mục 1-3 là
> lượt cuối Sprint 3 (2026-10-14, `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md`), giữ lại vì có
> phân tích độ trễ và AI vs Rule chi tiết; code đổi sau lượt đó ở mục 8. Thư mục `benchmark/results/` không commit; số
> được chép vào đây. Phân tích hàng loạt (T10, làm sau mục 9, không đổi luồng phân tích): mục 10. Sửa theo Peer
> Evaluation của bạn cùng nhóm (2026-10-20; không đổi prompt hay rule kết luận, `dev` chỉ rule vẫn 62/62): mục 7,
> "Peer Evaluation về repo này".

## Tóm tắt kết quả của bản nộp

Benchmark `dev` ×5 (20 case trên 13 cuộc gọi, 69 câu hỏi, 345 lần chạy; 2026-10-19, `gpt-4o-mini`), chi tiết ở mục 9:

| Metric (MVP 6.5) | Kết quả | Target | Mức |
|---|---|---|---|
| Verdict Accuracy | **100%** (310/310 lần chạy, 19/19 case) trên `dev`; `held-out` do mentor đo (mục 6) | ≥ 85% (`held-out`) | Bắt buộc |
| Metric Correctness | **100%** (310/310) | 100% | Bắt buộc |
| Security Leakage input / output | **0 / 8 255 · 0** (report, câu trả lời và application log) | 0% · 0 | Bắt buộc |
| Issue Category Accuracy | N/A — ground truth không có nhãn category (mục 7, "Bộ benchmark `dev`") | ≥ 70% | Báo cáo |
| Consistency | **100%** (19/19 case giống hệt qua 5 lần) | ≥ 95% | Báo cáo |
| Template Compliance | **100%** | 100% | Báo cáo |
| Intent Accuracy | **100%** (320/320) | ≥ 90% | Báo cáo |
| Unsupported Claim — AI thô / report cuối | **0%** (0/310) / **0%** | 0% | Báo cáo |
| Pipeline Success Rate | **100%** (310/310), 0 lần lùi về rule | ≥ 95% | Báo cáo |
| Latency P50 / P95 | **2,95 s / 4,04 s** (chậm nhất 11,3 s, máy cá nhân) | — | Báo cáo |

AI vs Rule: 19/19 case Rule = AI = Final = Ground Truth, 0 vi phạm Guardrails (mục 3). Test bắt buộc S01-S08, G01-G05,
F01-F04 kèm enum lạ / thiếu trường / input quá lớn: 67/67 qua (mục 9). Bảo mật: [`bao-cao-bao-mat.md`](bao-cao-bao-mat.md).
Phân tích lỗi qua các lượt: [`phan-tich-loi-dev.md`](phan-tich-loi-dev.md).

## 1. Cấu hình đo

| | |
|---|---|
| Code | `main` @ `bf0114b` (commit Sprint 3) |
| Bộ case | `benchmark/dev-cases.yaml`: 20 case, 69 câu hỏi × 5 lần = **345 lần chạy** (310 lần ra report, 35 lần câu ngoài phạm vi) |
| Signaling | Elasticsearch, 13/13 Call-ID của `dev` |
| AI | OpenAI `gpt-4o-mini`, temperature 0 |
| Prompt AI (dấu vân tay) | `analyze-call.system.txt e8941db2acc1`, `ai-analysis.schema.json e06428ad4f05`, `parse-request.system.txt bd618652241b`, `request-intent.schema.json fe0dcba2e2a6` |
| Thời gian · chi phí | 33 phút (2026-10-14 09:53 → 10:26) · ~1,90 triệu token vào + ~50 nghìn token ra trên 654 lời gọi ≈ **0,32 USD** |

So với mốc P0.3 (`moc-sprint-3.md`), bộ case thêm `case-020` (câu hỏi giả định sai) và `case-019` đã có từ đợt 1.

**Độ phủ của bộ `dev` so với MVP 6.3.** 13 cuộc gọi có nhãn, mỗi cuộc 3-5 cách hỏi:

| Loại ca MVP 6.3 yêu cầu | Case |
|---|---|
| SUCCESS bình thường | case-002, 009–014 |
| SUCCESS chất lượng kém | **Không có** — data mẫu không có cuộc chất lượng kém có nhãn (mục 7) |
| FAIL thiết lập | case-004, 007 (server từ chối, mã 428) |
| FAIL media (ICE / TURN) | case-001 (ICE); 005, 006, 008 (TURN) |
| UNKNOWN do thiếu file | case-018 — đáp án suy từ luật "thiếu file → UNKNOWN" (MVP 4.1), không phải nhãn mentor |
| Bình thường có WARN | Mọi case SUCCESS: signaling của cả 7 cuộc có 5–13 event `level=WARN` |
| Thiếu file một bên | case-015 (và các cuộc vốn chỉ có log một bên: 001, 006, 008, 009) |
| Tên file sai loại | case-016; case-013 có sẵn file gõ sai tên `calleer_webrtc.log` |
| File khác Call-ID | case-017 |
| `OUT_OF_SCOPE` (≥ 5 câu) | 7 câu: case-003 (5 câu) + 1 câu ở case-010, 1 câu ở case-012 |
| Ngoài 6.3 | case-019 (dữ liệu nhạy cảm giả, MVP 10 scenario 6), case-020 (câu hỏi giả định sai) |

## 2. Kết quả trên `dev` — lượt cuối Sprint 3, 2026-10-14 (MVP mục 6.5)

| Metric | Sprint 2 (06/10) | Mốc P0.3 (10/10) | Cuối Sprint 3 (14/10) | Target | Mức |
|---|---|---|---|---|---|
| Verdict Accuracy | 100% (280/280) | 100% (280/280) | **100% (310/310)** | ≥ 85% (đo chính thức trên `held-out`) | Bắt buộc |
| Metric Correctness | 100% | 100% | **100% (310/310)** | 100% | Bắt buộc |
| Security Leakage input / output | 0/7 005 · 0 | 0/7 005 · 0 | **0/8 255 · 0** (345 lần chạy, gồm application log) | 0% · 0 | Bắt buộc |
| Issue Category Accuracy | N/A | N/A | N/A — ground truth chưa có nhãn category | ≥ 70% | Báo cáo |
| Consistency | 100% (17/17 case) | 100% (17/17 case) | **100% (19/19 case)**, mọi case giống hệt qua 5 lần | ≥ 95% | Báo cáo |
| Template Compliance | 100% | 100% | **100%** | 100% | Báo cáo |
| Intent Accuracy | 100% (290/290) | 100% (290/290) | **100% (320/320)** | ≥ 90% | Báo cáo |
| Unsupported Claim — AI trước Guardrails | 2,9% (8/280) | 0% (0/280) | **0% (0/309)** | 0% | Báo cáo |
| Unsupported Claim — report cuối | 0% | 0% | **0%** | 0% | Báo cáo |
| Pipeline Success Rate | 100% | 100% | **100% (310/310)** — 1 lần lùi về rule vì OpenAI quá giờ, vẫn ra report | ≥ 95% | Báo cáo |
| Latency P50 / P95 | 3,8 s / 8,4 s | 3,4 s / 8,1 s | 3,6 s / **14,9 s** (xem dưới) | — | Báo cáo |

**Đọc bảng.** Kết luận do rule quyết, nên Verdict / Consistency / Intent ổn định qua các lượt là điều mong đợi chứ
chưa phải bằng chứng AI tự suy luận đúng (xem Known Limitations, mục AI). Thứ Sprint 3 cải thiện được nằm ở phần chữ AI
viết — xem mục 4.

**Độ trễ P95 14,9 s là do OpenAI lúc đo, không do code.** Thời gian riêng của lời gọi AI phân tích: lượt 2026-10-13
(`sau-giai-thich`) P50 1,8 s / P95 2,5 s; lượt cuối P50 2,5 s / P95 11,1 s, chậm nhất 45 s. Trong lượt cuối OpenAI trả 3
lỗi nhà cung cấp và 1 lần quá giờ — cơ chế thử lại (T8) xử lý được, riêng 1 lần quá giờ hẳn thì report lùi về rule
(`case-015`, đúng thiết kế, vẫn có report). Prompt dài thêm ~9% (5 129 so với 4 715 token vào mỗi lời gọi phân tích:
evidence có thêm RTT / jitter, prompt thêm quy tắc 9a–9c), không đủ giải thích mức chậm gấp bốn. Phần không phải AI của
pipeline vẫn dưới 1 s. Số đo độ trễ trên máy cá nhân chỉ dùng để so xu hướng.

**Bằng chứng: lượt chạy lại cùng ngày.** `benchmark/results/chay-lai-2026-10-14/evaluation-20261014-115405.md` — cùng bộ
case, ×5, **cùng dấu vân tay prompt** (`analyze-call.system.txt e8941db2acc1`, ba file còn lại cũng trùng), chạy sau
commit `bf0114b`, xong lúc 11:54:

| | Lượt 14/10 (mục 2) | Chạy lại 14/10 |
|---|---|---|
| Latency P50 / P95 / chậm nhất | 3,6 s / 14,9 s / 47,7 s | **3,0 s / 4,5 s / 9,0 s** |
| Fallback | 1/310 (TIMEOUT) | 0/310 |
| Verdict / Metric Correctness / Consistency / Template / Intent / Pipeline Success | 100% | 100% |
| Unsupported Claim AI thô / report cuối | 0/309 · 0% | 0/310 · 0% |

Cùng prompt, cùng bộ case, chỉ cách nhau khoảng một giờ mà P95 giảm từ 14,9 s xuống 4,5 s và không còn lần quá giờ —
phù hợp với kết luận độ trễ cao là do phía OpenAI lúc đo. Bảng mục 2 vẫn ghi số của lượt bản cuối; số của bản nộp ở
mục 9.

## 3. AI vs Rule

| Case | Rule | AI (trước Guardrails) | Final | Ground truth |
|---|---|---|---|---|
| case-001, 004–008, 019 | FAIL | FAIL | FAIL | FAIL |
| case-002, 009–014, 016, 017, 020 | SUCCESS | SUCCESS | SUCCESS | SUCCESS |
| case-015 | SUCCESS | SUCCESS ×14, 1 lần AI quá giờ | SUCCESS | SUCCESS |
| case-018 | UNKNOWN | UNKNOWN | UNKNOWN | UNKNOWN |

(`case-003` toàn câu ngoài phạm vi, không có report.) AI khớp rule ở mọi lần AI trả lời. **Vi phạm Guardrails: không có**
(hai lượt 2026-10-13 có 4 và 5 lần G05 ở `case-007` câu 3 — AI trừ hai mốc giờ ra `0.112`; đã sửa bằng quy tắc 4b;
toàn bộ các lượt: [`phan-tich-loi-dev.md`](phan-tich-loi-dev.md) mục 4).

## 3b. Đo lại sau góp ý Sprint 2 của mentor (2026-10-15)

Code sau các sửa theo nhận xét mentor: G05 bắt số viết liền đơn vị (design-decisions mục 43), cổng fail-closed trước khi
gửi AI (mục 44), ngưỡng mất gói cả cuộc từ 5 % (mục 46). Cùng bộ case `dev` (20 case, 69 câu × 5 = 345 lần chạy), cùng
dấu vân tay prompt `analyze-call.system.txt e8941db2acc1`. Lượt này cho biết các sửa trên không làm hỏng gì; số của bản
nộp ở mục 9. Kết quả: `benchmark/results/gop-y-mentor-json/evaluation-20261015-174838.md`.

| Metric | Lượt 14/10 (mục 2) | Sau góp ý mentor (15/10) |
|---|---|---|
| Verdict / Metric Correctness / Consistency / Template / Intent / Pipeline Success | 100% | **100%** (310/310; Intent 320/320) |
| Security Leakage input / output | 0/8 255 · 0 | **0/8 255 · 0** |
| Unsupported Claim AI thô / report cuối | 0/309 · 0% (G05 cũ) | **0/310 · 0%** (G05 mới, bắt cả `250ms`, `45s`) |
| Fallback | 1/310 (OpenAI quá giờ) | **0/310** — cổng fail-closed không chặn lần nào |
| Latency P50 / P95 | 3,6 s / 14,9 s | **3,1 s / 4,3 s** |

Với G05 mới, "Unsupported Claim 0%" không còn là cận dưới như nhận xét của mentor: không có số viết liền đơn vị nào bị
bỏ sót trong 310 lần AI trả lời.

### T11 Sprint 2 — structured (JSON) và semi-structured (văn bản)

MVP 6.1 T11: *"Semi-structured context vs structured evidence: accuracy, consistency, token, latency"*. Bản văn bản theo ví
dụ mentor gửi: cùng dữ kiện, cùng system prompt, chỉ khác cách trình bày context (`ai.context-format=text`, mục 45). Hai lượt
chạy cùng ngày, cùng code, cùng bộ case ×5. Kết quả văn bản: `benchmark/results/gop-y-mentor-text/evaluation-20261015-195724.md`.

| Tiêu chí | Structured (JSON) | Semi-structured (văn bản) |
|---|---|---|
| **Accuracy** — Verdict / Intent | 100% (310/310) / 100% (320/320) | 100% (310/310) / 100% (320/320) |
| **Consistency** | 100%, 19/19 case giống hệt qua 5 lần | 100%, 19/19 case |
| **Token** — phân tích, trung bình mỗi lời gọi | 5 153 vào + 145 ra | **4 768 vào (−7,5 %)** + 139 ra |
| **Latency** P50 / P95 / chậm nhất | 3,1 s / 4,3 s / 7,2 s | 3,0 s / 4,1 s / 9,9 s |
| Unsupported Claim AI thô · fallback | 0/310 · 0 | 0/310 · 0 |
| AI nhắc chỉ số qua mã | 90,5 % (286/316) | 92,2 % (238/258) |
| Evidence AI trích đủ dòng căn cứ của rule | **96/115** | 77/115 |
| AI trích HẾT mọi evidence (căn cứ mất tác dụng) | **6/310** | 20/310 |
| Evidence trích giống nhau giữa các lần lặp | 94,6 % | 93,6 % |

**Đọc bảng.**
- Accuracy và consistency **không phân biệt được** hai cách dựng context trên `dev`: kết luận do rule quyết, AI thấy kết
  luận của rule trong context (Known Limitations, mục AI). Muốn so được phần này cần thêm biến thể context không có
  `ruleVerdict` — chưa làm.
- Bản văn bản rẻ hơn khoảng 7,5 % token vào. Latency khác nhau trong mức dao động của OpenAI, không kết luận được.
- Bản JSON cho evidence tốt hơn: AI trích đủ căn cứ của rule ở 96/115 lần so với 77/115, và ít khi trích hết mọi evidence
  (6 so với 20 lần). Cờ `ruleBasis` là một trường riêng trong JSON; trong bản văn bản nó chỉ là một cụm chữ cuối dòng.
- **Giữ JSON làm mặc định**: chênh 7,5 % token không bù được việc evidence AI trích kém đi — evidence là phần MVP mục 3.3
  đòi phải có căn cứ.

## 4. Sprint 3 cải thiện gì, đo bằng gì

| Mảng | Trước | Sau | Đo bằng |
|---|---|---|---|
| Số AI tự viết | 2,9% đầu ra thô (Sprint 2); 4/310 và 5/310 ở `case-007` câu 3 (hai lượt 13/10, AI trừ hai mốc giờ ra `0.112`) | **0/309** lượt 14/10 (lượt kiểm riêng sau khi sửa 4b: 0/45); **0/310** lượt trước nộp 19/10, G05 mới | Unsupported Claim thô |
| Gán sai nghĩa con số | `271D1FAF`: mẫu 11,3% bị gọi là mất gói cả cuộc | AI nhắc chỉ số bằng mã, code điền tên + giá trị (T1a, mục 32) | Kiểm tay |
| Tóm tắt ca FAIL | AI viết "không thiết lập được" cho cuộc đã thiết lập ở 10/10 lần (`70C388AD`, `FE4EF5E7`) | Code viết từ lý do của rule (mục 40) | Kiểm tay, test |
| Câu hỏi giả định sai | AI nhận giả định, tìm "nguyên nhân" cho lỗi không có (`4FD495C1`) | Một phần: không còn bịa nguyên nhân, chưa luôn phủ nhận thẳng giả định (`case-020`, đọc tay) | Đọc tay |
| Độ tin cậy | Gán rải rác, không lý do | `ConfidencePolicy` theo bảng 7.2, report ghi lý do (T3) | Test theo bảng |
| Đề xuất | AI thay hẳn đề xuất của rule | Rule giữ bộ chuẩn, AI chỉ bổ sung (T2) | Kiểm tay |
| Lỗi khi hạ tầng hỏng | Không timeout phía trình duyệt | 429 tách lý do, ES tắt vẫn ra report, thử lại có kiểm soát (T4, T8) | Thử ES tắt, 429, file lớn |
| Log | `key=value`, thiếu `call_id` | JSON đủ bảy trường MVP 6.2, latency từng bước (T5) | Test log |
| Quan sát | Không có | Mỗi request một bản ghi vào ES, view Kibana (T9) | Kibana |
| Thống kê toàn kho | Chỉ xem từng cuộc | `--batch-analyze`: mọi Call-ID trong ES, phân bố verdict / loại lỗi tách theo nguồn dữ liệu (T10); dashboard kết quả và dashboard hàng loạt (T11) | Mục 10, Kibana |
| Độ trễ P95 | 8,4 s (gồm ~7 s sanitizer chậm ở `DE7DD314`) | Sanitizer đã sửa ở T4 (lượt 13/10: 3,8 s); lượt 14/10 14,9 s do OpenAI chậm; trước nộp 19/10: **4,04 s** (mục 9) | Runner |

## 5. Chạy trên dataset mới của mentor (`ai20k_new_dataset`, 2026-10-13 → 14)

7 cuộc gọi, **không có nhãn** — bảng dưới là kết luận của tool, không phải độ chính xác. Số của bản code cuối
(`--analyze-all=../ai20k_new_dataset`, chỉ rule, chạy lại 2026-10-20; 7/7 report hợp lệ theo schema). Cột "13–14/10" là
kết luận trước khi đổi ngưỡng mất gói sang cả cuộc từ 5 % (2026-10-15, theo mentor; design-decisions mục 46). Ảnh chụp
Web UI và report Markdown của từng cuộc (chụp 2026-10-14, trước khi đổi ngưỡng mất gói — khớp cột "13–14/10" của bảng dưới, không phải kết luận hiện tại): `../image-report-final/<CALL-ID>/` (trước khi sửa: `../image-report-new-dataset/`).

| Cuộc | Kết luận | Loại lỗi | Tin cậy | 13–14/10 | Ghi chú |
|---|---|---|---|---|---|
| 06BCA48C | SUCCESS | — | HIGH | `NETWORK_PACKET_LOSS`, LOW | Mất gói cả cuộc 0,36% / 0,15%, có đợt mẫu tới 38,6% |
| 4FD495C1 | SUCCESS | — | HIGH | `NETWORK_PACKET_LOSS`, LOW | Caller 6/91 mẫu > 5%, cả cuộc 1,39% |
| 70C388AD | FAIL | ICE_FAILURE | MEDIUM | không đổi | Caller nhận ~0,7 s âm thanh rồi im 12 s; thiếu end call log callee |
| B5F19381 | SUCCESS | NETWORK_PACKET_LOSS | LOW | không đổi | Caller mất gói 8,48% cả cuộc, MOS thấp nhất 1,94 |
| FC3187B4 | SUCCESS | NETWORK_DELAY_JITTER | LOW | không đổi | RTT trung bình ~540 ms cả cuộc; callee có 2 thiết bị |
| FE4E4BDC | SUCCESS | — | HIGH | `NETWORK_PACKET_LOSS`, LOW | Cuộc 9 s, mất gói cả cuộc 4,65 % (dưới ngưỡng 5 %), RTT ~690 ms |
| FE4EF5E7 | FAIL | ICE_FAILURE | MEDIUM | không đổi | Nói ~30 s rồi rớt; BYE của callee không tới server |

Lỗi tool tìm ra nhờ dataset này và đã sửa (chi tiết: [`bay-du-lieu.md`](bay-du-lieu.md) bẫy #20–#22, design-decisions mục 40–41):

1. `signaling.json` có hai định dạng export — bản thô bị từ chối 3/7 file. Nay đọc cả hai.
2. Callee nhiều thiết bị — chỉ số lấy nhầm máy không bắt máy, report nói "callee không nhận được âm thanh". Nay lấy máy
   có âm thanh, evidence gắn nhãn thiết bị.
3. Chỉ số media không ghi chiều — AI dùng mất gói của caller để giải thích "bên nhận không nghe được". Nay tên gửi AI ghi
   "(caller nhận)", prompt bắt nói rõ chiều.
4. Cuộc rớt giữa chừng bị giải thích là "media không thiết lập được". Nay lý do và evidence nói rõ đã có âm thanh rồi mới mất.
5. Độ trễ cao kéo dài không được gắn cờ (`FC3187B4`). Nay có rule `NETWORK_DELAY_JITTER` theo ngưỡng Teams CQD.

Kết luận trên data mẫu không đổi sau các sửa trên: 13/13 cuộc có nhãn đúng.

Dataset không có nhãn nên kết luận của 7 cuộc chưa được kiểm chứng, nhất là `70C388AD` và `FE4EF5E7` (tool ra FAIL;
một người khác kết luận SUCCESS).

## 6. Kết quả `held-out`

Tập `held-out` do mentor giữ và chạy bằng Evaluation Runner của dự án (MVP 6.3); tài liệu này chưa có số đo đó.
Cách chạy: README, mục "Chạy bộ case `held-out`".

## 7. Known Limitations

### Ngưỡng chất lượng

- **Không có cuộc gọi chất lượng kém có nhãn** → `NETWORK_PACKET_LOSS`, `NETWORK_DELAY_JITTER` vẫn `UNVALIDATED`; mọi
  cờ chất lượng ở độ tin cậy LOW.
- **Ngưỡng mất gói theo mentor (đổi 2026-10-15).** Cờ `NETWORK_PACKET_LOSS` bật khi một leg mất gói CẢ CUỘC từ 5 %
  (packetsLost ÷ (packetsLost + packetsReceived)) — mentor: "packet loss < 5 %, jitter < 30 ms là ổn định". Trước đó cờ
  bật khi MỘT mẫu stats > 5 %, MOS < 3,5 hoặc hasMediaPoor = 1. Số mẫu vượt, MOS thấp nhất, hasMediaPoor vẫn in ở phần
  "Đối chiếu" nhưng không tự bật cờ: cuộc MOS rất thấp mà mất gói cả cuộc thấp (`FE4E4BDC`: MOS 1,00, một mẫu mất 100 %)
  nay là `SUCCESS` không cờ. Bảng độ nhạy: `scripts/quality-thresholds/sensitivity.py` (design-decisions mục 46).
- **Ngưỡng độ trễ / jitter** lấy từ Microsoft Teams CQD (RTT / jitter trung bình > 500 ms / 30 ms, luồng > 500 gói),
  đối chiếu Twilio và ITU-T G.114. Có nguồn nhưng chưa kiểm trên data có nhãn. Cuộc ngắn (≤ 500 gói) không xét, như Teams.
- Khi cả mất gói và độ trễ cùng vượt, rule xếp `NETWORK_PACKET_LOSS` (theo MVP mục 10 scenario 2).

### Kết luận

- **Cuộc rớt giữa chừng** xếp `ICE_FAILURE` vì taxonomy MVP 4.2 không có loại riêng.
- Tín hiệu `network_lost` (end call log, `originator=remote`) chỉ dùng để giải thích; nó không chứng minh bên kia mất
  mạng — app báo nó ~7 s sau khi hết dữ liệu từ bên kia.
- **Gọi nhóm** (3 người trở lên) không hỗ trợ; nhiều thiết bị của **một** người nhận thì có ([`bay-du-lieu.md`](bay-du-lieu.md) bẫy #21).
- Các mục còn mở từ Sprint 2 vẫn giữ: `UNKNOWN` chưa có nhãn của mentor (đáp án `UNKNOWN` của case-018 suy từ luật
  "thiếu file → UNKNOWN" của MVP 4.1); "evidence mâu thuẫn" chưa cài; mã 428 xếp tạm
  `SIGNALING_FAILURE`; thiếu log một bên ra SUCCESS / FAIL (theo nhãn `5E0800AE`) chứ không `UNKNOWN` như scenario 5.

### Phần chữ AI viết

Guardrails chỉ kiểm phần có cấu trúc (verdict, category, evidence ID, con số có nguồn), **không kiểm nghĩa câu chữ**.
Các lỗi diễn giải còn gặp trên dataset mới:

- Phóng đại mức độ: "mất gói đáng kể" khi cả cuộc 0,36% (`06BCA48C`) dù prompt quy tắc 7a cấm tự đánh giá.
- Phần phân tích ca FAIL: gắn mã chỉ số vào câu không đúng nghĩa ("không nhận được âm thanh: {PACKET_LOSS_CALLER}"),
  có lần ghi nhầm bên của `network_lost` (`FE4EF5E7`). Tóm tắt ca FAIL đã chuyển sang code viết (mục 40).
- Câu hỏi giả định sai: AI không còn bịa nguyên nhân nhưng chưa luôn phủ nhận thẳng giả định (`case-020`).
- Gọi RTT lúc kết thúc là "RTT trung bình" (`FC3187B4`).

### Bộ benchmark `dev`

- Thiếu loại ca "SUCCESS chất lượng kém" của MVP 6.3: data mẫu không có cuộc gọi chất lượng kém có nhãn.
- "FAIL thiết lập" chỉ có kiểu server từ chối (mã 428, case-004 / 007); không có ca signaling timeout hay "không tìm
  thấy session".
- "Bình thường có WARN" không có case riêng: dựa vào việc mọi cuộc SUCCESS đều có 5-13 event WARN.
- `expected_quality_flag`, `expected_issue_category`, `expected_evidence` để trống ở mọi case (ground truth chưa có), nên
  Issue Category Accuracy là N/A.

### Dữ liệu và vận hành

- Chỉ số chất lượng chỉ có ở leg có end call log; MOS / RTT / jitter trong bảng là giá trị lúc kết thúc cuộc gọi.
- "Số lần No sessions found" chỉ đếm được với bản export signaling thô (có trường `message`); toàn bộ `ai20k_sample` là
  bản gọn nên chỉ số này là `N/A`.
- Giới hạn độ dài câu hỏi (4 000 ký tự) chỉ áp ở Chat API; Evaluation Runner và CLI không chặn.
- WebRTC log chỉ có mốc tương đối, chưa đồng bộ với timeline signaling.
- Signaling nạp vào ES ở dạng gốc (`appUserId`), chưa qua Sanitizer; mọi đường ra AI, report và log đều qua Sanitizer
  (Security Evaluation Report mục 4.3).
- Phân tích hàng loạt (T10) chỉ bằng dữ liệu trong ES không kết luận được SUCCESS và chỉ thấy triệu chứng phía server
  (lỗi TURN hiện thành `SIGNALING_FAILURE`): ES không có log máy. Muốn thống kê có ý nghĩa phải kèm `--logs`. Mục 10.

### Peer Evaluation về repo này

Báo cáo của Trần Anh Tú về repo này (2026-10-20, commit `a905256`; nằm trong repo của bạn ấy,
`docs/peer-evaluation-report.md`): Runbook **đủ** để dựng lại, mọi số khớp (590 test, `--analyze-all` 13/13, `dev`
100 %); 2 issue, đều tái hiện được trên server profile `web` của mình, đã sửa ngày 2026-10-20 (design-decisions mục 49):

| Issue của bạn ấy | Xử lý |
|---|---|
| Call-ID trong câu hỏi khác cuộc gọi của file: end call log bị loại nhưng WebRTC log vẫn quyết định verdict (log `2D9057AA` + Call-ID `DE7DD314` ra `DE7DD314` FAIL / ICE, MEDIUM) | **Sửa:** lượt tải lên chỉ có end call log của cuộc khác thì WebRTC log đi kèm cũng bị loại → ca này nay ra `UNKNOWN`, LOW; Tóm tắt, Đề xuất ("kiểm tra lại Call-ID…") và hai dòng đầu "Giới hạn dữ liệu" nói thẳng log thuộc cuộc gọi khác. **Còn lại (Known Limitation):** chỉ đính kèm WebRTC log và gõ Call-ID — không có gì để kiểm; report ghi cảnh báo "Call-ID lấy từ câu hỏi … không kiểm chứng được" |
| End call log có BOM UTF-8 / lưu UTF-16 bị coi là hỏng → `UNKNOWN` | **Sửa:** bỏ BOM UTF-8, đọc UTF-16 theo BOM; hai ca này nay ra đúng FAIL / ICE như file gốc. UTF-16 không BOM vẫn bị loại, thông báo gợi ý lưu lại dạng UTF-8 |
| Góp ý Runbook: `build.ps1` dò thêm `~/.jdks/*21*`; mục 1 thiếu Python cho Kibana | Đã sửa cả hai |

Sau khi sửa: 600 test, 0 lỗi; `--analyze-all` 20 cuộc ra đúng từng dòng như trước; `dev` chỉ rule: Verdict 62/62,
`case-017` (F03, có end call log khớp) vẫn giữ WebRTC log. (Chiều ngược lại — báo cáo của mình về repo bạn ấy:
[`peer-evaluation-report.md`](peer-evaluation-report.md).)

## 8. Thay đổi sau lượt 14/10 (rà soát theo MVP, 2026-10-19)

Rà soát code theo MVP tìm ra năm chỗ; đã sửa kèm test (`mvn test`: 565 test, 0 lỗi). Lý do: design-decisions mục 47.

| Sửa | Ảnh hưởng tới số đo |
|---|---|
| Call-ID chuẩn hoá viết hoa ở File Validator và bộ đọc file case | Không đổi `dev` (Call-ID đã viết hoa). Bỏ rủi ro file case `held-out` ghi Call-ID chữ thường làm mọi case ra `UNKNOWN` |
| Trích dẫn `tên file:dòng` trong report qua Sanitizer; mẫu SĐT nhận cả số dính `_` | Không đổi data mẫu (quét lại: số khớp như cũ) |
| "Số lần No sessions found" đếm được với bản export thô | `ai20k_sample` không đổi (bản gọn, vẫn `N/A`); 3 cuộc thô của `ai20k_new_dataset` có số (3 / 4 / 3 lần), nên context AI của các cuộc đó đổi |
| S06 kiểm đúng đường làm sạch của pipeline; xoá `JsonSanitizer` không dùng | Không |
| Câu trả lời AI thiếu trường bắt buộc → `INVALID_RESPONSE`; `analysis` rỗng → G02; trần 2 000 token ra; câu hỏi quá 4 000 ký tự bị Chat API từ chối | Request gửi OpenAI có thêm `max_completion_tokens` (chạy thử AI thật: 2/2 lời gọi `OK`, Guardrails qua) |

Prompt và schema AI không đổi (dấu vân tay như mục 1), nhưng request gửi AI đã khác, nên số ở mục 2-3 là của bản code
`bf0114b`. Số của bản code cuối: mục 9.

## 9. Chạy lại trước khi nộp (MVP 7.1, 2026-10-19)

> MVP 7.1: *"Trước khi nộp, chạy lại toàn bộ: S01-S08, G01-G05 (kèm unexpected enum, missing fields, oversized input),
> F01-F04 và toàn bộ benchmark `dev` để so với kết quả Sprint 2."* Code: bản sau mục 8 (`mvn test` lúc chạy: 565 test, 0 lỗi; bản nộp sau T10 và sửa theo Peer Evaluation: 600).

### S01-S08, G01-G05, F01-F04

Bảng sinh tự động từ báo cáo Surefire (`scripts/error-analysis/sgf_report.py`): mỗi test mang mã ca ở `@DisplayName`,
ba nhóm của MVP 7.1 liệt kê tên test tường minh trong script.

| Ca | Test | Kết quả | | Ca | Test | Kết quả |
|---|---|---|---|---|---|---|
| S01 JWT | 1 | qua | | G01 Evidence ID không tồn tại | 1 | qua |
| S02 Authorization | 1 | qua | | G02 Sai format | 5 | qua |
| S03 API key trong exception | 2 | qua | | G03 Ngoài taxonomy | 2 | qua |
| S04 Phone / email | 4 | qua | | G04 AI mâu thuẫn rule | 2 | qua |
| S05 IP / device ID | 5 | qua | | G05 Số ngoài bộ chỉ số | 6 | qua |
| S06 Nested JSON | 2 | qua | | F01 Thiếu file một bên | 1 | qua |
| S07 AI trả lại giá trị nhạy cảm | 2 | qua | | F02 Tên file sai nội dung | 2 | qua |
| S08 Error message / log | 3 | qua | | F03 Khác Call-ID | 6 | qua |
| 7.1 enum lạ | 4 | qua | | F04 Hỏng / quá lớn | 8 | qua |
| 7.1 thiếu trường | 5 | qua | | 7.1 input quá lớn | 5 | qua |

**67/67 test qua** (2026-10-19; sau sửa theo Peer Evaluation 2026-10-20, F03 thêm 2 test: 69/69). Phép đo leakage trên `dev` (bên dưới) và kiểm độc lập bằng script trên data mẫu: Security Evaluation
Report [`bao-cao-bao-mat.md`](bao-cao-bao-mat.md). Sprint 2 (2026-10-07): đủ 17 ca có test, `mvn test` 390 test qua; chưa có test cho thiếu trường
bắt buộc, câu hỏi quá dài, trần token ra, SĐT dính `_`, Call-ID khác hoa thường; S06 khi đó kiểm một class pipeline
không gọi (mục 8).

### Benchmark `dev` ×5

Lượt `evaluation-20261019-162735` (16:10 → 16:27, 17 phút): 20 case, 69 câu × 5 = 345 lần chạy, `gpt-4o-mini`, cùng
dấu vân tay prompt với mục 1, context JSON. ~1,91 triệu token vào + ~50 nghìn ra trên 655 lời gọi ≈ **0,32 USD**.

| Metric (MVP 6.5) | Sprint 2 (2026-10-06) | Cuối Sprint 3 (2026-10-14, mục 2) | Trước nộp (2026-10-19) | Target |
|---|---|---|---|---|
| Quy mô | 18 case, 315 lần chạy | 20 case, 345 lần chạy | 20 case, 345 lần chạy | |
| Verdict Accuracy | 100% (280/280) | 100% (310/310) | **100% (310/310)** | ≥ 85% (`held-out`) |
| Metric Correctness | 100% | 100% | **100% (310/310)** | 100% |
| Security Leakage input | 0 / 7 005 | 0 / 8 255 | **0 / 8 255** | 0% |
| Security Leakage output (report · log) | 0 · chưa đo | 0 · 0 | **0 · 0** (345/345 lần có log) | 0 |
| Issue Category Accuracy | N/A | N/A | N/A — chưa có nhãn | ≥ 70% |
| Consistency | 100% (17/17 case) | 100% (19/19) | **100% (19/19)** | ≥ 95% |
| Template Compliance | 100% | 100% | **100%** | 100% |
| Intent Accuracy | 100% (290/290) | 100% (320/320) | **100% (320/320)** | ≥ 90% |
| Unsupported Claim — AI thô / report cuối | 2,9% / 0% | 0% (0/309) / 0% | **0% (0/310) / 0%** — G05 bắt cả số liền đơn vị | 0% |
| Pipeline Success Rate | 100% | 100% (310/310) | **100% (310/310)** | ≥ 95% |
| Latency P50 / P95 | 3,8 s / 8,4 s | 3,6 s / 14,9 s | **2,95 s / 4,04 s** (chậm nhất 11,3 s) | — |
| Fallback | — | 1/310 (OpenAI quá giờ) | **0/310** | — |
| Evidence AI trích đủ căn cứ của rule | — | 94/115 | **98/115** | — |

AI vs Rule: 19/19 case Rule = AI = Final = Ground Truth (bảng giống mục 3); 0 vi phạm Guardrails, 0 lần AI lệch rule.
Kết luận: các sửa ở mục 8 không làm đổi kết luận nào trên `dev`; mọi metric `Bắt buộc` và `Báo cáo` có đo được đều đạt
target. Latency gần lượt đo lại 15/10 (3,1 s / 4,3 s, cùng prompt); P95 14,9 s của lượt 14/10 là do OpenAI chậm lúc đo
(mục 2).

## 10. Phân tích hàng loạt (T10, 2026-10-19)

> MVP 7.1 T10 (Nếu kịp): *"Phân tích hàng loạt các Call-ID trong ES local, thống kê phân bố verdict / issue category"*.
> Lệnh `--batch-analyze`, lý do thiết kế: design-decisions mục 48. Làm sau mục 9 và không sửa dòng nào của luồng phân
> tích (chỉ thêm lệnh, thống kê, adapter ES), nên số đo mục 9 vẫn là của bản nộp. `mvn test`: 590 test, 0 lỗi.

Đầu vào: 27 Call-ID trong index signaling (20 `ai20k_sample` + 7 `ai20k_new_dataset`), mỗi cuộc qua đúng pipeline của
Web UI, chỉ rule (0 USD, tất định). Elasticsearch chỉ chứa signaling — log máy do người dùng đính kèm (MVP 1.2) — nên
chạy hai chế độ:

| | Chỉ signaling (không `--logs`) | Signaling + log máy (`--logs` hai dataset) |
|---|---|---|
| Số cuộc | 27 | 27 (27/27 tìm được thư mục log) |
| `SUCCESS` | 0 | 13 (2 có cờ chất lượng kém) |
| `FAIL` | 11 | 14 |
| `UNKNOWN` | 16 (59,3 %) | 0 |
| Loại lỗi | 11 `SIGNALING_FAILURE` | 6 `TURN_FAILURE` · 5 `SIGNALING_FAILURE` · 3 `ICE_FAILURE` · 1 `NETWORK_PACKET_LOSS` · 1 `NETWORK_DELAY_JITTER` |
| Độ tin cậy | 11 MEDIUM · 16 LOW | 11 HIGH · 14 MEDIUM · 2 LOW |
| Đúng nhãn (13 cuộc `success/` + `fail/`) | 5/13 | 13/13 |

Đọc hai cột:

- **Chỉ signaling không kết luận được SUCCESS — đó là đúng, không phải lỗi.** Signaling cho biết cuộc gọi có thiết lập
  được không, không cho biết âm thanh có tới không: `2D9057AA` signaling hoàn hảo mà 0 byte âm thanh (bẫy #12). Rule xét
  media trước khi được kết luận SUCCESS, không có log máy thì trả UNKNOWN (MVP 3.3). 16 cuộc UNKNOWN, khi có đủ log, ra
  13 SUCCESS và 3 FAIL `ICE_FAILURE` (`2D9057AA` và hai cuộc rớt giữa chừng `70C388AD`, `FE4EF5E7`).
- **FAIL từ signaling tin được:** cả 11 cuộc cũng ra FAIL khi có đủ log. Không cuộc nào đổi từ SUCCESS sang FAIL hay ngược
  lại giữa hai chế độ — chỉ UNKNOWN được thay bằng kết luận.
- **Loại lỗi từ signaling chỉ là triệu chứng phía server.** 6 cuộc lỗi TURN hiện ra thành `SIGNALING_FAILURE`: server chỉ
  thấy cuộc gọi không nối được, nguyên nhân TURN nằm trong WebRTC log. Thống kê loại lỗi của nhóm chỉ signaling vì vậy
  không dùng để kết luận nguyên nhân.

Vì vậy báo cáo (`target/batch/*.md`) và dashboard Kibana "Phân tích hàng loạt — toàn kho" (index `call-analysis-results`)
luôn tách hai nhóm theo nguồn dữ liệu của TỪNG cuộc, và in mục "Cách đọc" khi có cuộc chỉ có signaling. Muốn thống kê
phân bố có ý nghĩa thì cần log máy (`--logs`).
