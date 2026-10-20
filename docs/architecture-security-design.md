# Architecture & Security Design — AI Call Analysis Assistant

| | |
|---|---|
| Người phụ trách | Chủ repo (`hason0510`) |
| Cập nhật lần cuối | 2026-10-19 (cuối Sprint 3, sau góp ý mentor, rà soát theo MVP và T10) |
| Thay cho | `docs/architecture-v1.md` (kiến trúc Sprint 1, chưa có AI và Web UI) |
| Chi tiết từng quyết định | `docs/design-decisions.md` (49 mục) — tài liệu này trỏ tới số mục, không chép lại |
| Bẫy dữ liệu | `docs/bay-du-lieu.md` — các tài liệu trích theo số hiệu ("bẫy #13") |

Tài liệu mô tả hệ thống ở trạng thái hiện tại: kiến trúc, luồng dữ liệu, phần nào code làm và phần nào AI làm, cách
xử lý dữ liệu nhạy cảm, taxonomy, danh mục chỉ số, cách dùng prompt, API và schema của report. Cách dựng và chạy:
`docs/runbook.md`.

---

## 1. Hệ thống làm gì

Người dùng hỏi bằng tiếng Việt và đính kèm log của một cuộc gọi VoIP/WebRTC. Hệ thống trả về report theo mẫu cố định
(MVP mục 4.5): kết luận `SUCCESS` / `FAIL` / `UNKNOWN`, cờ chất lượng, độ tin cậy kèm lý do, evidence trỏ về dòng log
gốc, bảng chỉ số, nguyên nhân, đề xuất và giới hạn dữ liệu.

Ba nguyên tắc quyết định phần lớn thiết kế:

1. **Phần nào tính được bằng code thì không giao cho AI** (MVP 3.2). Chỉ số, kết luận, evidence, độ tin cậy và bố cục
   report đều do code. AI chỉ hiểu câu hỏi và viết phần giải thích.
2. **Không có căn cứ thì không kết luận.** Thiếu dữ liệu thì ra `UNKNOWN` và nói rõ thiếu gì; chỉ số đo không được thì
   ghi `N/A` kèm lý do, không bao giờ ghi 0.
3. **Không gửi dữ liệu nhạy cảm gốc ra ngoài.** Mọi thứ đi tới AI, tới người dùng và vào log đều qua một Sanitizer.

---

## 2. Kiến trúc

### 2.1. Phân tầng

Theo `Controller → Service → Domain → Infrastructure`, triển khai kiểu Hexagonal (Ports & Adapters):

```text
io.hason.callanalysis
├── controller/       ADAPTER VÀO — Chat API (POST /api/analyze), chỉ bật ở profile "web"
├── cli/              ADAPTER VÀO — các lệnh dòng lệnh (--analyze, --evaluate, --batch-analyze, --import-signaling…)
├── service/          ĐIỀU PHỐI — ChatAnalysisService, AnalyzeCallService, AiVerdictService,
│   │                 RequestParserService, CallLogNormalizationService, EvaluationService, BatchAnalysisService
│   └── port/         CỔNG RA — SignalingSource, AiAnalyzer, IntentClassifier, ApplicationLogTap,
│                     RequestRecordSink, CallCatalog, BatchResultSink
├── domain/           LÕI — không Spring, không I/O
│   ├── parse/  signaling/  timeline/  metrics/  rule/  confidence/  evidence/
│   ├── ai/  guardrail/  request/  report/  validation/  security/  evaluation/  batch/  taxonomy/
└── infrastructure/   ADAPTER RA — es/ (Elasticsearch), ai/ (OpenAI), file/, logging/, security/, report/
```

**Luật bắt buộc:** `domain` không import `infrastructure`, không có annotation Spring, không đọc file, không gọi mạng.

Luật này cho ba thứ đo được: pipeline tất định (cùng input ra cùng output — điều kiện của Consistency ≥ 95%); toàn
bộ logic test được không cần Elasticsearch hay AI (594 unit test, 2026-10-20); đổi nhà cung cấp AI chỉ cần viết adapter mới cho
hai cổng `AiAnalyzer` và `IntentClassifier` (MVP 6.6).

**Adapter chỉ làm việc cơ học.** `ElasticsearchSignalingSource` map document thành `RawSignalingRecord` rồi dừng; parse
timestamp, suy ra leg, gán loại sự kiện là việc của domain (design-decisions mục 4).

