# Peer Evaluation Report (Sprint 3 T7)

> MVP mục 7.3: chạy sản phẩm của bạn còn lại trên tập `dev` theo Runbook của bạn ấy, rồi báo cáo: Runbook có đủ để tự
> dựng và chạy không; tối thiểu 2 issue kèm bước tái hiện; tối thiểu 1 điểm thiết kế học được. Không sửa code của nhau —
> người nhận báo cáo tự quyết định fix issue nào.

| Mục | Nội dung |
| --- | --- |
| Người đánh giá | Bế Nguyễn Hà Sơn |
| Sản phẩm được đánh giá | Repo của Trần Anh Tú — `github.com/tutran0401/call-analysis-assistant`, commit `a5010a6` (2026-10-14) |
| Ngày chạy | 2026-10-19 |
| Môi trường | Windows 11, Git Bash; JDK mặc định 26.0.2 và JBR 21.0.10; Maven 3.9.11; Node 22.19 / npm 10.9; Docker 29.1.3 |
| Dữ liệu | `ai20k_sample` (`success/`, `fail/`, `for_test/`, 20 cuộc) + `ai20k_new_dataset` (7 cuộc), đặt vào `sample-data/` đúng mục 1 Runbook |
| AI | OpenAI `gpt-4o-mini`, key của người đánh giá |

Cách làm: làm theo Runbook từng bước, đúng chữ; chỗ nào phải làm khác Runbook thì ghi lại. Mọi con số trong báo cáo
được đối chiếu lại với log gốc trước khi ghi thành issue — vài chỗ trông giống lỗi nhưng kiểm lại thì đúng (mục 2.6).

---

## 1. Runbook có đủ để tự dựng và chạy được không?

| Bước (mục Runbook) | Runbook ghi | Kết quả | Phải làm thêm ngoài Runbook |
| --- | --- | --- | --- |
| Yêu cầu (1) | JDK 17+, Maven 3.9+, Node 18+, Docker | Đủ công cụ | — |
| Data mẫu (1) | `sample-data/{success,fail,for_test,new_dataset}/<Call-ID>/` | Khớp bố cục data mentor; code tra thư mục theo Call-ID, không phân biệt hoa thường | Đổi tên `ai20k_new_dataset` → `new_dataset` |
| ES + Kibana (2) | `docker compose up -d elasticsearch kibana` | ES `green`, Kibana lên | Dừng ES / Kibana của mình trước (cùng cổng 9200 / 5601) |
| Build (3) | `mvn clean package` (build + toàn bộ test) | **BUILD FAILURE** với JDK 26 mặc định: 5 lỗi Mockito + 1 test tích hợp; không có jar | Dùng JDK 21 và build `-DskipTests` trước — xem R1, R2 |
| Import (3) | `java -jar … import sample-data/success … new_dataset` | 27 cuộc, 1 812 event, chạy lại không nhân đôi | — |
| Cấu hình AI (4) | `cp .env.example .env`, điền key | `/api/health`: AI configured, ES UP | — |
| Backend + Web UI (5) | backend cổng 8080, `npm run dev` cổng 5173 | Backend **không lên**: cổng 8080 bị một dịch vụ khác trên máy chiếm | Chạy backend `--server.port=8090` và một file cấu hình Vite tạm trỏ proxy sang 8090 — xem R3 |
| Test (6) | `mvn test`; `npm install && npm test` | Sau khi import và dùng JDK 21: **328/328** backend, **15/15** Web UI | — |
| Benchmark `dev` (7) | `evaluate --cases benchmark/dev/cases.yaml --data-root sample-data --repeat 5` | Chạy hết — mục 4 | — |
| Bảo mật (8) | `security-eval --data-root sample-data` | **PASS**, 3 lượt (AI thật, AI giả rò mọi giá trị, AI giả rò giá trị không có chữ số) | — |
| Batch + Kibana (9) | `batch --data-root sample-data`; `sh kibana/import-dashboard.sh` | 27 cuộc, 0 lỗi; 10 đối tượng Kibana, dashboard hiển thị | — |
| CLI (5) | `analyze <thư mục>`, `--from-file` | Chạy được cả hai đường signaling (ES / file) | — |

