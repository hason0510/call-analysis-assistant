# Cách tính metric — Sprint 1 và Sprint 2

> Viết ngày 2026-10-06. Tài liệu này gom về một chỗ: MVP yêu cầu đo gì, dự án đo thế nào, và vì sao đo như vậy.
> Mỗi chỗ đều ghi rõ là **MVP quy định** hay **dự án tự quyết** — MVP thường chỉ có một dòng mô tả cho mỗi metric.

Có ba lớp "metric" khác nhau, đừng lẫn:

| Lớp | Đo cái gì | Nguồn yêu cầu |
|---|---|---|
| 1. Tiêu chí nghiệm thu Sprint 1 | Nền dữ liệu đúng chưa: parse, timeline, chỉ số, độ bền | MVP mục 5.1 |
| 2. Chỉ số cuộc gọi | Con số trong report của MỘT cuộc gọi: thời gian thiết lập, MOS… | MVP mục 4.3 |
| 3. Evaluation Metrics Sprint 2 | Cả hệ thống tốt tới đâu trên một bộ case: accuracy, consistency… | MVP mục 6.5 |

---

## 1. Sprint 1 — tiêu chí nghiệm thu (MVP mục 5.1)

Kết quả Sprint 1 đã có trong báo cáo Sprint 1; bảng dưới tóm tắt lại để đối chiếu.

| Tiêu chí (MVP) | Cách đo | Vì sao đo như vậy | Kết quả |
|---|---|---|---|
| Parse ≥ 90% log hợp lệ | Mỗi dòng không rỗng rơi vào đúng một trong bốn loại: header, sinh event, dòng nối tiếp (gộp vào bản ghi trước), cảnh báo `ParseWarning`. **Dòng mất = dòng có cảnh báo** | Đếm "số event ÷ số dòng" sai: dòng nối tiếp gộp lại làm số event ít hơn, signaling từ ES cộng thêm làm số event nhiều hơn | 29 238 dòng, 0 dòng file có cảnh báo → **100%** |
| Timeline đúng thứ tự; xử lý duplicate | Unit test (`TimelineBuilderTest`) + phép thử tay; lệch đồng hồ đo bằng `ClockOffsetEstimator` (trung vị hiệu thời gian `send_cmd` ↔ signaling) | Data mẫu không có file trùng thật (đã so md5) → phải tự tạo bản trùng để thử | Đạt; giới hạn: WebRTC log chỉ có mốc tương đối, không sắp chung được với signaling |
| Invalid input không làm crash | Test riêng từng parser + thả file nhị phân / rỗng / dòng 300 000 ký tự vào thư mục cuộc gọi | Yêu cầu "parser không được throw" của MVP mục 3.3 | Đạt |
| Chỉ số khớp 100% với tính tay trên ≥ 5 cuộc | Tính tay từ log gốc rồi so với `--metrics-all`; script kiểm độc lập `scripts/verify-metrics/` | Tính tay độc lập mới bắt được lỗi công thức (cùng code tính hai lần thì luôn khớp) | 36/36 giá trị thời gian (9 cuộc) + 280/280 giá trị còn lại, 0 lệch |
| Unit test đầy đủ cho core logic | Đếm test, 0 lỗi | — | 197 test lúc nghiệm thu (nay 392) |

**Đo thêm, ngoài 5.1** (lệnh `--analyze-all`): Verdict Accuracy **13/13** trên 6 cuộc `fail/` + 7 cuộc `success/`,
report hợp lệ theo schema **20/20**. Ground truth duy nhất là tên thư mục `fail/` / `success/`; `for_test/` không có nhãn
nên không tính accuracy.

---

## 2. Chỉ số cuộc gọi (MVP mục 4.3) — Metrics Calculator

Đây là các con số **trong report**, không phải số đánh giá hệ thống. Code: `domain/metrics/MetricsCalculator`.

**Quy tắc chung (MVP 4.3):** chỉ số không có dữ liệu → `N/A (lý do cụ thể)`, **không bao giờ về 0**
(`MetricValue = Present | NotAvailable(reason)`).