### 2.2. Ba nguồn dữ liệu

| Nguồn | Vào hệ thống bằng | Thời gian | Ghi chú |
|---|---|---|---|
| Signaling | Elasticsearch local, index `signaling-events`, truy vấn theo Call-ID | ISO-8601 UTC, nano giây | Không có trường `leg`, không có trường chữ |
| End call log (`*_endcall.log`) | Người dùng đính kèm | epoch millis, đồng hồ client | TSV tự mang schema ở các dòng `#H` đầu file |
| WebRTC log (`*_webrtc.log`) | Người dùng đính kèm | `[giây:mili]` **tương đối** | Hai định dạng iOS / Android; có dòng nối tiếp |

WebRTC log không có giờ tuyệt đối, nên timeline tách hai phần: track chính (signaling + end call, giờ tuyệt đối) và
mỗi file WebRTC một track tương đối. Không trộn hai loại để khỏi tạo thứ tự giả (design-decisions mục 6, 19).

Index `signaling-events` dùng `dynamic: strict` và `_id` tất định `callId:ordinal`: nạp lại không sinh bản trùng, trường
lạ bị báo lỗi ngay (mục 3, 7).

---

## 3. Luồng dữ liệu của một request

```text
 Người dùng (Web UI / curl / --evaluate)
        │  câu hỏi + file log
        ▼
 [1] AnalyzeController ── file > 20 MB: không đọc nội dung, đánh dấu để loại
        ▼
 [2] RequestParserService ── GarbledText (câu hỏi hỏng mã hoá)
        │                    Sanitizer (chế độ câu hỏi) ──► AI #1: phân loại intent ──► kiểm đầu ra
        │                    AI lỗi → bộ từ khoá dự phòng
        ├── OUT_OF_SCOPE ──► câu từ chối cố định, dừng
        ▼
 [3] FileValidator ── nhận loại file theo NỘI DUNG, kiểm Call-ID, loại file hỏng / quá cỡ (nêu tên);
                      WebRTC log đi cùng lượt tải lên chỉ có log của cuộc khác cũng bị loại. File đọc UTF-8,
                      có BOM thì theo BOM (UTF-16) — design-decisions mục 49
        ▼
 [4] Parse → CanonicalEvent ◄── Elasticsearch: signaling theo Call-ID (lỗi ES → ghi Giới hạn dữ liệu, chạy tiếp)
        ▼
 [5] TimelineBuilder ── gán leg, dedupe mức file, sắp xếp, đo lệch đồng hồ (chỉ báo, không sửa giờ)
        ▼
 [6] MetricsCalculator · SignalExtractor → RuleVerdictEngine → ConfidencePolicy · EvidenceEngine
        ▼
 [7] AiContextBuilder ── Sanitizer (nhớ định danh trước, rồi thay) ──► context có kiểu
        ▼
 [8] AI #2: phân tích ──► Sanitizer đầu ra ──► Guardrails G01-G05 ──► đối chiếu với rule
        │                                    REJECTED / lỗi AI → lùi về rule (degraded)
        ▼
 [9] ReportBuilder ── Sanitizer cho mọi chữ tự do của report ── kiểm JSON Schema ── ReportRenderer
        ▼
 Report (JSON + Markdown) trả người dùng; log JSON đã làm sạch ghi ra console
```

Thứ tự ở bước 6 có chủ đích: rule xét media **trước** khi được phép kết luận `SUCCESS`. Cuộc `2D9057AA` có signaling
hoàn hảo nhưng ICE failed và 0 byte audio; rule chỉ nhìn signaling sẽ kết luận sai (mục 16).

Hết giờ chờ: AI 30 s mỗi lần thử, ngân sách 45 s cho cả lời gọi kể cả thử lại; Elasticsearch kết nối 5 s, đọc 30 s;
trình duyệt chờ tối đa 150 s (lớn hơn tổng ngân sách các bước phía server: 45 + 5 + 30 + 45 = 125 s).

---

## 4. Phân vai code và AI

