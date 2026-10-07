# Kịch bản demo Sprint 2

> MVP mục 6.6: *"End-to-End Demo trên web UI"*. MVP mục 10 (Final Demo): *"Demo riêng sản phẩm của bạn trên web UI
> với 6 scenario sau"*. Tài liệu này đi đúng 6 scenario đó, thêm một câu ngoài phạm vi.
> Tổng thời gian khoảng 20 phút. Kết quả chạy thật của đúng kịch bản này (2026-10-07): `docs/ket-qua-demo-sprint-2.md`.
>
> Mỗi lần bấm **Phân tích** tốn khoảng 0,0007 USD (2 lời gọi `gpt-4o-mini`). Cả kịch bản ≈ 0,01 USD.

## Lưu ý về shell

Lệnh dưới đây viết cho **PowerShell**. Các khác biệt với Git Bash xem `docs/demo-sprint-1.md` mục "Lưu ý về shell". Hai
điểm riêng của Sprint 2:

| Vấn đề | Cách làm |
|---|---|
| `-Dspring-boot.run.profiles=web` không nằm trong nháy | PowerShell cắt tại dấu chấm → `Unknown lifecycle phase ".run.profiles=web"`. **Luôn đặt `-D...` trong nháy kép** |
| Máy để JDK 26 làm mặc định | Chạy qua `.\build.ps1` — script tự chọn JDK 21 chỉ trong phiên đó |

---

## Chuẩn bị (10 phút trước demo)

```powershell
cd D:\HASON\2025.2\VIN\OJT\PROJECT\call-analysis-assistant
docker compose up -d elasticsearch
curl.exe -s http://localhost:9200/_cluster/health
```

Đợi `"status":"green"` hoặc `"yellow"`. Signaling phải đã nạp (Sprint 1, phần 0) — nếu chưa:

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample"
```

Ra **1 059 document / 20 cuộc gọi**. Kiểm file `.env` có `OPENAI_API_KEY` (mẫu ở `.env.example`). Rồi bật server:

```powershell
.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web"
```

Cổng 8080 bận thì thêm `"-Dspring-boot.run.arguments=--server.port=8089"`. Mở `http://localhost:8080/`.

**Chuẩn bị sẵn trong Explorer** các thư mục dưới đây để kéo thả file (tiết kiệm thời gian):

