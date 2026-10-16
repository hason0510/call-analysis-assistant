# Final Evaluation Report & Known Limitations — Sprint 3

> Tài liệu bàn giao số 3 của MVP mục 7.4: accuracy, consistency, AI vs Rule, latency trên `dev`; kết quả `held-out` do
> Mentor gửi lại. Cách tính từng metric: [`evaluation-metrics.md`](evaluation-metrics.md). Các quyết định thiết kế nhắc
> tới ở đây: [`design-decisions.md`](design-decisions.md) (số mục trong ngoặc).
>
> Số đo: lượt benchmark cuối `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md` (không commit thư mục
> `benchmark/results/`; số được chép vào đây). Ô ⏳ là việc còn chờ.

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

## 2. Kết quả trên `dev` (MVP mục 6.5)

| Metric | Sprint 2 (06/10) | Mốc P0.3 (10/10) | Cuối Sprint 3 (14/10) | Target | Mức |
|---|---|---|---|---|---|
| Verdict Accuracy | 100% (280/280) | 100% (280/280) | **100% (310/310)** | ≥ 85% (đo chính thức trên `held-out`) | Bắt buộc |
| Metric Correctness | 100% | 100% | **100% (310/310)** | 100% | Bắt buộc |
| Security Leakage input / output | 0/7 005 · 0 | 0/7 005 · 0 | **0/8 255 · 0** (345 lần chạy, gồm application log) | 0% · 0 | Bắt buộc |
| Issue Category Accuracy | N/A | N/A | N/A — ground truth chưa có nhãn category | ≥ 70% | Báo cáo |
| Consistency | 100% | 100% | **100% (310/310)**, mọi case giống hệt qua 5 lần | ≥ 95% | Báo cáo |
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

| | Bản cuối (chính thức) | Chạy lại |
|---|---|---|
| Latency P50 / P95 / chậm nhất | 3,6 s / 14,9 s / 47,7 s | **3,0 s / 4,5 s / 9,0 s** |
| Fallback | 1/310 (TIMEOUT) | 0/310 |
| Verdict / Metric Correctness / Consistency / Template / Intent / Pipeline Success | 100% | 100% |
| Unsupported Claim AI thô / report cuối | 0/309 · 0% | 0/310 · 0% |

Cùng prompt, cùng bộ case, chỉ cách nhau khoảng một giờ mà P95 giảm từ 14,9 s xuống 4,5 s và không còn lần quá giờ —
phù hợp với kết luận độ trễ cao là do phía OpenAI lúc đo. Số chính thức vẫn lấy từ lượt bản cuối.

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
dấu vân tay prompt `analyze-call.system.txt e8941db2acc1`. Số chính thức của Sprint 3 vẫn là lượt 14/10 ở mục 2; lượt này
cho biết các sửa trên không làm hỏng gì. Kết quả: `benchmark/results/gop-y-mentor-json/evaluation-20261015-174838.md`.

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
| Số AI tự viết | 2,9% đầu ra thô (Sprint 2); 4/310 và 5/310 ở `case-007` câu 3 (hai lượt 13/10, AI trừ hai mốc giờ ra `0.112`) | **0/309** lượt cuối (lượt kiểm riêng sau khi sửa 4b: 0/45) | Unsupported Claim thô |
| Gán sai nghĩa con số | `271D1FAF`: mẫu 11,3% bị gọi là mất gói cả cuộc | AI nhắc chỉ số bằng mã, code điền tên + giá trị (T1a, mục 32) | Kiểm tay |
| Tóm tắt ca FAIL | AI viết "không thiết lập được" cho cuộc đã thiết lập ở 10/10 lần (`70C388AD`, `FE4EF5E7`) | Code viết từ lý do của rule (mục 40) | Kiểm tay, test |
| Câu hỏi giả định sai | AI nhận giả định, tìm "nguyên nhân" cho lỗi không có (`4FD495C1`) | Một phần: không còn bịa nguyên nhân, chưa luôn phủ nhận thẳng giả định (`case-020`, đọc tay) | Đọc tay |
| Độ tin cậy | Gán rải rác, không lý do | `ConfidencePolicy` theo bảng 7.2, report ghi lý do (T3) | Test theo bảng |
| Đề xuất | AI thay hẳn đề xuất của rule | Rule giữ bộ chuẩn, AI chỉ bổ sung (T2) | Kiểm tay |
| Lỗi khi hạ tầng hỏng | Không timeout phía trình duyệt | 429 tách lý do, ES tắt vẫn ra report, thử lại có kiểm soát (T4, T8) | Thử ES tắt, 429, file lớn |
| Log | `key=value`, thiếu `call_id` | JSON đủ bảy trường MVP 6.2, latency từng bước (T5) | Test log |
| Quan sát | Không có | Mỗi request một bản ghi vào ES, view Kibana (T9) | Kibana |
| Độ trễ P95 | 8,4 s (gồm ~7 s sanitizer chậm ở `DE7DD314`) | Sanitizer đã sửa ở T4 (lượt 13/10: 3,8 s); lượt cuối 14,9 s do OpenAI chậm | Runner |

## 5. Chạy trên dataset mới của mentor (`ai20k_new_dataset`, 2026-10-13 → 14)

7 cuộc gọi, **không có nhãn** — bảng dưới là kết luận của tool, không phải độ chính xác. Ảnh chụp Web UI và report
Markdown của từng cuộc: `../image-report-final/<CALL-ID>/` (trước khi sửa: `../image-report-new-dataset/`).