| Phần | Ai làm | Ghi chú |
|---|---|---|
| Parse log, dựng timeline | Code | |
| Chỉ số cuộc gọi | Code | AI chỉ được nhắc chỉ số bằng mã (`{PACKET_LOSS_CALLER}`), code điền giá trị (mục 32) |
| Kết luận và issue category | Code (rule) quyết định | AI đề xuất; lệch rule thì kết luận cuối là `UNKNOWN` kèm cờ "cần kiểm tra" (mục 23) |
| Evidence | Code chọn, kèm cờ "căn cứ của rule" | AI chỉ được trích ID có trong context (G01) |
| Độ tin cậy | Code (`ConfidencePolicy`) | `confidenceLevel` AI trả về chỉ để tham khảo, không dùng (mục 35) |
| Hiểu câu hỏi (intent, trọng tâm) | AI | Call-ID lấy bằng regex; AI lỗi thì lùi về bộ từ khoá (mục 26) |
| Tóm tắt, phân tích | AI | Report từ rule vẫn có "Vì sao kết luận" và "Căn cứ của rule" (mục 37) |
| Đề xuất | Bộ chuẩn của code + AI bổ sung tối đa 3 câu | Bộ chuẩn luôn giữ (mục 37) |
| Bố cục report | Code | Mẫu MVP 4.5; AI chỉ điền trường (mục 29) |
| Câu từ chối ngoài phạm vi | Code | Câu cố định, giống nhau mọi lần |

---

## 5. Bảo mật dữ liệu

### 5.1. Phân loại và chính sách

Danh sách dữ liệu nhạy cảm nằm ở `src/main/resources/sensitive-data-inventory.yaml` (19 mục). Sanitizer nạp thẳng
file này, nên đổi chính sách không cần sửa code.

| Chính sách | Thay bằng | Áp cho |
|---|---|---|
| `DROP` | `[REDACTED]` | Bí mật: token, JWT, Authorization, API key, mật khẩu, credential TURN, `ice-pwd` |
| `MASK` | `[LABEL_REDACTED]` | IP client và IP mạng nội bộ, ICE candidate, địa chỉ relay, DTLS fingerprint, URL nội dung người dùng, số điện thoại, email |
| `PSEUDONYMIZE` | `LABEL_a81f2c` (HMAC, 6 ký tự hex) | `appUserId`, `deviceId`, session ID: cùng giá trị cùng mã, vẫn so được hai lần xuất hiện |
| `MINIMIZE` | `LABEL_a81f` (4 ký tự hex) | Chi tiết hạ tầng: TURN endpoint, tên service, pod |
| `ALLOW` | giữ nguyên | Chỉ số chất lượng, ISP / ASN, lý do lỗi hiển thị cho người dùng, `requestId` |

Thứ tự các mục là thứ tự áp dụng: bí mật trước; DTLS fingerprint trước IP (68 chuỗi trông như IPv6 trong data mẫu là
mảnh fingerprint); chuỗi có cấu trúc trước IP chung; định danh cuối cùng.

Khoá HMAC đọc từ `CALL_ANALYSIS_PSEUDONYM_KEY` trong `.env`, không nằm trong repo. Thiếu khoá thì mỗi lần chạy dùng khoá
ngẫu nhiên. Key OpenAI cũng chỉ nằm trong `.env`.

### 5.2. Năm chỗ làm sạch

Một Sanitizer duy nhất cho cả ứng dụng (mục 28), đặt ở mọi chỗ dữ liệu rời khỏi hệ thống:

| # | Chỗ | Cách |
|---|---|---|
| 1 | Câu hỏi, trước khi gửi AI phân loại | Chế độ câu hỏi: bí mật `tên=giá trị` chỉ che đúng giá trị, giữ phần yêu cầu viết sau (mục 34 và `bao-cao-bao-mat.md` mục 4.1) |
| 2 | Context gửi AI phân tích | Nhớ mọi định danh có cấu trúc của cuộc gọi (thuộc tính event) trước, rồi thay ở mọi chuỗi — định danh lọt vào mô tả evidence vẫn bị thay |
| 3 | Câu trả lời của AI | Làm sạch trước Guardrails và trước report: model có thể bịa hoặc lặp lại một chuỗi nhạy cảm (ca S07) |
| 4 | Report trả người dùng | Mọi chữ tự do của report (tóm tắt, evidence, lý do, đề xuất) đi qua Sanitizer một lần nữa — kể cả trích dẫn `tên file:dòng`, vì tên file do người dùng đặt (2026-10-19) |
| 5 | Application log | Log chữ qua converter `%smsg` / `%sex` / `%skvp`; log JSON (profile web) qua `SanitizedJsonLogFormatter` |