**Kết luận: Đủ, nhưng phải tự xử lý 3 chỗ** — người mới làm đúng chữ sẽ kẹt ở bước 3 (R1 + R2). Còn lại Runbook rất
đầy đủ: có lệnh cho từng việc mentor cần (kể cả chạy `held-out`), bảng xử lý sự cố khớp với lỗi thật gặp phải, và mọi
lệnh khác chạy đúng như ghi.

**R1 — Không nêu giới hạn trên của JDK.** Runbook ghi "JDK 17+". Máy có JDK 26 làm mặc định: build chạy, nhưng 5 test
`ElasticsearchSignalingTest` lỗi *"Mockito cannot mock this class: … ElasticsearchClient … Java: 26"* (Mockito / Byte
Buddy chưa hỗ trợ Java 26). Cùng lệnh với JDK 21: 5 lỗi biến mất. *Đề xuất:* ghi "JDK 17–21" (hoặc bản đã kiểm), thêm
dòng xử lý sự cố.

**R2 — Bước 3 chạy test trước khi có dữ liệu, nên build hỏng và không có jar để import.** `mvn clean package` chạy cả
`ElasticsearchPipelineIntegrationTest`. Test này chỉ tự bỏ qua khi ES **tắt**; theo Runbook, ES đang **bật nhưng rỗng**
(bước 2 xong, chưa import) → *"JSON path $.report.verdict expected:<FAIL> but was:<UNKNOWN>"* → `BUILD FAILURE` → không
có jar → không chạy được lệnh `import` ngay dòng sau. Tái hiện: làm bước 2, rồi bước 3 đúng chữ. *Đề xuất:* đổi bước 3
thành `mvn clean package -DskipTests` → `import` → `mvn test`; hoặc test tự bỏ qua khi index chưa có dữ liệu.

**R3 — Cổng 8080 viết cứng ở hai chỗ, không có dòng xử lý sự cố.** Backend (`application-web.yml`) và proxy của
frontend (`web/vite.config.ts`) cùng dùng 8080. Đổi cổng backend bằng tham số thì UI vẫn gọi 8080 — muốn đổi phải sửa
code. *Đề xuất:* đọc cổng proxy từ biến môi trường, thêm dòng "Port 8080 was already in use" vào bảng xử lý sự cố.

---

## 2. Issue tìm được

Xếp theo mức ảnh hưởng tới report người dùng đọc. Issue 1–4 có thể tái hiện bằng Web UI / `POST /api/chat`; dữ liệu
dùng là data mẫu của mentor.

### Issue 1: "Số lần No sessions found" báo `0` khi không đo được (lẽ ra `N/A`)

**Mức:** trung bình — trái MVP 4.3 ("Chỉ số thiếu → N/A kèm lý do, không mặc định 0").

**Tái hiện:**

1. Web UI, câu hỏi bất kỳ, đính kèm `success/DE7DD314-…/caller_endcall.log`.
2. Bảng chỉ số: `Số lần "No sessions found" | 0 lần | Signaling/End Call log`.

**Vì sao sai:** "No sessions found" là thông báo của **server** trong trường `message` của signaling. Bản export của
`ai20k_sample` là dạng gọn — không có `message` — nên với 20 cuộc này không ai biết giá trị thật. Log máy cũng không
bao giờ chứa chuỗi này: ở 3 cuộc của dataset mới mà signaling có 3 / 4 / 3 lần, log máy có **0** lần. Code
(`SignalingCountCalculator.noSessionsFoundCount`) chỉ trả `N/A` khi **không event nào** có trường `msg` / `message`;
end call log luôn có `msg`, nên cứ đính kèm log máy là ra `0`.