| Chỉ số (Core) | Cách tính | Bẫy đã gặp (CLAUDE.md) |
|---|---|---|
| Thời gian thiết lập | `INIT_CALL` đầu → `OK_ACK` đầu (MVP định nghĩa) | Trừ ở độ chính xác nano rồi mới cắt về mili (bẫy #14) |
| Thời gian với tới callee | `INVITE` đầu → `TRYING` (MVP) | |
| Số lần gửi lại INVITE / BYE | Gom các dòng cùng lệnh thành **cụm cách nhau ≥ 250 ms**, số lần gửi lại = số cụm − 1 | Một lần gửi sinh nhiều dòng log: đếm dòng ra 9 thay vì 3 (bẫy #13) |
| Số lần "No sessions found" | Đếm trong signaling | |
| Thời gian đổ chuông | `RINGING` → `OK` (MVP) | |
| Thời lượng kết nối | `OK_ACK` → `BYE` (MVP) | `_emitBye … duration` là giây, `endCall.duration` là mili giây (bẫy #8) |
| Bên kết thúc | Ai gửi `BYE` | |
| MOS, jitter | Giá trị trong summary `endcall` lúc kết thúc | Tra loại bản ghi theo cột `#tag`, không theo số `#Hn` (bẫy #2) |
| Packet loss | `packetsLost ÷ (packetsLost + packetsReceived)` — hai bộ đếm cộng dồn | KHÔNG dùng `packetLostPercent`: đó là tỉ lệ trong khoảng giữa hai lần đo (bẫy #3) |
| RTT | `transport.currentRttMs` | `transport.rttMs` là giá trị tích luỹ (bẫy #4) |
| Trạng thái ICE | `connected` nếu từng chuyển sang `connected` / `completed`; nếu chưa từng thì trạng thái cuối cùng | Cuộc thành công vẫn kết thúc ở `disconnected` khi cúp máy (bẫy #16) |

Chỉ số "Nếu kịp" (T11 Sprint 1): khoảng trống `PAIR_PING` lớn nhất (**proxy**, report ghi rõ), latency API nội bộ lúc
`INIT_CALL`, số WARN / ERROR theo service, ISP / ASN / quốc gia.

---

## 3. Sprint 2 — Evaluation Metrics (MVP mục 6.5)

Tính bởi **Evaluation Runner** (T9): `--evaluate=<file case> --logs=<thư mục log> --repeat=<n>`.
Code: `domain/evaluation/EvaluationScorer` (chấm), `service/EvaluationService` (chạy), `cli/EvaluateCommand`.

### 3.1. Nguyên tắc chung

| Nguyên tắc | Nguồn |
|---|---|
| Danh sách metric, target, mức "Bắt buộc" / "Báo cáo" | **MVP 6.5**, chép nguyên |
| "Evaluation phải measurable, repeatable và auditable" → file case là tham số, thứ tự chạy tất định, lưu từng lần chạy ra JSON | **MVP 6.5** |
| Runner nhận bộ case từ file để Mentor chạy `held-out` | **MVP 6.1 T9** |
| Mỗi câu hỏi chạy qua **đúng luồng Web UI** (`ChatAnalysisService`), không có đường riêng cho benchmark | Dự án tự quyết: hệ thống được chấm phải là hệ thống người dùng dùng |
| `call_id` của case truyền vào pipeline như khi người dùng ghi Call-ID (Call-ID trong câu hỏi vẫn ưu tiên) | Dự án tự quyết (cách A, 2026-10-06): WebRTC log không mang Call-ID, 5/13 cuộc chỉ có WebRTC log → không truyền thì luôn UNKNOWN |
| **Đơn vị tính là "lần chạy"** (1 câu hỏi × 1 lần lặp), không phải case | Dự án tự quyết. MVP chỉ nói "5 lần × 3 cách hỏi" ở dòng Consistency; mỗi lần chạy là một câu trả lời người dùng nhận được |
| Không đo được → `N/A (lý do)`, không ghi 0% | Mượn quy tắc **MVP 4.3** |
| Không tự đặt nhãn ground truth còn thiếu (category, evidence) | Dự án tự quyết: tự ra đề rồi tự chấm thì con số vô nghĩa |

### 3.2. Từng metric

| Metric · target · mức | MVP nói | Runner tính | Chỗ dự án tự quyết |
|---|---|---|---|
| **Verdict Accuracy** · ≥ 85% · Bắt buộc | "Đúng SUCCESS / FAIL / UNKNOWN trên `held-out` (Mentor chạy), báo cáo kèm số case" | Lần chạy của câu hỏi phân tích, trong case có `expected_verdict`; đúng khi ra report và verdict trùng | Câu phân tích bị phân loại nhầm thành `OUT_OF_SCOPE` tính là **sai** — người dùng không nhận được report |
| **Metric Correctness** · 100% · Bắt buộc | "Số liệu trong report khớp giá trị Metrics Calculator" | So bảng chỉ số của report cuối với bảng dựng thẳng từ Metrics Calculator | Chỉ chứng minh bước ghép phần AI không làm sai bảng chỉ số. Độ đúng của chính Calculator dựa vào kiểm chứng tay Sprint 1 (mục 1). Con số trong chữ AI kiểm ở Unsupported Claim |
| **Security Leakage (input)** · 0% · Bắt buộc | "Sensitive fields đến AI ÷ Sensitive fields detected" | Gom **giá trị nhạy cảm gốc** của case (file + signaling thô) bằng bộ đọc riêng (`SensitiveValues`), đếm giá trị còn nguyên văn trong chuỗi gửi AI. Tỉ lệ = Σ lộ ÷ Σ phát hiện, cộng qua mọi lần chạy | Không dùng regex của Sanitizer, để Sanitizer bỏ sót dạng nào thì phép đo vẫn bắt được. Đo cả khi chưa có key (chuỗi SẼ được gửi). Chỉ lưu số đếm theo loại. Chưa gom IPv6 và giá trị nằm trong câu hỏi |
| **Security Leakage (output)** · 0 · Bắt buộc | "Sensitive values trong report, response, log" | Cùng tập giá trị gốc, đếm trong toàn bộ phản hồi trả người dùng (report JSON, bản render, câu trả lời) | **Application log không quét trong runner** — vẫn dựa vào test S08 và bộ lọc log `%smsg` |
| **Issue Category Accuracy** · ≥ 70% · Báo cáo | "Đúng category, chỉ tính case FAIL và SUCCESS có cờ chất lượng" | Đúng điều kiện đó, thêm: ground truth phải có `expected_issue_category` | Ground truth hiện chưa có nhãn category → N/A |
| **Consistency** · ≥ 95% · Báo cáo | "Mỗi case chạy 5 lần × 3 cách hỏi; verdict và issue category phải giống nhau" | Gom mọi lần chạy của một case (mọi cách hỏi × mọi lần lặp), lấy kết quả số đông (verdict + category). Tỉ lệ = lần trùng số đông ÷ tổng lần. In kèm số case giống hệt mọi lần | **MVP không cho công thức.** Cách "số case giống hệt" chặt hơn, in kèm để đối chiếu. Chỉ xét case có ≥ 2 lần chạy |
| **Template Compliance** · 100% · Báo cáo | "Report đủ mục, đúng thứ tự theo 4.5" | Kiểm bản **Markdown đã render**: tiêu đề, 5 nhãn đầu, 5 mục đúng thứ tự (`TemplateCompliance`) | Kiểm bản render, không kiểm JSON: đó là thứ người dùng thấy |
| **Intent Accuracy** · ≥ 90% · Báo cáo | "Phân loại đúng intent, gồm từ chối đúng câu `OUT_OF_SCOPE`" | Câu có nhãn `expected_intent`; tách số theo nguồn phân loại (AI / từ khoá) | Mẫu case 6.3 **không có chỗ ghi intent** → `expected_intent` là phần mở rộng, chưa chốt |
| **Unsupported Claim Rate** · 0% · Báo cáo | "Mọi con số và evidence ID trong phân tích phải khớp input (kiểm tra tự động)" | "Kiểm tra tự động" = Guardrails G01 (evidence không tồn tại) và G05 (con số không có trong input). Hai dòng: **AI trước Guardrails** và **report cuối** | Tách hai dòng: đầu ra thô của AI có thể sai, nhưng lần sai bị chặn và report lùi về rule. Chi tiết từng vi phạm in ở mục "Vi phạm Guardrails" |
| **Pipeline Success Rate** · ≥ 95% · Báo cáo | "Request trả report hợp lệ (kể cả degraded)" | Chỉ tính câu hỏi **phải ra report** (không đánh dấu `OUT_OF_SCOPE`); thành công = nhận được report đã qua schema | Đúng chữ MVP. Câu phân tích bị từ chối nhầm hay ném lỗi là **thất bại**. (Trước 2026-10-06 tính mọi request không ném lỗi — lượt 1 khi đó ra 63/63, theo cách mới là 54/56) |
| **Latency** · — · Báo cáo | "P50, P95 end-to-end trên máy cá nhân" | Thời gian quanh lời gọi pipeline; percentile theo hạng gần nhất (luôn là một giá trị đo thật) | Chỉ tính lần ra report: câu bị từ chối chạy ~0 ms, kéo số xuống. Không tính đọc file từ đĩa |
| Token · — · Thêm | Không có trong 6.5; MVP 6.1 T11 so "token" giữa các cách dựng context | Số token provider báo (trường `usage` của phản hồi), tách lời gọi phân loại và lời gọi phân tích: trung bình mỗi lời gọi + tổng | Không ước lượng. Lời gọi lỗi / quá giờ không có số. Lời gọi phân loại mà câu trả lời bị loại (INVALID_INTENT) vẫn tính — đã tốn token |
| Fallback (degraded) · — · Thêm | Không có trong 6.5 | Tỉ lệ report phải lùi về rule, kèm lý do | Thêm để đọc đúng các số khác: AI lỗi nhiều thì thực chất đang đo rule |

**Bảng AI vs Rule** (MVP 6.5: các cột Case | Rule Verdict | AI Verdict | Ground Truth). Runner thêm cột **Final**
(kết luận sau Guardrails). Cột AI là đầu ra thô, trước Guardrails. Mỗi ô là phân bố qua mọi lần chạy của case,
ví dụ `FAIL ×14, UNKNOWN ×1`.

### 3.3. Bộ case `dev` (MVP mục 6.3)

`benchmark/dev-cases.yaml`: 18 case, 63 câu hỏi. Độ phủ từng loại ca MVP đòi ghi ở đầu file. Thiếu: **SUCCESS chất
lượng kém** (data không có cuộc nào có nhãn). Biến thể "tên file sai loại" (case-016) là
bản sao y hệt `caller_endcall.log` của `DE7DD314` nhưng đặt tên `caller_webrtc.log`, tạo bằng `scripts/benchmark-variants/make_variants.py`
vào thư mục log, ngoài repo (data mẫu chứa IP thật).

### 3.4. Kết quả đã đo

**Lượt 1 — tập `dev`, 2026-10-06, `gpt-4o-mini`, mỗi câu 1 lần (63 lần chạy), ES có signaling 13/13 Call-ID.**
117 lời gọi API, 207 520 token vào + 9 889 token ra ≈ **0,037 USD**.

| Metric | Kết quả | Target |
|---|---|---|
| Verdict Accuracy | 96,4% (54/56) | ≥ 85% |
| Metric Correctness | 100% (54/54) | 100% |
| Consistency (1 lần × nhiều cách hỏi) | 96,4% | ≥ 95% |
| Template Compliance | 100% | 100% |
| Intent Accuracy | 94,9% (56/59, AI); đường lui từ khoá: 83,1% | ≥ 90% |
| Unsupported Claim (AI trước Guardrails) | 5,6% (3/54, cả 3 là G05) | 0% |
| Pipeline Success Rate | 96,4% (54/56) theo cách tính mới (mục 4.5); cách cũ 63/63 | ≥ 95% |
| Latency | P50 4,0 s · P95 6,8 s | — |

- 2 lần sai verdict đều do câu không dấu bị AI đẩy ra `OUT_OF_SCOPE` ("cuoc goi nay on chu",
  "ai la nguoi cup may truoc") — pipeline không chạy. Rule kết luận đúng ở mọi lần ra report.
- AI trùng rule ở mọi lần trả lời. AI **thấy** kết luận của rule trong context, nên điều này chưa chứng minh AI tự suy
  luận đúng — việc của T11.
- 3 lần G05: 2/3 là câu hỏi "bao lâu". Lượt 1 chưa lưu con số bị chặn; lượt 2 có lưu.

**Lượt 2 — tập `dev`, 2026-10-06, `gpt-4o-mini`, mỗi câu 5 lần (315 lần chạy).** 580 lời gọi API, 1 020 392 token vào +
47 480 token ra ≈ **0,18 USD**. Chạy bằng runner TRƯỚC khi đo leakage và đổi cách tính Pipeline Success (mục 4.1, 4.5);
số Pipeline Success dưới đây tính lại từ file JSON theo cách mới.

| Metric | Kết quả | Target |
|---|---|---|
| Verdict Accuracy | 94,6% (265/280) | ≥ 85% |
| Metric Correctness | 100% (265/265) | 100% |
| Consistency | **94,6%** (265/280); 14/17 case giống hệt mọi lần | ≥ 95% — **không đạt** |
| Template Compliance | 100% | 100% |
| Intent Accuracy | 94,9% (280/295, AI) | ≥ 90% |
| Unsupported Claim — AI trước Guardrails | 4,5% (12/265, đều G05) | 0% |
| Unsupported Claim — report cuối | **0%** (0/253) | 0% |
| Pipeline Success Rate (cách mới) | 94,6% (265/280) | ≥ 95% — **không đạt** |
| Latency | P50 3,7 s · P95 6,4 s · max 9,0 s | — |

**Đọc kết quả:**

- **Mỗi câu hỏi cho cùng một kết quả ở cả 5 lần lặp** (63/63 câu). Consistency và Pipeline Success hụt 95% hoàn toàn
  vì 3 câu bị AI phân loại nhầm thành `OUT_OF_SCOPE` — cả 5 lần: "cuoc goi nay on chu", "ai la nguoi cup may truoc"
  (không dấu, ngắn) và "Vì sao lời mời gọi phải gửi lại nhiều lần?". Đó là lệch giữa các CÁCH HỎI, không phải AI trả lời
  chập chờn giữa các lần chạy. Riêng câu thứ ba ở lượt 1 lại ra `ANALYZE_CALL`: `temperature 0` không bảo đảm tất định
  tuyệt đối giữa hai lượt.
- **Rule và AI kết luận đúng ở mọi lần ra report.** Mọi lần sai verdict đều do câu bị từ chối nhầm.
- **12 lần G05** (chi tiết lấy từ mục "Vi phạm Guardrails" của báo cáo runner, đối chiếu với `--ai-context`):

| Số bị chặn | Lần | Nguồn gốc | Đánh giá |
|---|---|---|---|
| `4.4` | 2 | AI làm tròn MOS 4.42397 / 4.42201 | Vô hại về nghĩa, nhưng phạm quy tắc giữ nguyên số |
| `0.112` | 5 | `D114749E`: mọi chỉ số thời gian là N/A (cuộc gọi không tới INVITE); câu hỏi "thời gian từ lúc gọi đến lúc báo lỗi" → AI tự trừ hai mốc thời gian | Vi phạm thật ("AI không tính số liệu") — chặn đúng |
| `11.942` | 5 | `C8CF631E`: không khớp số nào (đổ chuông 10942 ms, thiết lập 11958 ms) | **AI bịa số** — chặn đúng |

  Cả 12 lần, report lùi về rule: người dùng không thấy số sai. Hướng sửa gốc: AI trích mã chỉ số `{Mxx}` (mục 4.3).

**Lượt kiểm leakage — 2026-10-06, không dùng AI (miễn phí), runner mới.** Security Leakage đầu vào **0/1 401** giá trị
gốc (cộng qua 63 lần chạy), đầu ra **0**. Chưa có số leakage của lượt có AI thật — phần chữ AI viết chỉ được đo ở lượt
có AI.


**Sửa prompt phân loại câu hỏi — 2026-10-06.** Lượt 2 cho thấy AI đẩy nhầm câu hỏi phân tích ngắn / không dấu ra
`OUT_OF_SCOPE`. Sửa `prompts/parse-request.system.txt`: (1) nói rõ người dùng hay gõ không dấu và rất ngắn — đọc như có
dấu, đó không phải lý do xếp `OUT_OF_SCOPE`; (2) thu hẹp `OUT_OF_SCOPE` về "nhờ làm việc khác / chủ đề không liên quan",
mọi câu HỎI về chính cuộc gọi đều trong phạm vi, phân vân thì không chọn `OUT_OF_SCOPE`; (3) thêm khía cạnh bắt máy, gửi
lại lệnh / lời mời gọi, ai cúp máy, kết thúc, thời gian. Prompt không chép câu nào của tập `dev`.

Để không "sửa cho khớp đề", viết TRƯỚC khi sửa một bộ câu kiểm tra mới `benchmark/intent-check.yaml` (16 câu: 8 trong
phạm vi — phần lớn ngắn, không dấu — và 8 ngoài phạm vi), chạy với cả prompt cũ và mới:

| | Prompt cũ | Prompt mới |
|---|---|---|
| Câu trong phạm vi bị đẩy nhầm ra `OUT_OF_SCOPE` | **5/8** | **0/8** |
| Câu ngoài phạm vi bị từ chối đúng | 8/8 | 8/8 |
| Intent Accuracy | 68,8% | 93,8% |

**Lượt 3 — tập `dev`, prompt mới, mỗi câu 1 lần (63 lần chạy), `gpt-4o-mini`.** 119 lời gọi API ≈ **0,04 USD**.
Đây cũng là lượt đầu đo Security Leakage khi có AI thật.

| Metric | Lượt 1 (prompt cũ) | Lượt 3 (prompt mới) | Target |
|---|---|---|---|
| Verdict Accuracy | 96,4% | **100%** (56/56) | ≥ 85% |
| Intent Accuracy | 94,9% | **98,3%** (58/59) | ≥ 90% |
| Consistency (1 lần × nhiều cách hỏi) | 96,4% | **100%** (17/17 case) | ≥ 95% |
| Pipeline Success Rate | 96,4% | **100%** (56/56) | ≥ 95% |
| Security Leakage (input) | — | **0%** (0/1 401) | 0% |
| Security Leakage (output) | — | **0** | 0 |
| Unsupported Claim — AI trước Guardrails | 5,6% | 3,6% (2/56: `4.4` làm tròn MOS, `0.112` tự trừ mốc thời gian) | 0% |
| Unsupported Claim — report cuối | — | 0% | 0% |
| Template Compliance, Metric Correctness | 100% | 100% | 100% |
| Latency | P50 4,0 s · P95 6,8 s | P50 4,1 s · P95 6,9 s | — |

- Cả 7 câu `OUT_OF_SCOPE` thật vẫn bị từ chối: thu hẹp định nghĩa không làm lọt câu ngoài phạm vi.
- Lệch intent duy nhất: "Cuộc gọi này kết thúc ra sao?" — AI xếp `ANALYZE_WITH_FOCUS`, nhãn là `ANALYZE_CALL`. Câu cùng
  kiểu ở bộ kiểm tra ("cuoc goi ket thuc the nao") cũng vậy. Prompt mới liệt kê "kết thúc" là một diễn biến, nên đây là
  chỗ NHÃN mơ hồ, không phải từ chối nhầm; chưa đổi nhãn (đổi nhãn sau khi thấy kết quả là chỉnh đề theo đáp án) — cần
  chốt quy ước rồi mới đổi.

**Lượt 4 — tập `dev`, prompt mới, mỗi câu 5 lần (315 lần chạy), `gpt-4o-mini`.** 595 lời gọi API ≈ **0,20 USD**.
Đây là bộ số chính thức của tập `dev` cho Sprint 2.

| Metric | Lượt 2 (prompt cũ, 5 lần) | Lượt 4 (prompt mới, 5 lần) | Target |
|---|---|---|---|
| Verdict Accuracy | 94,6% | **100%** (280/280) | ≥ 85% |
| Metric Correctness | 100% | **100%** (280/280) | 100% |
| Security Leakage (input) | — | **0%** (0/7 005) | 0% |
| Security Leakage (output) | — | **0** (315 lần chạy) | 0 |
| Issue Category Accuracy | N/A | N/A (ground truth chưa có nhãn category) | ≥ 70% |
| Consistency | 94,6% · 14/17 case | **100%** (280/280) · **17/17 case** giống hệt mọi lần | ≥ 95% |
| Template Compliance | 100% | **100%** | 100% |
| Intent Accuracy | 94,9% | **98,3%** (290/295) | ≥ 90% |
| Unsupported Claim — AI trước Guardrails | 4,5% | **2,9%** (8/280) | 0% |
| Unsupported Claim — report cuối | 0% | **0%** (0/272) | 0% |
| Pipeline Success Rate (cách mới) | 94,6% | **100%** (280/280) | ≥ 95% |
| Latency | P50 3,7 s · P95 6,4 s | P50 3,8 s · **P95 8,4 s** · max 12,3 s | — |

- **Mọi metric có target đều đạt**, trừ Unsupported Claim trước Guardrails (2,9%, target 0%) — nhưng không lần nào tới
  người dùng: cả 8 lần, report lùi về rule.
- Bảng AI vs Rule: ở cả 17 case, rule, AI và kết luận cuối trùng nhau và trùng ground truth ở mọi lần chạy. AI vẫn THẤY
  kết luận rule trong context — việc tách bạch AI có tự suy luận đúng không là T11.
- Lệch intent duy nhất vẫn là "Cuộc gọi này kết thúc ra sao?" (cả 5 lần) — chỗ nhãn mơ hồ đã ghi ở lượt 3.
- 8 lần G05: `0.112` ×5 (AI tự trừ mốc thời gian, cuộc `D114749E`), `4.4` ×1 (làm tròn MOS), `10.942` ×1 (đổi
  10942 ms sang giây) và `11.942` ×1 (số bịa) — hai số sau cùng một câu hỏi của `C8CF631E` ở hai lần lặp khác nhau.
  Vậy **kết luận** tất định qua 5 lần, nhưng **chữ AI viết** thì không: cùng câu hỏi, có lần qua Guardrails, có lần bị chặn.
- P95 tăng từ 6,4 s lên 8,4 s dù số lời gọi tương đương — dao động phía OpenAI, chưa có gì trong code giải thích.

**Quy ước nhãn cho câu mơ hồ — 2026-10-06.** "Cuộc gọi này kết thúc ra sao?" (và "cuoc goi ket thuc the nao" ở bộ kiểm
tra) đọc được hai nghĩa: hỏi KẾT CỤC (`ANALYZE_CALL`) hay hỏi GIAI ĐOẠN kết thúc (`ANALYZE_WITH_FOCUS`). Câu mơ hồ không có
một đáp án đúng duy nhất nên không dùng để chấm phân loại: giữ câu ở dạng chuỗi trần (không nhãn intent) — vẫn tính vào
Verdict Accuracy và Consistency, không tính vào Intent Accuracy. Không viết lại câu (dễ thành thay câu AI làm sai bằng câu
AI làm đúng) và không đổi nhãn theo đáp án của AI. Quy tắc đặt câu để tránh nhãn mơ hồ ghi ở đầu `dev-cases.yaml`.
Nhầm giữa hai intent phân tích không ảnh hưởng người dùng: cả hai đều cho report đầy đủ.

Chấm lại Intent Accuracy theo nhãn mới, từ file JSON đã có (không chạy lại AI):

| | Trước | Sau khi bỏ nhãn câu mơ hồ |
|---|---|---|
| Lượt 3 — `dev`, 1 lần | 98,3% (58/59) | **100%** (58/58) |
| Lượt 4 — `dev`, 5 lần | 98,3% (290/295) | **100%** (290/290) |
| Bộ kiểm tra, prompt cũ | 68,8% (11/16) | 73,3% (11/15) |
| Bộ kiểm tra, prompt mới | 93,8% (15/16) | **100%** (15/15) |


**Quy tắc 4b cho câu hỏi về khoảng thời gian — 2026-10-06.** Thêm vào `prompts/analyze-call.system.txt`: có chỉ số thì
chép đúng giá trị và đơn vị; chỉ số N/A thì nói không có số đo, nêu lý do, chỉ ra evidence bằng ID — không tự trừ mốc
thời gian, không chép mốc giờ. Không nới G05 (MVP mục 3.2, 3.3, 6.5 nghiêng về khớp nguyên văn).

Kiểm riêng 2 câu từng bị G05 chặn, mỗi câu 5 lần (20 lời gọi API ≈ 0,007 USD):

| Câu | Trước (lượt 4) | Sau quy tắc 4b |
|---|---|---|
| `D114749E` "thoi gian tu luc goi den luc bao loi la bao lau" | 5/5 lần bị chặn (`0.112`, tự trừ mốc thời gian) | **0/5** |
| `C8CF631E` "mat bao lau thi ben kia nghe may" | 2/5 (`10.942`, `11.942`) | 1/5 (`11.42` — số không khớp chỉ số nào) |

Kiểu "tự tính" hết hẳn. Kiểu "viết sai số" còn, hiếm và không đoán trước: prompt không chặn được hoàn toàn — G05 vẫn chặn,
report lùi về rule. Hướng sửa tận gốc vẫn là AI trích mã chỉ số `{Mxx}` (Sprint 3 T1).

**Lượt 5 — hồi quy trên tập `dev` sau quy tắc 4b, mỗi câu 1 lần (63 lần chạy).** 119 lời gọi API ≈ 0,04 USD.
Verdict 100% (56/56), Intent 100% (58/58), Consistency 100%, Pipeline Success 100%, Template / Metric Correctness 100%,
Security Leakage 0/1 401 · 0, **Unsupported Claim trước Guardrails 0/56**, 0 fallback; latency P50 3,8 s · P95 8,2 s.
Không câu nào bị hỏng. Chưa có số 5 lần cho phiên bản này — chạy khi chốt phiên bản.

---

## 4. Các điểm chưa chốt trong cách đo — phân tích và hướng xử lý

| # | Điểm | Mức độ | Hướng | Cần xác nhận? | Trạng thái |
|---|---|---|---|---|---|
| 4.1 | Security Leakage khi chạy `held-out` | **Cao** — metric bắt buộc, điều kiện đạt tối thiểu (mục 8.1) | Đối chiếu giá trị gốc trong runner | Không | **Đã làm** 2026-10-06 |
| 4.2 | Công thức Consistency, đơn vị "lần chạy" | Trung bình | Giữ công thức A, in kèm B | **Có** | Chưa chốt |
| 4.3 | Metric Correctness gần như luôn đúng | Trung bình | Ghi rõ ngay; đề xuất AI trích mã chỉ số `{Mxx}` ở Sprint 3 | Không | Đã ghi rõ |
| 4.4 | Format case + cách A | **Cao** — runner phải đọc được `held-out` | Làm rõ định dạng case và ý nghĩa `expected_evidence` | **Có** | Chưa chốt |
| 4.5 | Pipeline Success Rate tính câu từ chối là thành công | Thấp | Chỉ tính câu phải ra report | Không | **Đã làm** 2026-10-06 |

### 4.1. Security Leakage khi Mentor chạy `held-out`

**Vấn đề.** Hai metric leakage là **bắt buộc**; mục 8.1 ghi "Security Leakage = 0" là điều kiện đạt tối thiểu. Trước
đây chúng chỉ được đo bằng test S01-S08 (fixture tự viết) và `scripts/verify-sanitizer` (data mẫu), runner in `N/A`.
Khi Mentor chạy `held-out` — cuộc gọi mới, có thể có dạng dữ liệu nhạy cảm chưa gặp — metric bắt buộc này không có số,
đúng trên tập dữ liệu mình chưa từng kiểm.

**Các hướng đã cân nhắc:**

| Hướng | Ưu | Nhược |
|---|---|---|
| a. Quét report bằng regex của Sanitizer | Dễ | **Không độc lập**: Sanitizer bỏ sót mẫu nào thì phép quét cũng bỏ sót đúng mẫu đó |
| **b. Đối chiếu giá trị gốc** (đã chọn) | Đúng công thức MVP ("đến AI ÷ detected"); kiểm theo GIÁ TRỊ chứ không theo mẫu nên độc lập hơn; là cách `verify_sanitized.py` đã làm, chuyển vào runner | Mẫu số vẫn phụ thuộc bộ đọc nhận ra giá trị gốc; chưa gom IPv6 |

**Đã làm:** `domain/evaluation/SensitiveValues` gom giá trị gốc từ trường signaling (appUserId, csid, callSessionId,
service), cột end call log, cặp `deviceId=` / `csid=`, `a=ice-pwd`, `u/p=`, `Cand[]`, fingerprint, IPv4 (trừ 0.0.0.0,
loopback, broadcast). `EvaluationService` đối chiếu với chuỗi gửi AI (câu hỏi + context JSON) và toàn bộ phản hồi.
Kết quả chỉ lưu số đếm theo loại.

**Còn lại:** IPv6; giá trị nhạy cảm nằm trong câu hỏi người dùng; application log.

### 4.2. Công thức Consistency và đơn vị "lần chạy"

**Vấn đề.** MVP chỉ ghi "mỗi case chạy 5 lần × 3 cách hỏi; verdict và category phải giống nhau, ≥ 95%" — không nói
phần trăm tính trên cái gì. Ví dụ 17 case × 20 lần chạy, 3 case mỗi case lệch đúng 1 lần:

| Công thức | Cách tính | Ví dụ |
|---|---|---|
| **A. Theo lần chạy** (đang dùng) | Lần trùng kết quả số đông ÷ tổng lần | 337/340 = **99,1%** |
| B. Theo case | Case giống hệt mọi lần ÷ số case | 14/17 = **82,4%** — không đạt |
| C. Theo cặp | Tỉ lệ cặp lần chạy cùng kết quả | ~98% |

Cùng câu hỏi với Verdict Accuracy: tính theo lần chạy (lần sai nào cũng bị trừ) hay theo case (lấy số đông — che mất
lần chạy chập chờn)?

**Hướng:** giữ A làm số chính (cách đọc tự nhiên của "x% giống nhau"), in B bên cạnh (đã có: "n/m case giống hệt").
Cần xác nhận. File JSON lưu từng lần chạy, nên đổi công thức thì **tính lại từ JSON, không tốn API**.

### 4.3. Metric Correctness gần như luôn đúng

**Vấn đề.** Runner so bảng chỉ số của report cuối với bảng dựng thẳng từ Calculator. Theo kiến trúc, bảng này do code
dựng, AI không chạm được — nên phép so gần như luôn 100%: nó chặn lỗi hồi quy, không đo rủi ro thật.

Rủi ro thật là **con số trong chữ AI viết**. G05 chỉ kiểm "con số có trong input không", không kiểm con số gắn đúng chỉ
số và đúng bên: AI viết "MOS bên gọi 4.42201" trong khi đó là MOS bên nhận — G05 vẫn cho qua. Còn chính Calculator chỉ
được kiểm bằng tính tay Sprint 1 (9 cuộc); trên `held-out` không có số tính tay.

| Hướng | Khi nào |
|---|---|
| a. Giữ, ghi rõ trong báo cáo là phép kiểm gì | **Ngay** (đã ghi ở mục 3.2) |
| b. Dò tên chỉ số / bên đứng gần con số trong chữ AI | Không khuyến khích — dễ báo nhầm với tiếng Việt |
| **c. AI không viết số, chỉ trích mã chỉ số** — AI viết "MOS bên gọi là {M07}", code thay bằng giá trị thật, như cách AI đang trích `EV05` | **Sprint 3 T1.** Con số trong chữ đúng theo cấu trúc; G05 gần như hết việc; giải quyết luôn 3 lần G05 ở lượt 1 |

### 4.4. Format case và cách A

| Câu hỏi | Rủi ro nếu đoán sai | Đang làm tạm |
|---|---|---|
| Runner tìm file ở đâu (mẫu ghi tên trần) | Bố cục khác → "không có thư mục…" | Tìm thư mục trùng `call_id` ở mọi cấp; lỗi nêu tên, sửa được bằng `--logs` |
| Ghi intent đúng ở đâu | `held-out` không có nhãn → Intent Accuracy N/A trên `held-out`; tên trường khác → runner bỏ qua nhãn | `{text, expected_intent}` tuỳ chọn |
| Nhãn category / evidence | Issue Category Accuracy N/A | Để `null` |
| **`expected_evidence: [EV002, EV005]` nghĩa là gì?** | Mã `EV002` do HỆ THỐNG tự sinh — người viết đáp án không biết trước hệ thống đánh số thế nào, nên so mã với mã vô nghĩa. Có thể ý định là nêu dòng log / sự kiện phải có trong evidence | Chưa dùng trường này |
| Cách A (runner truyền `call_id`) | Nếu yêu cầu là chỉ dùng câu hỏi + file → số đo hiện tại thuận lợi hơn thực tế | Đã làm A, ghi rõ ở mục 3.1 |

**Hướng:** xác nhận các điểm trên, ý nghĩa của `expected_evidence` và cách A trước khi Mentor chạy `held-out`.

### 4.5. Pipeline Success Rate tính câu từ chối là thành công

**Vấn đề.** MVP: "Request trả **report** hợp lệ (kể cả degraded)". Câu `OUT_OF_SCOPE` nhận câu từ chối, không phải report.

| Cách | Mẫu số | Câu phân tích bị từ chối nhầm |
|---|---|---|
| a. Trước đây | Mọi request | Thành công (không ném lỗi) |
| **b. Đúng chữ MVP** (đã chọn) | Chỉ câu phải ra report | **Thất bại** — người dùng không nhận được report |
| c. Tính câu từ chối là thất bại | Mọi request | Phạt hành vi đúng — sai |

**Đã làm** 2026-10-06. Lượt 1 theo cách mới: 54/56 thay vì 63/63.