**Cổng fail-closed** (`OutboundGate`, mục 44): ngay trước mỗi lời gọi AI, quét lại đúng chuỗi sắp gửi bằng bộ nhận diện
độc lập với Sanitizer (bộ của phép đo Security Leakage). Còn IP, bí mật (JWT, Bearer, API key, `token=…`), SĐT di động
Việt Nam hay email thì không gửi, report lùi về rule với `SENSITIVE_DATA_BLOCKED`. Cổng chỉ nhận ra các loại đó: định
danh gốc, mật khẩu ICE, hostname nội bộ mà Sanitizer bỏ sót thì không chặn.

Signaling nạp vào Elasticsearch không lấy trường `message` (chữ tự do mang User-ID, Call-ID gốc) và `clientIp` của bản
export thô; từ `message` chỉ giữ cờ có kiểu `noSessionsFound` (2026-10-19).

Dòng log của mỗi lời gọi AI chỉ gồm 7 trường MVP 6.2 cho phép (`request_id`, `call_id` đã mã giả, `model`,
`latency_ms`, `result_status`, `token_usage`, `fallback_reason`); không ghi nội dung request / response (mục 36).

### 5.3. Đã kiểm thế nào

Chi tiết: `docs/bao-cao-bao-mat.md`. Tóm tắt (lượt cuối 2026-10-14 và lượt đo lại 2026-10-15, mỗi lượt `dev` ×5):

- S01-S08: 17 test tự động mang tên ca, đều qua (`mvn test` 2026-10-19).
- Leakage đầu vào AI: 0 / 8 255 giá trị gốc qua 345 lần chạy với AI thật. Giá trị gốc lấy bằng bộ đọc riêng, không dùng
  regex của Sanitizer, nên Sanitizer bỏ sót dạng nào thì phép đo vẫn bắt được.
- Leakage đầu ra: 0 trong report và phản hồi; 0 trong application log của từng lần chạy.
- Kiểm độc lập bằng script Python (`scripts/verify-sanitizer`): 0 giá trị sót trên 42 file đã làm sạch.

### 5.4. Bề mặt hệ thống

- Elasticsearch và Kibana chỉ lắng nghe trên `127.0.0.1`, không bật xác thực: chỉ dùng trên máy cá nhân.
- File quá 20 MB không được đọc nội dung; file hỏng hoặc sai loại bị loại riêng và nêu tên, không làm hỏng cả request.
- Lỗi không lường trước trả câu thông báo chung, không trả stack trace hay nội dung file.
- Chữ từ server (kể cả chữ AI viết) hiển thị trên Web UI bằng `textContent`, không bao giờ `innerHTML`.

**Giới hạn:** signaling nạp vào Elasticsearch ở dạng gốc (có `appUserId`). Dự án hiểu "dữ liệu ghi vào ES local" của
MVP 6.2 là kết quả phân tích; lý do: Security Evaluation Report mục 4.3.

---

## 6. Guardrails, fallback và độ tin cậy

### 6.1. Guardrails cho câu trả lời của AI

| Mã | Kiểm | Vi phạm thì |
|---|---|---|
| G01 | Evidence ID AI trích có tồn tại trong context không | REJECTED |
| G02 | Đủ trường, đúng định dạng (FAIL phải có category, kết luận phải trích evidence) | REJECTED |
| G03 | `verdict` / `issueCategory` nằm trong taxonomy (viết thường cũng là sai, không sửa hộ) | REJECTED |
| G04 | Kết luận AI có khớp rule không | FLAGGED: khác verdict → `UNKNOWN`; cùng verdict khác category → giữ category của rule |
| G05 | Mọi con số AI viết có nguyên văn trong context không; mã chỉ số có tồn tại không | REJECTED |

G05 so số nguyên văn: đổi đơn vị (1505 ms → 1,5 giây) hay làm tròn cũng tính là số không có trong input (mục 25).

### 6.2. Fallback

AI lỗi hoặc bị Guardrails loại thì report dựng hoàn toàn từ rule, đánh dấu `degraded`, ghi mã lý do. Web UI hiện
banner nêu lý do bằng lời.