| Tắt | Thư mục trong `..\ai20k_sample\` |
|---|---|
| `EE129C8F` | `success\EE129C8F-EAD0-4302-AB68-920D32F8B8B7` |
| `271D1FAF` | `for_test\271D1FAF-26D4-4150-AC3F-9204505BD84B` |
| `1B009D42` | `fail\1B009D42-49CD-479E-B26C-3A2994AEB720` |
| `2D9057AA` | `fail\2D9057AA-C496-48B2-946A-98FA2896D086` |
| `703100CF` | `fail\703100CF-5742-467E-9E0E-34E45F60FF58` |
| `DE7DD314` | `success\DE7DD314-F432-45CB-BCB4-AE9103CC0919` |

**Thao tác trên web**, giống nhau ở mọi scenario: gõ câu hỏi vào ô *"Bạn muốn biết gì về cuộc gọi này?"* → kéo thả
file vào khung (hoặc bấm *chọn file*) → **Phân tích** (hoặc Ctrl + Enter). Report hiện ở cột phải theo mẫu MVP 4.5.
Không đính kèm `signaling.json` — signaling lấy từ Elasticsearch theo Call-ID.

---

## Phần 0 — Đường đi của một câu hỏi (1 phút, nói trước khi bấm)

Mở trang kiến trúc (https://claude.ai/artifact/HCaMCcVZVH537JwqEJpQp4) hoặc nói ngắn:

> Câu hỏi → **Request Parser** (AI hiểu intent và trọng tâm; Call-ID lấy bằng regex) → **File Validator** →
> pipeline Sprint 1 (timeline, chỉ số, rule, evidence) → **Sanitizer** làm sạch → **AI** viết tóm tắt / phân tích / đề
> xuất → **Guardrails** G01-G05 → hỏng thì **Fallback** về rule → kiểm **schema** → render theo mẫu 4.5.

Điểm cần nhấn: **kết luận, chỉ số, evidence do code tính**; AI chỉ viết chữ và bị kiểm (MVP 3.2).

---

## Scenario 1 — Cuộc gọi bình thường: `EE129C8F` (2 phút)

**MVP mong:** `SUCCESS`, không cờ chất lượng.

- Câu hỏi: `Phân tích giúp mình cuộc gọi này`
- File: cả 4 file log của `EE129C8F` (`caller_endcall.log`, `calleer_webrtc.log`, `callee_endcall.log`, `callee_webrtc.log`)

| Chỗ cần chỉ | Giải thích |
|---|---|
| `Kết luận: SUCCESS` · `Cờ chất lượng: Không` · `HIGH` | Đúng scenario 1 |
| Giới hạn dữ liệu: *"File calleer_webrtc.log được gán lại từ CALLEE sang CALLER"* | Tên file gõ sai trong data gốc. Hệ thống gán leg theo **nội dung** (vai SDP offer/answer), không theo tên — bẫy #18 |
| Dòng *Phân tích* có `13530 ms`, `36307 ms` | Số do AI viết nhưng **chép nguyên văn** từ bảng chỉ số; viết khác đi một chữ số là G05 chặn |

---

## Scenario 2 — Chất lượng kém: `271D1FAF` (2,5 phút)

**MVP mong:** `SUCCESS` + cờ chất lượng, `NETWORK_PACKET_LOSS`.

- Câu hỏi: `Vì sao bên gọi nghe bị giật?`
- File: cả 4 file log của `271D1FAF`

| Chỗ cần chỉ | Giải thích |
|---|---|
| `Cờ chất lượng: Có - NETWORK_PACKET_LOSS` | Rule quét **chuỗi stats từng giây**, không chỉ summary lúc kết thúc (bẫy #3): caller 18/80 mẫu mất gói > 5 %, cao nhất 11.32 % |
| `Độ tin cậy: LOW` + giới hạn *"Ngưỡng phát hiện chất lượng kém CHƯA kiểm chứng được"* | Data có nhãn không có cuộc chất lượng kém nào → ngưỡng là phỏng đoán, hệ thống **tự nói ra** thay vì giả vờ chắc chắn |
| Tóm tắt nhắc *"Bên gọi nghe bị giật"* | Request Parser tách được **trọng tâm** câu hỏi và AI trả lời đúng điều được hỏi |

**Lưu ý:** `271D1FAF` nằm trong `for_test/` — không có nhãn, **không dùng để hiệu chỉnh** rule hay prompt.
Data có nhãn không có ca nào cho scenario này (câu hỏi mở: cần cuộc gọi chất lượng kém có nhãn).

---

## Scenario 3 — Lỗi thiết lập: `1B009D42` (2 phút)

**MVP mong:** `FAIL`, `SIGNALING_FAILURE`.

- Câu hỏi: `Vì sao gọi không được?`
- File: `caller_endcall.log`, `caller_webrtc.log` của `1B009D42` (cuộc này chỉ có log bên gọi)

| Chỗ cần chỉ | Giải thích |
|---|---|
| `FAIL` · `SIGNALING_FAILURE` · `HIGH` | Đúng scenario 3 |
| Tóm tắt nêu `428 call.outgoing.error.privacy_restricted` | Mã đọc **nguyên văn từ log**, không viết cứng theo loại lỗi |
| Giới hạn: *"Thiếu end call log của callee"*, *"Thiếu WebRTC log của callee"* | Mỗi file thiếu được nêu tên riêng |

---

## Scenario 4 — Lỗi media: `2D9057AA` và `703100CF` (4 phút)

**MVP mong:** `FAIL`, `ICE_FAILURE` hoặc `TURN_FAILURE`.

### 4a — `2D9057AA`, ca quan trọng nhất

- Câu hỏi: `Vì sao bên nhận không nghe được gì?`
- File: `callee_endcall.log`, `callee_webrtc.log` của `2D9057AA`

Nhắc lại Sprint 1: signaling của cuộc này **hoàn hảo** (có BYE, `PAIR_PING` đều) nhưng ground truth là FAIL. Report ra
`FAIL · ICE_FAILURE`; phân tích trả lời đúng trọng tâm *"bên nhận không nghe được"*: ICE chuyển `checking → failed`, không
một byte audio nào. Độ tin cậy MEDIUM vì thiếu log của caller — có ghi ở "Giới hạn dữ liệu".

### 4b — `703100CF`, TURN hỏng thật

- Câu hỏi: `Cuộc gọi này lỗi gì?`
- File: `caller_endcall.log`, `caller_webrtc.log` của `703100CF`

Report ra `FAIL · TURN_FAILURE`. Chỉ phần *Chính*: không có lần `allocate requested successfully` nào trong cả file. Nói
thêm (bẫy #7): dòng `Received TURN allocate error response` có ở cả cuộc thành công — đếm nó sẽ báo nhầm 11/20 cuộc.

**Điểm yếu để chủ động nói:** đề xuất của AI ở ca này có câu *"Kiểm tra cấu hình TURN server"*, trong khi log cho thấy
**chưa request nào rời máy** (`Failed to create TURN client socket`). Đề xuất của rule
nói đúng điều đó (*"Chưa có request nào tới được TURN server … chưa thể kết luận gì về TURN server hay tường lửa"*). Đây là việc T2 của
Sprint 3: giữ đề xuất của rule, AI chỉ bổ sung (README, mục Known Limitations).

---

## Scenario 5 — Thiếu dữ liệu: `DE7DD314` (2 phút)

**MVP mong:** `UNKNOWN`, nêu rõ file thiếu.

### 5a — chỉ một file

- Câu hỏi: `Phân tích giúp mình cuộc gọi này`
- File: chỉ `caller_endcall.log` của `DE7DD314`

Report ra `SUCCESS · MEDIUM`, không phải `UNKNOWN`. "Giới hạn dữ liệu" nêu đủ: thiếu end call log của callee, thiếu WebRTC
log cả hai bên, bản export signaling bị cắt bớt.

**Lưu ý:** đây là chỗ **lệch MVP có chủ đích, chưa chốt** (câu hỏi mở). Ground truth của `5E0800AE` — cuộc chỉ
có một file — là `SUCCESS`, mâu thuẫn với scenario 5. Hệ thống đang chọn: còn đủ căn cứ media (end call log có byte audio)
thì vẫn kết luận, nhưng hạ tin cậy tối đa MEDIUM và liệt kê file thiếu.

### 5b — không đính kèm file nào

- Câu hỏi: `Phân tích cuộc gọi DE7DD314-F432-45CB-BCB4-AE9103CC0919`
- File: không có

Report ra `UNKNOWN · LOW`: signaling đạt `OK_ACK_OK` nhưng không có log client để kiểm media — *"signaling đẹp chưa đủ để
kết luận"* (bài học từ `2D9057AA`). Call-ID trích bằng regex từ câu hỏi.

---

## Scenario 6 — Nhất quán + dữ liệu nhạy cảm: `2D9057AA` (3 phút)

**MVP mong:** cùng verdict qua 3 cách hỏi; AI request không chứa secret; report / log đã làm sạch.

File cho cả ba lần: `callee_endcall.log`, `callee_webrtc.log` của `2D9057AA` (hai file này có **176 dòng chứa IPv4 thật**).

| Lần | Câu hỏi | Mong |
|---|---|---|
| 1 | `Cuộc gọi này có thành công không?` | `FAIL · ICE_FAILURE · MEDIUM` |
| 2 | `kiem tra cuoc goi nay giup minh` (không dấu) | như trên |
| 3 | `Cuộc gọi từ máy 0912345678 (IP 113.161.42.7) bị lỗi, phân tích giúp mình` | như trên |

Câu 4a cũng dùng đúng hai file này — tính cả nó là bốn cách hỏi cùng một kết luận.

**Chỉ chỗ đã làm sạch:**

1. Report trên web: không có số điện thoại, không có IP nào. Mở DevTools → Network → phản hồi `/api/analyze` để cho thấy
   cả JSON cũng sạch.
2. **Đúng thứ gửi cho AI**, xem bằng lệnh không gọi AI, không tốn tiền:

   ```powershell
   .\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--ai-context=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086 '--question=Cuoc goi tu may 0912345678 (IP 113.161.42.7) bi loi'"
   ```

   Dòng đầu in câu hỏi đã làm sạch: `Cuoc goi tu may [CONTACT_REDACTED] (IP [IP_REDACTED]) bi loi`. Chạy thử
   2026-10-06: cả 258 dòng output không còn địa chỉ IPv4 nào, dù hai file log có 176 dòng chứa IP.
3. Log của server (cửa sổ đang chạy `spring-boot:run`): dòng log AI chỉ gồm các trường MVP 6.2 cho phép, không có nội dung.
4. Số đo cả tập (benchmark `dev`, 315 lần chạy): **0/7 005** giá trị nhạy cảm gốc lọt vào dữ liệu gửi AI, 0 trong phản hồi.

Có thể hỏi thêm dạng liệt kê `Máy 0912345678 gọi lỗi, token eyJ…, IP 113.161.42.7, xem giúp` — vẫn ra cùng kết luận
(kiểm 2026-10-07). **Nếu thử bằng curl thay vì web:** curl trên Windows làm hỏng chữ có dấu trong tham số dòng lệnh, câu
hỏi tới server thành `M?y … g?i l?i` và có thể bị xếp ngoài phạm vi. Gửi câu từ file UTF-8: `-F "message=<cau-hoi.txt"`
(`docs/ket-qua-demo-sprint-2.md` mục 3).

Log mẫu không chứa JWT hay số điện thoại; ca JWT / phone / email / URL có token nằm trong test S01-S08
(`SensitiveDataSanitizerTest`) và chạy được bằng:

```powershell
.\build.ps1 -q test "-Dtest=SensitiveDataSanitizerTest"
```

---

## Phần thêm — Câu ngoài phạm vi (30 giây)

- Câu hỏi: `Viết giúp mình một email xin nghỉ phép`
- File: `caller_endcall.log` của `DE7DD314`

Trả về câu từ chối **viết sẵn trong code**, không phải chữ AI — giống hệt nhau mọi lần, không thể lỡ trả lời luôn. Không
chạy pipeline phân tích, nên nhanh (≈ 0,7 s).

---

## Nếu có sự cố trong lúc demo

| Triệu chứng | Nguyên nhân thường gặp | Xử lý |
|---|---|---|
| Mọi report ra `UNKNOWN`, giới hạn nói không lấy được signaling | ES tắt hoặc chưa nạp | `docker compose up -d elasticsearch`, nạp lại signaling |
| Banner vàng *"Report dựng từ rule vì AI không dùng được"* | Không có key, hết hạn mức, mạng lỗi, hoặc Guardrails chặn | Đây chính là **Fallback** — kết luận vẫn đúng, độ tin cậy tối đa MEDIUM. Lý do ghi ngay trong banner và ở dòng log `fallback_reason` |
| Trang báo file bị loại | File quá 20 MB, rỗng, không nhận diện được, hoặc end call log khác Call-ID | Đúng thiết kế (MVP F01-F04): file lỗi bị loại riêng, các file còn lại vẫn được phân tích |
| `Unknown lifecycle phase ".run.profiles=web"` | `-D...` không nằm trong nháy | Đặt trong nháy kép |