**Đề xuất:** chỉ đếm trên signaling; signaling không có `message` thì `N/A` ("bản export dạng gọn không có nội dung
thông báo"). Với dataset mới (có `message`) code hiện đếm đúng — `06BCA48C` ra 3.

### Issue 2: Cờ "chất lượng kém — mất gói" bật theo một mẫu đỉnh của `packetLostPercent`, không theo mất gói của cuộc gọi

**Mức:** trung bình — 4/5 cuộc bị gắn `NETWORK_PACKET_LOSS` có mất gói cả cuộc dưới 5 %.

**Tái hiện:**

1. Web UI, đính kèm 4 file của `new_dataset/06bca48c-…`.
2. Report: `SUCCESS`, cờ chất lượng **có**, `NETWORK_PACKET_LOSS`; bảng chỉ số `Packet loss cao nhất (caller) | 38.571 %`.

**Vì sao sai:** `audio.packetLostPercent` là tỉ lệ mất gói **trong khoảng** giữa hai lần đo, không phải của cả cuộc.
Code lấy **giá trị lớn nhất** của trường này (`LegQualityCalculator`) và bật cờ khi > 5 % (`PacketLossQualityCheck`), nên
một khoảng đo ngắn đủ làm cả cuộc "chất lượng kém". Mất gói cả cuộc tính từ hai bộ đếm cộng dồn
`audio.packetsLost ÷ (packetsLost + packetsReceived)` ở mẫu stats cuối (cùng log):

| Cuộc | Mẫu cao nhất | Số mẫu > 5 % | Mất gói cả cuộc |
| --- | --- | --- | --- |
| `06BCA48C` (caller) | 38,6 % | 8 / 514 | **0,36 %** |
| `4FD495C1` (caller) | 18,0 % | 6 / 91 | **1,39 %** |
| `271D1FAF` (caller) | 11,3 % | 18 / 80 | **1,83 %** |
| `FE4E4BDC` (callee `734b966f`) | 100 % | 1 / 9 | **4,65 %** |
| `B5F19381` (caller) | 49,5 % | 33 / 71 | **8,48 %** |

Chỉ `B5F19381` mất gói đáng kể cả cuộc. Thêm nữa, ở cả 5 cuộc mục **Evidence chính** chỉ có dòng signaling
(INIT_CALL, OK_ACK_OK, BYE) — không có dòng stats nào làm căn cứ cho cờ chất lượng, trái MVP 3.3 ("mọi kết luận phải
trace được về evidence").

**Đề xuất:** dùng mất gói cả cuộc (bộ đếm cộng dồn) cho ngưỡng; giá trị đỉnh nếu giữ thì ghi rõ là "mẫu cao nhất" và
chỉ làm thông tin đối chiếu; thêm dòng stats vượt ngưỡng vào evidence khi bật cờ.

### Issue 3: Signaling bị cắt bớt nhưng report ghi "Giới hạn dữ liệu: Không có", độ tin cậy HIGH

**Mức:** trung bình — trái MVP 3.3 (thiếu dữ liệu phải nêu ở "Giới hạn dữ liệu") và 7.2 (độ tin cậy xét dữ liệu thiếu).

**Tái hiện:**

1. `success/DE7DD314-…/signaling.json` có `"truncated": true, "returned": 200, "total_matching": 201`.
2. Web UI, đính kèm cả 4 log của `DE7DD314` → `Độ tin cậy: HIGH`, `Căn cứ: … đủ file log`, `Giới hạn dữ liệu: Không có.`
3. CLI `analyze sample-data/success/DE7DD314-… --from-file`: report giống hệt; cảnh báo *"Signaling export … is
   truncated (returned 200 of 201)"* chỉ nằm ở mục phụ "Ghi chú xử lý (parser warnings, không thuộc report chính thức)".
4. CLI không `--from-file` (signaling từ ES, cũng là đường của Web UI): không có cảnh báo nào.

**Vì sao:** cờ `truncated` chỉ được đọc lúc parse file thành cảnh báo, không vào `dataLimitations` hay chính sách độ tin
cậy; mapping index `call-signaling-events` không có trường nào giữ thông tin này nên đường ES mất hẳn.

**Đề xuất:** lưu `truncated` / `returned` / `total_matching` khi import, đưa thành một dòng Giới hạn dữ liệu và hạ độ tin
cậy (bảng 7.2: "thiếu một phần dữ liệu → MEDIUM").

### Issue 4: Evidence từ WebRTC log mang giờ tuyệt đối không có trong file, không có số dòng

**Mức:** thấp–trung bình — MVP 8.2: "mọi evidence trace được về dòng log gốc".

**Tái hiện:**

1. Web UI, đính kèm `fail/2D9057AA-…/callee_endcall.log` + `callee_webrtc.log`.
2. Evidence: `[EV003][callee_webrtc.log 2026-09-17 23:43:22.031] Log WebRTC báo ICE của CALLEE không kết nối được`.
3. Tìm `23:43:22` trong `callee_webrtc.log`: **0** kết quả — WebRTC log chỉ có mốc tương đối `[giây:mili]`. Dòng thật là
   **dòng 902**: `[020:195] … Changing IceConnectionState checking => failed`. JSON của evidence chỉ có `source`,
   `timestamp`, `description` — không có số dòng.

**Vì sao:** mốc tuyệt đối là giá trị suy ra (ghép đồng hồ), hữu ích để đặt sự kiện lên timeline nhưng không dùng để tìm
lại dòng gốc được.

**Đề xuất:** thêm số dòng vào evidence (`callee_webrtc.log:902`), giữ mốc gốc `[020:195]` bên cạnh mốc suy ra.

### Issue 5: Lượt chạy chỉ rule được ghi là `AI_UNAVAILABLE`, làm sai dashboard fallback

**Mức:** thấp — ảnh hưởng quan sát vận hành (T9 / T11), không ảnh hưởng report.

**Tái hiện:**

1. Chạy `batch --data-root sample-data` (chỉ rule theo thiết kế), import dashboard (`kibana/import-dashboard.sh`).
2. Trên Web UI gửi một request khi AI lỗi thật (khởi động backend với key sai → HTTP 401).
3. Dashboard "Call Analysis - kết quả phân tích", biểu đồ **Fallback (degraded) theo lý do**: một màu duy nhất,
   **28** `AI_UNAVAILABLE` — trong đó 27 là lượt batch (`channel: batch`, `model: none`), chỉ 1 là AI lỗi thật.

Lượt "rule baseline" ở đầu mỗi case của `evaluate` cũng ghi dòng log `ai_call … result_status=FALLBACK
fallback_reason=AI_UNAVAILABLE`.

**Đề xuất:** lý do riêng cho chạy chỉ rule theo thiết kế (ví dụ `RULE_ONLY`) hoặc không coi là fallback; biểu đồ fallback
lọc `channel: web` (trường này đã có sẵn trong index).

### 2.6. Đã kiểm, không phải issue

Những chỗ trông như lỗi nhưng đối chiếu log gốc thì đúng:

- `2D9057AA` "Thời gian với tới callee: N/A (không có TRYING)" — signaling của cuộc này thật sự không có TRYING.
- `2D9057AA` "Gửi lại BYE: 4 lần" — 6 sự kiện BYE gom thành 5 lần gửi (cụm 0,5 s → 1 → 2 → 4 s), tức 4 lần gửi lại.
- Call-ID của dataset mới lưu trong ES dạng chữ thường, log máy viết hoa — vẫn tìm đúng signaling (`06BCA48C` đủ 3 lần
  "No sessions found").

### Quan sát (chưa xếp là issue)

Lúc import, trường `rawEvent` lưu nguyên văn sự kiện signaling gốc vào ES, gồm cả `clientIp` (IP công cộng thật; 419
document) và `message` (có User-ID). Bảng phân loại của chính repo xếp IP client là `SENSITIVE` — "bắt buộc mask trước
khi ra khỏi hệ thống", và MVP 6.2 đặt "dữ liệu ghi vào ES local" dưới Output Sanitizer. Cách hiểu điểm này của MVP chưa
rõ (bên mình cũng đang hỏi mentor, câu 5), nên chỉ ghi để bạn cân nhắc; Security Evaluation Report hiện chưa nhắc tới.

---

## 3. Điểm thiết kế học được / muốn áp dụng

1. **Kiểm Output Sanitizer bằng một "AI giả cố tình rò rỉ"** (`security-eval`, lượt 2 và 3). Thay vì chỉ kiểm AI thật
   — vốn hiếm khi trả lại giá trị nhạy cảm, nên S07 khó chứng minh — runner thay AI bằng bản giả trả lại **mọi** giá trị
   nhạy cảm, rồi thêm một lượt trả giá trị **không có chữ số** để vượt qua Guardrails. Như vậy lớp làm sạch đầu ra được
   kiểm riêng, có chủ đích. Bên mình đo leakage đầu ra trên AI thật nên ra 0 nhưng chưa chứng minh được sanitizer chặn
   được khi AI thật sự rò — muốn áp dụng.
2. **Đo AI độc lập với rule** (`--call-analysis.ai.include-rule-baseline=false`). Bên mình gửi kết luận của rule vào
   context AI, nên "AI trùng rule 100 %" chưa chứng minh AI tự suy luận đúng (đã ghi ở Known Limitations). Một cờ cấu
   hình để chạy lại benchmark khi AI không thấy rule là cách đo trực tiếp và rẻ.
3. **`/api/health` báo trạng thái AI và Elasticsearch, UI hiện banner trước khi người dùng gửi** — người dùng biết report
   sắp ra sẽ chỉ dựa trên rule trước khi chờ.

---

## 4. Số liệu chạy `dev`

Lệnh đúng mục 7 Runbook: `evaluate --cases benchmark/dev/cases.yaml --data-root sample-data --repeat 5`. 29 case (27 trong
phạm vi + 2 ngoài phạm vi), 461 lần chạy, `gpt-4o-mini`, context `structured`. Chạy 21:35 → 22:02 (27 phút), 488 lời
gọi AI, khoảng 1,76 triệu token (≈ 0,3 USD) — khớp ước tính trong Runbook ("20-30 phút, khoảng 1,6 triệu token").

| Metric (MVP 6.5) | Lượt chạy lại (2026-10-19) | Bạn ấy công bố (Final Evaluation Report) | Target |
| --- | --- | --- | --- |
| Verdict Accuracy | 96,5 % (416/431) | 96,5 % (416/431) | ≥ 85 % (`held-out`) |
| Metric Correctness | 100 % (431/431) | 100 % (431/431) | 100 % |
| Security Leakage input / output / log | 0 / 0 / 0 | 0 / 0 / 0 | 0 |
| Issue Category Accuracy | 100 % (227/227) | 100 % | ≥ 70 % |
| Consistency (case) | 100 % (27/27) | 100 % (27/27) | ≥ 95 % |
| Template Compliance | 100 % (431/431) | 100 % | 100 % |
| Intent Accuracy | 100 % (461/461) | 100 % (461/461) | ≥ 90 % |
| Unsupported Claim (report giao người dùng) | 0 % (0/431) | 0 % (0/431) | 0 % |
| Pipeline Success Rate | 100 % (461/461) | 100 % (461/461) | ≥ 95 % |
| Fallback | 0 % (0/431) | — | — |
| Latency P50 / P95 | 3 399 / 4 556 ms | 2 948 / 4 608 ms | — |

**Tái hiện được:** mọi metric khớp đúng từng con số bạn ấy công bố; chỉ latency khác (mạng / máy khác). AI vs Rule:
26/27 case Rule = AI = nhãn.

Đọc kèm (đều đã được bạn ấy tự ghi rõ, không phải issue mới):

- 15 lần sai Verdict đều là case `var-single-file` (chỉ `caller_webrtc.log` của `DE7DD314`): nhãn `UNKNOWN` theo scenario 5
  của MVP mục 10, hệ thống ra `SUCCESS`. File case ghi rõ "XUNG ĐỘT NHÃN" với `success/5E0800AE` (nhãn SUCCESS của mentor,
  cũng chỉ có một file client) và đang chờ mentor — đúng câu hỏi bên mình cũng đang chờ.
- Issue Category Accuracy 100 % là so với nhãn category lấy từ chính đầu ra của rule (đã kiểm tay), vì mentor chưa gán nhãn
  category — `cases.yaml` ghi rõ điều này.
- Bộ `dev` chỉ gồm 20 cuộc của `ai20k_sample`; 7 cuộc của dataset mới được thử tay qua Web UI (mục 2) và qua `batch`.

---

## 5. Tóm tắt cho người nhận

| # | Việc | Loại | Mức |
| --- | --- | --- | --- |
| R1 | Runbook: nêu giới hạn trên của JDK | Runbook | Thấp |
| R2 | Runbook: bước 3 build kèm test trước khi import → không có jar | Runbook | Trung bình |
| R3 | Runbook / code: cổng 8080 viết cứng ở backend và proxy Vite | Runbook | Thấp |
| 1 | "No sessions found" = 0 thay vì N/A với bản export dạng gọn | Report | Trung bình |
| 2 | Cờ mất gói theo mẫu đỉnh của khoảng đo; thiếu evidence cho cờ | Report | Trung bình |
| 3 | Signaling bị cắt bớt không vào Giới hạn dữ liệu / độ tin cậy | Report | Trung bình |
| 4 | Evidence WebRTC không trace được về dòng gốc | Report | Thấp–trung bình |
| 5 | Chạy chỉ rule ghi là `AI_UNAVAILABLE`, dashboard fallback sai | Quan sát vận hành | Thấp |

Theo MVP 7.3, bạn tự quyết định sửa issue nào; issue không sửa ghi vào Known Limitations.

---

## 6. Phản hồi của bạn ấy — kiểm lại (2026-10-20)

Bạn ấy sửa cả 5 issue, 3 vấn đề Runbook và cả phần "Quan sát", mỗi việc một commit. Kiểm lại trên bản
`a4d1cf5` (2026-10-20). Bạn ấy đã viết lại lịch sử git, nên commit được đánh giá ở mục đầu (`a5010a6`) nay là `24c65f7`.

Cách kiểm: build lại bằng JDK 21, chạy lại đúng bước tái hiện của từng issue bằng CLI của bạn ấy
(`analyze <thư mục> --from-file`, không cần Elasticsearch, không gọi AI) trên data gốc; phần chỉ chạy được trên ES / Kibana
thì kiểm qua code và test.

| # | Commit sửa | Kiểm lại | Kết quả |
| --- | --- | --- | --- |
| R1 | `a14ab97` | Runbook | Ghi "JDK 17 - 21", kèm dòng xử lý sự cố cho lỗi Mockito trên JDK 26 ✅ |
| R2 | `a14ab97` | Runbook | Thứ tự mới: `mvn clean package -DskipTests` → `import` → `mvn test`; test tích hợp ES tự bỏ qua kèm lý do khi ES chưa có dữ liệu ✅ |
| R3 | `a14ab97` | `web/vite.config.ts`, Runbook | Proxy đọc `BACKEND_URL` (mặc định 8080), Runbook có dòng xử lý "Port 8080 was already in use" ✅ |
| 1 | `7bb43d2` | `DE7DD314`, `271D1FAF` (bản export gọn) · `06BCA48C`, `4FD495C1` (bản thô) | Bản gọn: `N/A (bản export signaling dạng gọn không có nội dung thông báo (message) của server)`; bản thô: 3 và 4 lần, khớp đếm tay ✅ |
| 2 | `228f4fe` | `06BCA48C`, `4FD495C1`, `271D1FAF`, `B5F19381` | Bảng chỉ số tách "Packet loss cả cuộc" và "mẫu cao nhất". Ba cuộc mất gói cả cuộc 0,36 / 1,39 / 1,83 % hết cờ; `B5F19381` (8,48 %) còn cờ và evidence trích đúng dòng `caller_endcall.log:194` ✅ |
| 3 | `e17194b` | `DE7DD314` | Độ tin cậy MEDIUM (trước: HIGH), "Giới hạn dữ liệu" ghi *"Bản export signaling bị cắt bớt (chỉ có 200/201 sự kiện)"* ✅. Đường ES: import lưu trường `exportTruncated` — kiểm qua code và test, chưa chạy trên ES |
| 4 | `2d429e4` | `2D9057AA` | Evidence `[EV003][callee_webrtc.log:902 [020:195] ≈ 2026-09-17 23:43:22.031]` — đúng dòng 902, giữ cả mốc gốc lẫn mốc suy ra ✅ |
| 5 | `b552fc2` | Code, dashboard | Chạy chỉ rule ghi `RULE_ONLY` (không mang tiền tố `AI_`); biểu đồ "Fallback (degraded) theo lý do" lọc `not fallback_reason : "RULE_ONLY"` ✅ — kiểm qua code, chưa chạy lại batch trên ES |
| Quan sát | `74928aa` | Code, test | Signaling (kể cả `rawEvent`) đi qua Sanitizer trước khi ghi vào ES ✅ — kiểm qua code và test |

**Kết luận:** 5/5 issue và 3/3 vấn đề Runbook đã được sửa đúng hướng đề xuất; phần đã chạy lại được cho kết quả như
mong đợi. Ngoài phạm vi các issue: `FE4E4BDC` nay hết cờ mất gói (cả cuộc 4,65 %) nhưng được gắn `NETWORK_DELAY_JITTER`
theo ngưỡng RTT trung bình 300 ms của bạn ấy (RTT 692,6 ms) — là lựa chọn ngưỡng của bạn ấy, không phải lỗi.