| Mã | Khi nào | Thử lại? |
|---|---|---|
| `TIMEOUT` | AI quá giờ | Có |
| `PROVIDER_ERROR` | Mất mạng, provider lỗi 5xx | Có |
| `RATE_LIMITED` / `QUOTA_EXCEEDED` | HTTP 429: vượt tốc độ / hết hạn mức | Không |
| `AUTH_FAILED` | HTTP 401 / 403: khoá sai | Không |
| `INPUT_TOO_LARGE` | HTTP 400 `context_length_exceeded`: vượt cửa sổ ngữ cảnh | Không |
| `REQUEST_REJECTED` | HTTP 4xx khác | Không |
| `INVALID_RESPONSE`, `REFUSED` | AI trả sai định dạng, thiếu trường bắt buộc của schema, bị cắt (trần 2 000 token ra), hoặc từ chối | Không |
| `SENSITIVE_DATA_BLOCKED` | Cổng fail-closed thấy chuỗi sắp gửi còn dữ liệu nhạy cảm | Không |
| `NOT_CONFIGURED` | Chưa có khoá | Không |
| `GUARDRAIL_REJECTED`, `REPORT_SCHEMA_INVALID`, `UNEXPECTED_ERROR` | Câu trả lời bị Guardrails loại, report sai schema, lỗi không lường trước | Không |

**Thử lại có kiểm soát** (`RetryPolicy`, mục 38): chỉ hai mã đầu, tối đa 2 lần gọi (thử lại một lần), chờ 500 ms, ngân sách 45 s cho cả lời
gọi — lần sau chỉ dùng phần còn lại. Mỗi lần thử hỏng ghi thêm một dòng log `result_status=RETRY`.

### 6.3. Độ tin cậy

`ConfidencePolicy` là nơi duy nhất quyết định mức, theo bảng MVP 7.2 (mục 35):

| Điều kiện | Mức |
|---|---|
| Bằng chứng mạnh + AI khớp rule + đủ file | HIGH |
| Bằng chứng chưa trọn, hoặc thiếu một phần file, hoặc không có AI để đối chiếu | MEDIUM |
| Bằng chứng yếu, hoặc AI lệch rule | LOW |

Mỗi nhánh của rule khai báo bằng chứng mạnh / chưa trọn / yếu kèm lý do; report in lý do cạnh mức, ví dụ *"MEDIUM —
thiếu log client của caller; bằng chứng rõ: ICE chuyển sang failed, không có byte audio nào"*.

---

## 7. Taxonomy

`src/main/resources/taxonomy.yaml` — 6 issue category theo MVP 4.2, mỗi category có định nghĩa, triệu chứng, điểm mơ hồ
và trạng thái kiểm chứng trên data có nhãn:

| Category | Trạng thái | Phân nhánh trong code |
|---|---|---|
| `SIGNALING_FAILURE` | VALIDATED | `SignalingCause`: bị server từ chối, hết giờ chờ candidate, không có INVITE, `FAIL_HARD`, `CANCEL`, không đạt `OK_ACK_OK` |
| `ICE_FAILURE` | VALIDATED | Mạnh khi ICE chuyển sang failed; chưa trọn khi chỉ có 0 byte audio hoặc cờ `hasMediaFail` |
| `TURN_FAILURE` | VALIDATED | `TurnFailure`: không tạo được socket, lỗi gửi ngay trên máy, gửi mà không ai trả lời, có phản hồi mà không cấp phát |
| `NETWORK_PACKET_LOSS` | UNVALIDATED | Mất gói CẢ CUỘC từ 5 % theo ngưỡng mentor (mục 46); không có cuộc chất lượng kém nào có nhãn để kiểm |
| `NETWORK_DELAY_JITTER` | UNVALIDATED | RTT hoặc jitter trung bình > 500 ms / 30 ms trên luồng > 500 gói, ngưỡng Microsoft Teams CQD (mục 41); xét sau mất gói |
| `UNKNOWN` | UNVALIDATED | Thiếu signaling, thiếu log client (kể cả khi mọi log đính kèm thuộc cuộc gọi khác — report nói rõ nguyên nhân), không thấy `BYE` |