| Cuộc | Kết luận | Loại lỗi | Tin cậy | Ghi chú |
|---|---|---|---|---|
| 06BCA48C | SUCCESS | NETWORK_PACKET_LOSS | LOW | Mất gói cả cuộc 0,36% / 0,15%, có đợt mẫu tới 38,6% |
| 4FD495C1 | SUCCESS | NETWORK_PACKET_LOSS | LOW | Caller 6/91 mẫu > 5%, cả cuộc 1,39% |
| 70C388AD | FAIL | ICE_FAILURE | MEDIUM | Caller nhận ~0,7 s âm thanh rồi im 12 s; thiếu end call log callee |
| B5F19381 | SUCCESS | NETWORK_PACKET_LOSS | LOW | Caller mất gói 8,48% cả cuộc, MOS thấp nhất 1,94 |
| FC3187B4 | SUCCESS | NETWORK_DELAY_JITTER | LOW | RTT trung bình ~540 ms cả cuộc; callee có 2 thiết bị |
| FE4E4BDC | SUCCESS | NETWORK_PACKET_LOSS | LOW | Cuộc 9 s, RTT ~690 ms; luật mất gói xét trước độ trễ |
| FE4EF5E7 | FAIL | ICE_FAILURE | MEDIUM | Nói ~30 s rồi rớt; BYE của callee không tới server |

**Sau khi đổi ngưỡng mất gói (2026-10-15, cả cuộc từ 5 %, theo mentor)**, chạy lại bằng rule (`--analyze-all`):
`06BCA48C`, `4FD495C1`, `FE4E4BDC` thành `SUCCESS` không cờ (mất gói cả cuộc 0,36 %, 1,39 %, 4,65 %); `B5F19381` là cuộc
duy nhất còn `NETWORK_PACKET_LOSS`; `FC3187B4` (độ trễ), `70C388AD`, `FE4EF5E7` (FAIL) không đổi.

Lỗi tool tìm ra nhờ dataset này và đã sửa (chi tiết: CLAUDE.md bẫy #20–#22, design-decisions mục 40–41):

1. `signaling.json` có hai định dạng export — bản thô bị từ chối 3/7 file. Nay đọc cả hai.
2. Callee nhiều thiết bị — chỉ số lấy nhầm máy không bắt máy, report nói "callee không nhận được âm thanh". Nay lấy máy
   có âm thanh, evidence gắn nhãn thiết bị.
3. Chỉ số media không ghi chiều — AI dùng mất gói của caller để giải thích "bên nhận không nghe được". Nay tên gửi AI ghi
   "(caller nhận)", prompt bắt nói rõ chiều.
4. Cuộc rớt giữa chừng bị giải thích là "media không thiết lập được". Nay lý do và evidence nói rõ đã có âm thanh rồi mới mất.
5. Độ trễ cao kéo dài không được gắn cờ (`FC3187B4`). Nay có rule `NETWORK_DELAY_JITTER` theo ngưỡng Teams CQD.

Kết luận trên data mẫu không đổi sau các sửa trên: 13/13 cuộc có nhãn đúng.

**Câu hỏi mở với mentor:** dataset này có được dùng để sửa tool không (với `for_test/` mentor dặn không hiệu chỉnh); đáp
án của 7 cuộc, nhất là `70C388AD` và `FE4EF5E7` (tool ra FAIL; một người khác kết luận SUCCESS).

## 6. Kết quả `held-out`

⏳ Chờ mentor chạy Evaluation Runner trên `held-out` và gửi lại. Cách chạy: README, mục "Chạy bộ case `held-out`".

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
- **Gọi nhóm** (3 người trở lên) không hỗ trợ; nhiều thiết bị của **một** người nhận thì có (bẫy #21).
- Các mục còn mở từ Sprint 2 vẫn giữ: `UNKNOWN` chưa có ca có nhãn; "evidence mâu thuẫn" chưa cài; mã 428 xếp tạm
  `SIGNALING_FAILURE`; thiếu log một bên ra SUCCESS / FAIL (theo nhãn `5E0800AE`) chứ không `UNKNOWN` như scenario 5.

### Phần chữ AI viết

Guardrails chỉ kiểm phần có cấu trúc (verdict, category, evidence ID, con số có nguồn), **không kiểm nghĩa câu chữ**.
Các lỗi diễn giải còn gặp trên dataset mới:

- Phóng đại mức độ: "mất gói đáng kể" khi cả cuộc 0,36% (`06BCA48C`) dù prompt quy tắc 7a cấm tự đánh giá.
- Phần phân tích ca FAIL: gắn mã chỉ số vào câu không đúng nghĩa ("không nhận được âm thanh: {PACKET_LOSS_CALLER}"),
  có lần ghi nhầm bên của `network_lost` (`FE4EF5E7`). Tóm tắt ca FAIL đã chuyển sang code viết (mục 40).
- Câu hỏi giả định sai: AI không còn bịa nguyên nhân nhưng chưa luôn phủ nhận thẳng giả định (`case-020`).
- Gọi RTT lúc kết thúc là "RTT trung bình" (`FC3187B4`).

### Dữ liệu và vận hành

- Chỉ số chất lượng chỉ có ở leg có end call log; MOS / RTT / jitter trong bảng là giá trị lúc kết thúc cuộc gọi.
- WebRTC log chỉ có mốc tương đối, chưa đồng bộ với timeline signaling.
- Lệnh dòng lệnh với thư mục tên viết thường (như `ai20k_new_dataset`): Call-ID so phân biệt hoa thường nên file log bị
  loại; Web UI không bị (Call-ID lấy từ log).
- Signaling nạp vào ES ở dạng gốc (`appUserId`), chưa qua Sanitizer — chờ mentor xác nhận cách hiểu MVP 6.2.

