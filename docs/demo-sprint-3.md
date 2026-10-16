# Kịch bản Final Demo — Sprint 3

> MVP mục 10: *"Demo riêng sản phẩm của bạn trên web UI với 6 scenario sau; sau đó Mentor trình bày kết quả
> `held-out`"*. Tài liệu đi đúng 6 scenario, thêm một câu ngoài phạm vi và hai chỗ của Sprint 3.
>
> Kết quả ghi dưới mỗi scenario là kết quả **chạy thật** đúng câu hỏi và đúng file này, ngày 2026-10-12, với
> `gpt-4o-mini`. Cả kịch bản 11 lần bấm Phân tích, chưa tới 0,01 USD. Tổng thời gian khoảng 20 phút.
>
> Kịch bản Sprint 2 (`docs/demo-sprint-2.md`) giữ làm lịch sử; chỗ khác nhau ghi ở cuối tài liệu.

---

## Chuẩn bị (10 phút trước demo)

Các bước dựng lần đầu: `docs/runbook.md`. Trước demo chỉ cần:

```powershell
cd <thư mục repo>
docker compose up -d elasticsearch
curl.exe -s http://localhost:9200/_cluster/health                  # đợi "green" hoặc "yellow"
.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web,plain-log"
```

Mở http://localhost:8081/. Profile `plain-log` để log server in dạng chữ, dễ đọc trên màn hình; bỏ nó đi nếu muốn cho
xem log JSON (scenario 6). Kiểm `.env` có `OPENAI_API_KEY`.

Mở sẵn các thư mục sau trong Explorer để kéo thả file:

| Tắt | Thư mục trong `..\ai20k_sample\` |
|---|---|
| `EE129C8F` | `success\EE129C8F-EAD0-4302-AB68-920D32F8B8B7` |
| `271D1FAF` | `for_test\271D1FAF-26D4-4150-AC3F-9204505BD84B` (chỉ để so ngưỡng mới, scenario 2) |
| `B5F19381` | `..\ai20k_new_dataset\b5f19381-9733-8c14-f148-3dd427cff29a` (thư mục cạnh `ai20k_sample`) |
| `1B009D42` | `fail\1B009D42-49CD-479E-B26C-3A2994AEB720` |
| `2D9057AA` | `fail\2D9057AA-C496-48B2-946A-98FA2896D086` |
| `703100CF` | `fail\703100CF-5742-467E-9E0E-34E45F60FF58` |
| `DE7DD314` | `success\DE7DD314-F432-45CB-BCB4-AE9103CC0919` |

Thao tác giống nhau ở mọi scenario: gõ câu hỏi, kéo thả file, bấm **Phân tích** (hoặc Ctrl + Enter). Không đính kèm
`signaling.json`: signaling lấy từ Elasticsearch theo Call-ID.

---

## Phần 0 — Nói trước khi bấm (1 phút)

> Câu hỏi đi qua Request Parser (AI hiểu ý, Call-ID lấy bằng regex), File Validator, rồi pipeline: timeline, chỉ số,
> rule, evidence — **tất cả bằng code**. Sanitizer làm sạch, AI chỉ viết phần giải thích, Guardrails kiểm câu trả lời
> của AI, hỏng thì lùi về rule. Report qua schema rồi mới hiển thị.

Ba chỗ Sprint 3 thêm vào report, sẽ thấy ở mọi scenario:

1. Dòng **Độ tin cậy** có lý do đi kèm.
2. Mục nguyên nhân trả lời đủ bốn câu: **Chính** (chuyện gì xảy ra), **Vì sao kết luận**, **Căn cứ của rule** (evidence
   nào), và **Đề xuất** (điều tra gì tiếp).
3. **Đề xuất** chuẩn của rule luôn giữ; AI chỉ thêm ở dòng "Bổ sung (AI)".

---

## Scenario 1 — Cuộc gọi bình thường: `EE129C8F` (2 phút)

**MVP mong:** `SUCCESS`, không cờ chất lượng.

- Câu hỏi: `Phân tích giúp mình cuộc gọi này`
- File: cả 4 file log của `EE129C8F`

| Thấy gì | Nói gì |
|---|---|
| `SUCCESS` · `Cờ chất lượng: Không` · `HIGH — bằng chứng rõ: ICE đạt connected, kết thúc bằng BYE, không thấy lỗi media; AI khớp rule` | Đủ ba điều kiện của bảng 7.2 nên HIGH |
| Tóm tắt có `4.42133 [MOS (caller)]` | AI chỉ viết mã chỉ số, code điền giá trị và tên — AI không tự chép số |
| Giới hạn: *"File calleer_webrtc.log được gán lại từ CALLEE sang CALLER"* | Tên file gõ sai trong data gốc; hệ thống gán theo nội dung (vai SDP), không theo tên |

## Scenario 2 — Chất lượng kém: `B5F19381` (2,5 phút)

> **Đổi 2026-10-15.** Ngưỡng mất gói nay là mất gói CẢ CUỘC từ 5 % (mentor: "packet loss < 5 %, jitter < 30 ms là ổn
> định"). Ca cũ `271D1FAF` cả cuộc chỉ 1,83 % nên không còn bị gắn cờ. `B5F19381` (dataset mới của mentor) là cuộc duy nhất
> vượt ngưỡng: caller mất gói cả cuộc 8,48 %. Kết quả dưới đây là của rule (`--analyze`, 2026-10-15); **chưa bấm lại trên
> web với AI sau khi đổi ngưỡng** — chạy thử một lần trước buổi demo.

**MVP mong:** `SUCCESS` + cờ chất lượng, `NETWORK_PACKET_LOSS`.

- Câu hỏi: `Vì sao bên gọi nghe bị giật?`
- File: cả 4 file log của `B5F19381`

| Thấy gì | Nói gì |
|---|---|
| `Cờ chất lượng: Có - NETWORK_PACKET_LOSS` | Cờ dựa trên mất gói cả cuộc (hai bộ đếm cộng dồn), không bật vì một đợt ngắn |
| Chính: *"caller mất gói cả cuộc 8.48 % (ngưỡng: mất gói cả cuộc từ 5 %). Đối chiếu: caller có 33/71 mẫu stats mất gói > 5 % (cao nhất 49.51 %) … MOS thấp nhất 1.943 (caller), app bật hasMediaPoor ở 5 mẫu"* | Nêu con số bật cờ và ngưỡng, kèm số đo theo mẫu để đối chiếu |
| `LOW — bằng chứng yếu: ngưỡng chất lượng kém chưa kiểm chứng trên data có nhãn` | Không có cuộc chất lượng kém nào có nhãn: hệ thống tự nói ra thay vì giả vờ chắc chắn |
| Đầu report ghi *"Phân tích, ưu tiên: bên gọi nghe bị giật"* | Request Parser tách trọng tâm câu hỏi |

`B5F19381` chưa có nhãn (dataset mới, đang hỏi mentor). Có thể mở thêm `271D1FAF` (`for_test/`) để cho thấy ngưỡng mới:
nhiều mẫu tăng vọt tới 11 % nhưng cả cuộc 1,83 % thì báo `SUCCESS` không cờ.

Chủ động nói một điểm yếu: dòng *"Bổ sung (AI): Kiểm tra chất lượng mạng của bên gọi…"* là AI diễn đạt lại đề xuất
chuẩn ngay phía trên. Bộ lọc bỏ được câu chép nguyên văn, chưa bỏ được câu diễn đạt lại (Known Limitations).

## Scenario 3 — Lỗi thiết lập: `1B009D42` (2 phút)

**MVP mong:** `FAIL`, `SIGNALING_FAILURE`.

- Câu hỏi: `Vì sao gọi không được?`
- File: `caller_endcall.log`, `caller_webrtc.log` của `1B009D42` (cuộc này chỉ có log bên gọi)

| Thấy gì | Nói gì |
|---|---|
| `FAIL` · `SIGNALING_FAILURE` · `HIGH — bằng chứng rõ: server từ chối INIT_CALL với mã 428 …` | Câu trả lời của chính server nên vẫn HIGH dù thiếu log bên nhận — ngoại lệ duy nhất của quy tắc "thiếu file thì tối đa MEDIUM" |
| Mã `428 call.outgoing.error.privacy_restricted` | Đọc nguyên văn từ log, không viết cứng theo loại lỗi |
| Đề xuất: *"… đây là quyết định của server, không phải lỗi mạng"*, *"Không cần điều tra mạng, ICE hay TURN"* | Đề xuất theo nguyên nhân con của SIGNALING_FAILURE (Sprint 3). Trước đây cuộc này nhận câu chung "kiểm tra cấu hình timeout" |

## Scenario 4 — Lỗi media: `2D9057AA` và `703100CF` (4 phút)

**MVP mong:** `FAIL`, `ICE_FAILURE` hoặc `TURN_FAILURE`.

### 4a — `2D9057AA`, ca quan trọng nhất

- Câu hỏi: `Vì sao bên nhận không nghe được gì?`
- File: `callee_endcall.log`, `callee_webrtc.log` của `2D9057AA`

Signaling của cuộc này hoàn hảo (có `BYE`, `PAIR_PING` đều), nhưng ground truth là FAIL. Report:

| Thấy gì | Nói gì |
|---|---|
| `FAIL · ICE_FAILURE` | Rule xét media trước khi được kết luận SUCCESS |
| `MEDIUM — thiếu log client của caller; bằng chứng rõ: ICE chuyển sang failed, không có byte audio nào` | Bằng chứng mạnh nhưng thiếu một nửa log, nên MEDIUM — lý do đứng đầu |
| Vì sao kết luận: *"ICE chuyển sang failed và không bao giờ đạt connected"* · Căn cứ của rule: `EV07, EV12, EV13` | Bấm vào ID để cuộn tới đúng dòng log |
| Phân tích trả lời trọng tâm *"bên nhận không nghe được"* | |

### 4b — `703100CF`, TURN hỏng thật

- Câu hỏi: `Cuộc gọi này lỗi gì?`
- File: `caller_endcall.log`, `caller_webrtc.log` của `703100CF`

| Thấy gì | Nói gì |
|---|---|
| `FAIL · TURN_FAILURE` | Không có lần `allocate requested successfully` nào trong cả file. Dòng `TURN allocate error response` có ở cả cuộc thành công — đếm nó sẽ báo nhầm 11/20 cuộc |
| Đề xuất đầu: *"Chưa có request nào tới được TURN server: WebRTC không tạo được socket TURN …"* | Sprint 2, AI từng khuyên "kiểm tra cấu hình TURN server" ở cuộc này. Nay đề xuất chuẩn luôn giữ, và không có dòng AI nào khuyên sai hướng |
| Đề xuất về VPN `tun0`: *"… chưa đủ để kết luận VPN là nguyên nhân"* | VPN chỉ có ở 3 cuộc của cùng một máy: nêu như dữ kiện, không viết thành nguyên nhân |

## Scenario 5 — Thiếu dữ liệu: `DE7DD314` (2 phút)

**MVP mong:** `UNKNOWN`, nêu rõ file thiếu.

### 5a — chỉ một file

- Câu hỏi: `Phân tích giúp mình cuộc gọi này`
- File: chỉ `caller_endcall.log` của `DE7DD314`

Report ra `SUCCESS`, độ tin cậy `MEDIUM — bằng chứng chưa trọn: không thấy ICE đạt connected; bản export signaling bị
cắt bớt …; thiếu WebRTC log …; thiếu log client của callee`. "Giới hạn dữ liệu" liệt kê từng file thiếu.

Nói rõ: đây là chỗ **lệch MVP có chủ đích, chờ mentor chốt**. Ground truth của `5E0800AE` (cuộc chỉ có một file) là
`SUCCESS`, mâu thuẫn với scenario 5. Hệ thống đang theo nhãn, nhưng hạ tin cậy và nêu đủ bốn lý do.

### 5b — không đính kèm file nào

- Câu hỏi: `Phân tích cuộc gọi DE7DD314-F432-45CB-BCB4-AE9103CC0919`
- File: không có

Report ra `UNKNOWN · LOW — bằng chứng yếu: không có log client để kiểm chứng media`. Signaling đạt `OK_ACK_OK` nhưng
"signaling đẹp chưa đủ để kết luận SUCCESS" (bài học của `2D9057AA`). Call-ID lấy bằng regex từ câu hỏi.

## Scenario 6 — Nhất quán + dữ liệu nhạy cảm: `2D9057AA` (3 phút)

**MVP mong:** cùng verdict qua 3 cách hỏi; AI request không chứa secret; report / log đã làm sạch.

File cho cả ba lần: `callee_endcall.log`, `callee_webrtc.log` của `2D9057AA` (hai file này có 176 dòng chứa IPv4 thật).

| Lần | Câu hỏi | Kết quả chạy thật |
|---|---|---|
| 1 | `Cuộc gọi này có thành công không?` | `FAIL · ICE_FAILURE · MEDIUM`, căn cứ `EV07, EV12, EV13` |
| 2 | `kiem tra cuoc goi nay giup minh` (không dấu) | như trên |
| 3 | `Cuộc gọi từ máy 0912345678 (IP 203.0.113.7) bị lỗi, phân tích giúp mình` | như trên |

Tính cả 4a là bốn cách hỏi, cùng kết luận, cùng mức tin cậy, cùng căn cứ.

**Chỉ chỗ đã làm sạch:**

1. Report trên web không có số điện thoại hay IP nào. Mở DevTools → Network → phản hồi `/api/analyze`: JSON cũng sạch
   (kiểm 2026-10-12: 0 / 11 phản hồi chứa số điện thoại hay IP của câu 3).
2. Đúng thứ gửi cho AI, xem bằng lệnh không gọi AI:

   ```powershell
   .\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--ai-context=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086 '--question=Cuoc goi tu may 0912345678 (IP 203.0.113.7) bi loi'"
   ```

   Câu hỏi in ra đã thành `Cuoc goi tu may [CONTACT_REDACTED] (IP [IP_REDACTED]) bi loi`.
3. Log server: dòng `AI call` chỉ có `request_id`, `call_id` (mã giả `CALL_…`), `model`, `latency_ms`,
   `result_status`, `token_usage` — không nội dung. Chạy không có `plain-log` để thấy log JSON, mỗi dòng một sự kiện,
   lọc được theo `request_id`. Kiểm 2026-10-12: 0 dòng log chứa số điện thoại hay IP của câu 3.
4. Số đo cả tập (`dev`, AI thật): 0 / 1 555 giá trị nhạy cảm gốc lọt vào dữ liệu gửi AI, 0 trong phản hồi, 0 trong log.

Log mẫu không có JWT hay số điện thoại; các ca đó nằm trong test S01-S08:
`.\build.ps1 -q test "-Dtest=SensitiveDataSanitizerTest"`.

---

## Thêm 1 — Câu ngoài phạm vi (30 giây)

- Câu hỏi: `Viết giúp mình một email xin nghỉ phép`
- File: `caller_endcall.log` của `DE7DD314`

Trả về câu từ chối viết sẵn trong code (≈ 0,8 s), giống hệt nhau mọi lần, không chạy pipeline phân tích.

## Thêm 2 — Khi AI không dùng được (1 phút, tuỳ chọn)

Dừng server, chạy lại với khoá sai rồi bấm Phân tích một scenario bất kỳ:

```powershell
.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web,plain-log" "-Dspring-boot.run.arguments=--ai.openai.api-key=sk-khong-dung"
```

Banner vàng *"Report dựng từ rule vì AI không dùng được — khoá AI không hợp lệ…"*: kết luận vẫn đúng, độ tin cậy tối đa
MEDIUM với lý do *"không có AI để đối chiếu (AUTH_FAILED)"*. Lỗi khoá không được thử lại; quá giờ hay mất mạng thì hệ
thống tự thử lại một lần (log có dòng `result_status=RETRY`). Phần này đã kiểm bằng dòng lệnh với API thật (khoá giả →
`AUTH_FAILED`, không thử lại), chưa chạy thử trên web.

## Thêm 3 — Xem request trên Kibana (1 phút, tuỳ chọn)

Trước demo: `docker compose up -d` và `python scripts/kibana/setup_views.py`. Sau các scenario, mở
http://localhost:5601/app/discover → **Open** → *Requests — tất cả*: mỗi lần bấm Phân tích là một dòng, cột verdict, độ
tin cậy, nguồn, lý do fallback, latency. *Requests — fallback* chỉ còn các lần lùi về rule (ví dụ phần Thêm 2). Bản ghi
chỉ có mã và số: không câu hỏi, không nội dung log; `call_id` là mã giả.

---

## Nếu có sự cố trong lúc demo

| Triệu chứng | Nguyên nhân thường gặp | Xử lý |
|---|---|---|
| Mọi report ra `UNKNOWN`, giới hạn nói không lấy được signaling | Elasticsearch tắt hoặc chưa nạp | `docker compose up -d elasticsearch`, nạp lại signaling (runbook bước 2.5) |
| Banner vàng *"Report dựng từ rule"* | Khoá sai, hết hạn mức, mạng lỗi, hoặc Guardrails chặn | Đây chính là fallback; banner ghi lý do. Kết luận vẫn đúng |
| Câu hỏi bị từ chối "ngoài phạm vi" khi AI không dùng được | Bộ từ khoá dự phòng bỏ sót vài cách hỏi ngắn, ví dụ *"Cuộc này ổn không?"*. Câu của 6 scenario đều qua | Hỏi lại có chữ "cuộc gọi": *"Cuộc gọi này ổn không?"* |
| Trang báo file bị loại | File quá 20 MB, rỗng, không nhận diện được, hoặc khác Call-ID | Đúng thiết kế (F01-F04): file lỗi bị loại riêng, file còn lại vẫn được phân tích |
| `Unknown lifecycle phase ".run.profiles=web"` | `-D…` không nằm trong nháy kép | Đặt trong nháy kép |

Danh sách đầy đủ: `docs/runbook.md`, mục 7.

---

## Khác với kịch bản Sprint 2

| Chỗ | Sprint 2 | Sprint 3 |
|---|---|---|
| Độ tin cậy | Chỉ có mức | Mức kèm lý do |
| Mục nguyên nhân | Chính, Phân tích, Căn cứ (chỉ khi có AI) | Thêm "Vì sao kết luận" và "Căn cứ của rule", có cả khi không có AI |
| Đề xuất `703100CF` | AI khuyên "kiểm tra cấu hình TURN server" (sai hướng) | Đề xuất chuẩn của rule luôn giữ, AI chỉ bổ sung |
| Đề xuất `1B009D42` | Ba câu chung của SIGNALING_FAILURE | Đề xuất riêng cho ca bị server từ chối |
| Log server | Dạng chữ `key=value` | JSON có cấu trúc (profile `web`), `call_id` mã giả |
| Lỗi AI | Mọi lỗi HTTP là "không gọi được dịch vụ AI" | Tách khoá sai, hết hạn mức, câu hỏi quá dài; thử lại lỗi tạm thời |