Category `UNVALIDATED` luôn kèm một dòng ở "Giới hạn dữ liệu" của report. Taxonomy do `ReportBuilder` đọc và được gửi
cho AI; điều kiện của rule nằm trong code, không trong file (mục 17).

Mỗi category có bộ đề xuất chuẩn, chọn theo nhánh con nếu có (`ReportBuilder.standardSuggestions`).

---

## 8. Danh mục chỉ số

Do `MetricsCalculator` tính. Thiếu dữ liệu → `N/A` kèm lý do cụ thể, không bao giờ 0 (MVP 4.3).

| Chỉ số | Nguồn | Cách tính |
|---|---|---|
| Thời gian thiết lập | Signaling | `INIT_CALL` → `OK_ACK` |
| Thời gian với tới callee | Signaling | `INVITE` → `TRYING` |
| Số lần gửi lại INVITE / BYE | Signaling | Đếm số **lần gửi**, gom các dòng cách nhau dưới 250 ms thành một lần (đo trên 451 khoảng cách) |
| Thời gian đổ chuông | Signaling | `RINGING` → `OK` |
| Thời lượng kết nối | Signaling | `OK_ACK` → `BYE` |
| Bên kết thúc | Signaling | Bên gửi `BYE` đầu tiên |
| Số lần "No sessions found" | Signaling | Bản export THÔ: lúc import, `message` chứa "No sessions found" thành cờ `noSessionsFound` (chữ bị bỏ, không vào ES); đếm theo lần gửi như INVITE. Bản export gọn không có `message` → `N/A`. Không phải tín hiệu lỗi: 3/3 cuộc có dòng này, callee vẫn trả `TRYING` (đánh thức qua push) |
| MOS (mỗi bên) | End call | Bản ghi summary `endcall`, không có thì mẫu stats cuối |
| Packet loss (mỗi bên) | End call | `packetsLost ÷ (packetsLost + packetsReceived)` của bộ đếm cộng dồn — **không** dùng `packetLostPercent` (là tỉ lệ trong khoảng giữa hai lần đo) |
| RTT (mỗi bên) | End call | `transport.currentRttMs` — **không** dùng `transport.rttMs` (là giá trị tích luỹ) |
| Jitter (mỗi bên) | End call | `audio.jitter` (giây trong log), đổi sang ms |
| Trạng thái ICE đạt được (mỗi bên) | WebRTC | `connected` nếu ICE từng đạt `connected` / `completed`, không thì trạng thái cuối — không báo trạng thái cuối cho cuộc đã kết nối, vì cuộc thành công vẫn kết thúc ở `disconnected` |
| Khoảng trống PAIR_PING lớn nhất (mỗi bên) | Signaling | **Proxy**: gợi ý đường signaling có vấn đề, không chứng minh media có vấn đề |
| Latency API nội bộ lúc INIT_CALL | Signaling | Trường `latencyMs` của sự kiện `INIT_CALL`, in đủ các mẫu |
| Số WARN / ERROR theo service | Signaling | Chỉ để tham khảo: WARN có cả ở cuộc thành công, không dùng làm tín hiệu kết luận |
| ISP / ASN / quốc gia (mỗi bên) | Signaling | |

MOS, packet loss, RTT, jitter ghi `N/A` khi leg chưa từng nhận gói audio: app ghi 0 vào các trường này dù chưa đo.
Bẫy dữ liệu đã gặp khi tính chỉ số: [`bay-du-lieu.md`](bay-du-lieu.md).

---

## 9. Chiến lược prompt

Hai lời gọi AI mỗi request, model `gpt-4o-mini`, Structured Outputs `strict: true`, temperature 0 (mục 22):

| Lời gọi | System prompt | Schema đầu ra |
|---|---|---|
| Phân loại câu hỏi | `prompts/parse-request.system.txt` | `schema/request-intent.schema.json`: `ANALYZE_CALL` / `ANALYZE_WITH_FOCUS` (kèm trọng tâm) / `OUT_OF_SCOPE` |
| Phân tích cuộc gọi | `prompts/analyze-call.system.txt` | `schema/ai-analysis.schema.json`: verdict, cờ, category, summary, evidenceIds, analysis, suggestions (và `confidenceLevel` — chỉ tham khảo, không dùng) |

Các quyết định chính của prompt phân tích:

- **AI không đọc log gốc.** Nó nhận context có kiểu do `AiContextBuilder` dựng, đã làm sạch: kết luận của rule kèm bộ
  đề xuất chuẩn, evidence có ID và cờ `ruleBasis`, bảng chỉ số có mã, danh sách log có mặt, giới hạn dữ liệu, taxonomy
  (mục 24).
- **Chỉ số nhắc bằng mã, không bằng số.** AI viết `{PACKET_LOSS_CALLER}`, code điền "1.83 % [Packet loss (caller)]".
  Trước đó AI từng gọi một mẫu stats 11.3208% là "mất gói của caller" trong khi cả cuộc là 1.83%. Không dùng mã đánh số
  `{M20}`: AI lẫn với evidence `EV20` (mục 32).
- **Evidence là căn cứ, không phải danh sách.** Thường 2-6 dòng; đồng ý với rule thì trích các dòng `ruleBasis`
  (mục 33).
- **Số ngoài bảng chỉ số chép nguyên văn**, không đổi đơn vị, không tự tính khoảng thời gian từ hai mốc.
- **Đề xuất chỉ là phần bổ sung** cho bộ chuẩn, 0-3 câu, không khuyên hướng mà evidence không ủng hộ (mục 37).
- Câu hỏi có trọng tâm thì `analysis` trả lời trọng tâm trước; hỏi nhiều ý thì trả lời từng ý.
- **Context dạng JSON.** `ai.context-format=text` cho bản văn bản bán cấu trúc (T11 Sprint 2, mục 45): ít hơn 7,5 %
  token, accuracy / consistency như nhau, nhưng AI trích đủ căn cứ ít hơn (77 so với 96/115) — giữ JSON làm mặc định.

Prompt phân loại xử lý câu không dấu, câu cụt, câu đời thường; câu dán bí mật rồi viết tiếp vẫn giữ phần yêu cầu.

**Dấu vân tay prompt.** Evaluation Runner in 12 ký tự đầu SHA-256 của hai prompt và hai schema (dòng `Prompt AI`).
Hai lượt đo khác dấu vân tay thì không so số liệu trực tiếp.

Chi phí đo được (lượt 2026-10-14): khoảng 5 100 token vào và 190 token ra cho lời gọi phân tích, cộng khoảng 900 token
vào cho lời gọi phân loại — tổng khoảng 0,001 USD mỗi câu hỏi.

---

## 10. API và schema report

### 10.1. Endpoint (profile `web`)

| Endpoint | Vào | Ra |
|---|---|---|
| `POST /api/analyze` | multipart: `message` (câu hỏi, có thể trống), `files` (0..n file log) | JSON `Response` |
| `GET /api/report-layout` | — | Tiêu đề, nhãn 5 dòng đầu, tên các mục, giới hạn dung lượng file — Web UI dựng bố cục từ đây, không tự viết lại |

`Response`:

| Trường | Nghĩa |
|---|---|
| `requestId` | UUID của request; cùng mã với `request_id` trong log |
| `type` | `REPORT`, `OUT_OF_SCOPE` (câu từ chối cố định), `INVALID_REQUEST` (không file, không Call-ID); hai loại bị controller từ chối trước khi phân tích chỉ có `type` + `message`: `TOO_MANY_FILES` (quá 10 file), `QUESTION_TOO_LONG` (câu hỏi quá 4 000 ký tự) |
| `intent`, `focus` | Kết quả phân loại câu hỏi |
| `message` | Câu từ chối hoặc câu báo lỗi; null khi có report |
| `report` | Report JSON theo schema dưới đây |
| `rendered` | Report dạng Markdown theo mẫu MVP 4.5 |

Mã HTTP: `200` (kể cả `OUT_OF_SCOPE`); `400` với `INVALID_REQUEST`, `TOO_MANY_FILES`, `QUESTION_TOO_LONG`; `413` khi vượt giới hạn multipart (100 MB mỗi file,
300 MB cả request);
`500` với câu báo lỗi chung. File quá 20 MB **không** làm request lỗi: file đó bị loại và nêu ở "Giới hạn dữ liệu".

### 10.2. Report schema

`src/main/resources/schema/report-v1.schema.json`. Report luôn qua schema trước khi render; phần AI làm report sai
schema thì dựng lại từ rule (`REPORT_SCHEMA_INVALID`).

| Trường | Nội dung |
|---|---|
| `callId`, `verdict`, `qualityFlag`, `issueCategory` | Kết luận cuối |
| `confidenceLevel`, `confidenceReasons` | Mức tin cậy và lý do (ít nhất một) |
| `summary` | Tóm tắt một câu |
| `evidence[]` | `id`, `source`, `timestamp`, `description`, `sourceRef` (`tên file:dòng` hoặc `signaling#N`) |
| `metrics[]` | `name`, `value` + `unit` **hoặc** `naReason`, `source` |
| `possibleCauses` | `primary` (chuyện gì xảy ra), `reasoning` (vì sao kết luận), `basisEvidenceIds` (căn cứ của rule), `alternatives` |
| `suggestions`, `aiSuggestions` | Bộ đề xuất chuẩn; phần AI bổ sung (tối đa 3) |
| `dataLimitations` | Thiếu file, thiếu chỉ số, bản export bị cắt, câu hỏi hỏng mã hoá… |
| `analysis`, `citedEvidenceIds` | Phần phân tích và evidence AI trích; null / rỗng khi report từ rule |
| `analysisSource`, `degraded`, `fallbackReason`, `needsReview` | Nguồn kết luận, có lùi về rule không và vì sao, AI có lệch rule không |

Schema ép cả quy tắc nghiệp vụ, không chỉ cấu trúc (mục 18): `additionalProperties: false` ở mọi cấp; chỉ số có giá trị
thì không có lý do N/A và ngược lại; `degraded` khi và chỉ khi có `fallbackReason`; report nguồn `RULE` không được có
`analysis`, `citedEvidenceIds`, `aiSuggestions`.

---

## 11. Quan sát hệ thống

- Log có cấu trúc: trường đi dạng key-value; profile `web` in JSON mỗi sự kiện một dòng, CLI in chữ (mục 36).
- `request_id` nằm trong MDC suốt request, nên dòng của mọi bước (kể cả truy vấn Elasticsearch) lọc được theo một mã.
- Dòng `Request done` ghi latency từng bước: `intent_ms`, `analysis_ms` (parse + Elasticsearch + rule), `ai_ms`
  (AI + Guardrails), `report_ms`; dòng `Signaling fetch` ghi `signaling_ms`, dòng `AI verdict` ghi `guardrails_ms`.
- Profile `web` ghi mỗi request một bản ghi vào index `analysis-requests` (kết quả, verdict, độ tin cậy, nguồn, lý do
  fallback, latency từng bước, token, tên lớp lỗi) — chỉ mã, số, cờ; ghi bất đồng bộ, Elasticsearch lỗi không làm hỏng
  request. `scripts/kibana/setup_views.py` dựng data view và 4 saved search trên Kibana: tất cả, fallback, lỗi, chậm
  (mục 39).
- Lệnh `--batch-analyze` (T10) ghi mỗi Call-ID một bản ghi vào index `call-analysis-results`: verdict, loại lỗi, độ tin
  cậy, nguồn dữ liệu, số evidence — chỉ mã, số, cờ, `call_id` mã giả; lượt mới thay lượt cũ. Index riêng để không làm
  sai tỉ lệ fallback / độ trễ của request web. `scripts/kibana/setup_dashboard.py` dựng dashboard đọc index này
  (design-decisions mục 48).

---

## 12. Giới hạn ảnh hưởng tới thiết kế

Danh sách đầy đủ: README, mục Known Limitations.

- Data có nhãn chỉ phủ 3/6 category; hai category chất lượng mạng còn UNVALIDATED.
- WebRTC log chưa đồng bộ được với giờ tuyệt đối; evidence từ WebRTC ghi mốc tương đối.
- Chỉ số MOS / RTT / jitter là giá trị lúc kết thúc cuộc gọi; log không có bộ đếm để tính cho cả cuộc.
- Đề xuất AI diễn đạt lại bộ chuẩn có thể lọt vào phần "Bổ sung (AI)".
- Không giới hạn độ dài câu hỏi; câu vượt cửa sổ ngữ cảnh của model thì report lùi về rule với lý do chung.
- Luồng CLI chỉ rule (`--analyze`) có thể ra HIGH, trong khi báo cáo có thử AI mà AI lỗi thì tối đa MEDIUM (mục 35).
