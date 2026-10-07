# Kết quả chạy demo Sprint 2

> Chạy ngày **2026-10-07** theo đúng `docs/demo-sprint-2.md`, qua Chat API `POST /api/analyze` của server profile `web`,
> AI thật `gpt-4o-mini`. Cùng đường đi với nút **Phân tích** trên web UI — web chỉ gọi đúng endpoint này.
> Câu hỏi gửi lên bằng **UTF-8 đúng** (đọc từ file, như trình duyệt gửi). 11 request, chi phí ≈ 0,007 USD.
> Phản hồi JSON đầy đủ lưu ngoài repo ở `../eval-scratch/demo2/` (có Call-ID thật, không commit).
>
> Lần chạy đầu (2026-10-06) **bị loại**: câu hỏi gửi bằng curl trong Git Bash và bị hỏng chữ trên đường gửi (mục 3).

## 1. Tổng hợp theo 6 scenario của MVP mục 10

| # | Scenario | Cuộc gọi · file đính kèm | MVP mong | Kết quả | Đạt |
|---|---|---|---|---|---|
| 1 | Bình thường | `EE129C8F` · 4 file | `SUCCESS`, không cờ | `SUCCESS` · không cờ · HIGH | ✅ |
| 2 | Chất lượng kém | `271D1FAF` · 4 file | `SUCCESS` + cờ, `NETWORK_PACKET_LOSS` | `SUCCESS` · cờ `NETWORK_PACKET_LOSS` · LOW | ✅ (ca không nhãn, xem 2.2) |
| 3 | Lỗi thiết lập | `1B009D42` · 2 file caller | `FAIL`, `SIGNALING_FAILURE` | `FAIL` · `SIGNALING_FAILURE` · HIGH | ✅ |
| 4 | Lỗi media | `2D9057AA` · 2 file callee | `FAIL`, `ICE_FAILURE`/`TURN_FAILURE` | `FAIL` · `ICE_FAILURE` · MEDIUM | ✅ |
| 4 | Lỗi media | `703100CF` · 2 file caller | như trên | `FAIL` · `TURN_FAILURE` · MEDIUM | ✅ |
| 5 | Thiếu dữ liệu | `DE7DD314` · chỉ `caller_endcall.log` | `UNKNOWN`, nêu file thiếu | `SUCCESS` · MEDIUM · nêu đủ file thiếu | ⚠️ lệch có chủ đích, chưa chốt |
| 5 | Thiếu dữ liệu | `DE7DD314` · không file, Call-ID trong câu | như trên | `UNKNOWN` · LOW | ✅ |
| 6 | Nhất quán + dữ liệu nhạy cảm | `2D9057AA` · 4 cách hỏi, một câu chứa phone + JWT + IP | cùng verdict, đã làm sạch | 4/4 `FAIL · ICE_FAILURE · MEDIUM`; không lộ dữ liệu | ✅ |
| — | Ngoài phạm vi | `Viết giúp mình một email xin nghỉ phép` | từ chối | câu từ chối cố định | ✅ |

- **Intent đúng 11/11 câu.** Ba câu có trọng tâm tách được trọng tâm đúng chữ người dùng.
- Mọi report có `analysisSource = AI`; không report nào phải lùi về rule; không report nào cần kiểm tra (AI và rule thống
  nhất ở cả 10 report).
- Không phản hồi nào chứa số điện thoại, JWT hay địa chỉ IPv4 (tìm chuỗi trên JSON thô).

**Thời gian phản hồi:** report 2,9-7,5 s (trung vị 4,2 s); câu `OUT_OF_SCOPE` 0,7 s vì dừng ngay sau Request Parser.

| Request | s1 | s2 | s3 | s4a | s4b | s5a | s5b | s6a | s6b | s6c | s7 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Giây | 7,55 | 4,66 | 2,93 | 4,18 | 2,86 | 5,95 | 3,72 | 4,16 | 4,39 | 3,93 | 0,70 |

## 2. Chi tiết từng scenario

### 2.1. Scenario 1 — `EE129C8F`

Câu hỏi: *"Phân tích giúp mình cuộc gọi này"* → `ANALYZE_CALL`.

- Tóm tắt: *"Cuộc gọi đã được thiết lập thành công và kết thúc bình thường. Không có dấu hiệu lỗi media trong quá trình gọi."*
- Phân tích nêu `13530 ms` (thiết lập), `36307 ms` (kết nối), MOS hai bên — đều nguyên văn bảng chỉ số.
- Giới hạn dữ liệu (4 dòng) có: *"File calleer_webrtc.log được gán lại từ CALLEE sang CALLER (đối chiếu theo nội dung, không
  theo tên file)"* — tên file gõ sai trong data gốc được xử lý đúng.
- Trích 12/21 evidence làm căn cứ.

### 2.2. Scenario 2 — `271D1FAF`

Câu hỏi: *"Vì sao bên gọi nghe bị giật?"* → `ANALYZE_WITH_FOCUS`, trọng tâm *"bên gọi nghe bị giật"*.

- Chính (do rule viết): *"NETWORK_PACKET_LOSS — caller có 18/80 mẫu stats mất gói > 5 % (cao nhất 11.32 %); callee có 3/80
  mẫu stats mất gói > 5 % (cao nhất 7.55 %). Đối chiếu: MOS thấp nhất 4.10477 (caller), app không bật hasMediaPoor ở mẫu nào"*.
- Tóm tắt trả lời đúng trọng tâm: *"… bên gọi gặp vấn đề về chất lượng do mất gói cao."*
- Độ tin cậy LOW; giới hạn dữ liệu ghi ngưỡng chất lượng kém **chưa kiểm chứng được** trên data có nhãn.
- `271D1FAF` thuộc `for_test/`: không nhãn, không dùng hiệu chỉnh. Data có nhãn không có ca nào cho scenario 2.

**Phần chữ AI gán số sai nghĩa (Guardrails không bắt được).** Phân tích viết *"chỉ số mất gói của caller là 11.3208%"*.
`11.3208%` là tỉ lệ mất gói của **một mẫu stats** (cao nhất); mất gói **cả cuộc** của caller là `1.83 %` (bảng chỉ số).
Lần chạy 2026-10-06 có lỗi cùng kiểu với MOS: gọi MOS lúc kết thúc (`4.15346`) là "MOS thấp nhất" (thật ra `4.10477`).
G05 cho qua vì các số đó có thật trong context — G05 kiểm **số có nguồn**, không kiểm **số được gán đúng nghĩa**.
Hướng xử lý: Sprint 3 T1(a), AI trích mã chỉ số `{Mxx}`, code điền cả tên lẫn giá trị.

### 2.3. Scenario 3 — `1B009D42`

Câu hỏi: *"Vì sao gọi không được?"* → `ANALYZE_WITH_FOCUS`, trọng tâm *"gọi không được"*.

- Tóm tắt: *"Cuộc gọi không thiết lập được do server từ chối INIT_CALL với mã 428 call.outgoing.error.privacy_restricted.
  Cuộc gọi bị chặn trước khi tới callee."*
- Giới hạn dữ liệu nêu riêng: mã 428 là server từ chối theo chính sách, taxonomy chưa có category riêng (câu hỏi
  mở); thiếu end call log và WebRTC log của callee.
- Đề xuất của AI: *"Kiểm tra lại chính sách bảo mật của server…"* (mơ hồ), *"Xem xét các thiết lập quyền riêng tư của
  người dùng…"* (đúng hướng: người nhận chặn cuộc gọi).

### 2.4. Scenario 4 — `2D9057AA` và `703100CF`

**4a.** Câu hỏi: *"Vì sao bên nhận không nghe được gì?"* → trọng tâm *"bên nhận không nghe được"*.

- Tóm tắt: *"Cuộc gọi không thiết lập được đường truyền media. Bên nhận không nghe được gì do ICE chuyển sang failed và
  không bao giờ đạt connected."* — trả lời đúng điều được hỏi.
- MEDIUM vì thiếu log của caller (ghi ở giới hạn dữ liệu). Signaling của cuộc này hoàn hảo; kết luận đến từ media.
- Đề xuất *"Xem xét sử dụng TURN server…"* thừa: trong hệ thống này relay qua TURN đã là đường mặc định.

**4b.** Câu hỏi: *"Cuộc gọi này lỗi gì?"*

- Tóm tắt: *"Cuộc gọi không thiết lập được do không cấp phát được relay trên TURN server. Không có candidate để gửi INVITE."*
- Phân tích dẫn đúng EV07: socket TURN không tạo được.
- **Đề xuất của AI sai hướng:** *"Kiểm tra cấu hình TURN server để đảm bảo nó hoạt động bình thường."* Log cho thấy
  **chưa request nào rời máy**, nên chưa thể nói gì về TURN server. Đề xuất của rule cho đúng cuộc này (`--analyze`) nói
  đúng điều đó: *"Chưa có request nào tới được TURN server: WebRTC không tạo được socket TURN …, nên chưa thể kết luận gì
  về TURN server hay tường lửa."* Lỗi lặp lại ở cả hai lần chạy và trùng nhận xét khi so rule với AI trên `for_test/`
  → Sprint 3 T2(a): giữ đề xuất của rule, AI chỉ bổ sung.

### 2.5. Scenario 5 — `DE7DD314`

**5a — chỉ `caller_endcall.log`.** `SUCCESS · MEDIUM`. Giới hạn dữ liệu (4 dòng): bản export signaling bị cắt bớt; thiếu
end call log của callee; thiếu WebRTC log của cả hai bên.

MVP mong `UNKNOWN`. Hệ thống chủ ý ra `SUCCESS` hạ tin cậy vì ground truth của `5E0800AE` (cuộc chỉ có một file) là
`SUCCESS` — hai nguồn của đề bài mâu thuẫn nhau; câu hỏi mở, chưa chốt.

**5b — không file, Call-ID trong câu.** `UNKNOWN · LOW`: *"Signaling đạt OK_ACK_OK nhưng không có dữ liệu media để kiểm
chứng."* Call-ID trích bằng regex từ câu hỏi.

### 2.6. Scenario 6 — `2D9057AA`, cùng hai file callee

| Câu hỏi | Intent | Kết quả | Trích evidence |
|---|---|---|---|
| (4a) *Vì sao bên nhận không nghe được gì?* | `ANALYZE_WITH_FOCUS` | `FAIL · ICE_FAILURE · MEDIUM` | 4/13 |
| *Cuộc gọi này có thành công không?* | `ANALYZE_CALL` | `FAIL · ICE_FAILURE · MEDIUM` | 5/13 |
| *kiem tra cuoc goi nay giup minh* | `ANALYZE_CALL` | `FAIL · ICE_FAILURE · MEDIUM` | 13/13 |
| *Máy 0912345678 gọi lỗi, token eyJ…, IP 113.161.42.7, xem giúp* | `ANALYZE_CALL` | `FAIL · ICE_FAILURE · MEDIUM` | 4/13 |

Bốn cách hỏi cho cùng kết luận, cùng category, cùng độ tin cậy. Phần chữ thì khác nhau (số evidence được trích dao động
4-13) — kết luận ổn định, phần chữ thì không, đúng như đo ở benchmark.

**Làm sạch.**
- Hai file log có 176 dòng chứa IPv4 thật; không phản hồi nào chứa IPv4.
- Câu s6c: report không chứa số điện thoại, JWT hay IP của câu hỏi.
- Lệnh `--ai-context` (không gọi AI) trên cùng cuộc gọi, câu có số điện thoại và IP: câu hỏi gửi AI thành *"Cuoc goi tu may
  [CONTACT_REDACTED] (IP [IP_REDACTED]) bi loi"*, cả 258 dòng output không còn IPv4 nào.
- Số đo cả tập ở `docs/evaluation-metrics.md`: 0/7 005 giá trị gốc tới AI.

### 2.7. Ngoài phạm vi

*"Viết giúp mình một email xin nghỉ phép"* → `OUT_OF_SCOPE`, câu từ chối viết sẵn trong code, 0,7 s.

## 3. Lần chạy đầu bị loại: curl làm hỏng chữ có dấu

Lần chạy 2026-10-06 gửi câu hỏi bằng `curl -F "message=…"` trong Git Bash. Kết luận, category, chỉ số khớp lần chạy này,
nhưng câu s6c ra `OUT_OF_SCOPE`, và 4 request dò sau đó (dạng `Máy <phone> gọi lỗi, token / IP …, xem giúp`) cũng vậy.
Giả thuyết đầu tiên — AI hiểu nhầm nhãn che `[IP_REDACTED]` / `[REDACTED]` là chủ đề câu hỏi — **sai**. Kiểm ngày 2026-10-07:

| Phép kiểm | Kết quả | Kết luận |
|---|---|---|
| Đường lui từ khoá (không AI) trên các câu lỗi | đều `ANALYZE_CALL` | `OUT_OF_SCOPE` do AI chọn |
| Runner (`--evaluate`, câu đọc từ YAML UTF-8), **prompt không sửa**, 3 câu lỗi × 3 lần | **9/9 `ANALYZE_CALL`** | Prompt phân loại đúng các câu này |
| Gửi lại bằng curl như hôm trước | 3/3 `OUT_OF_SCOPE`; AI nhận nhiều hơn ~7 token so với runner | Câu đi qua curl đã khác |
| Bắt nguyên văn byte curl gửi đi | `M\xe1y … g?i l?i … xem gi\xfap` | curl đổi sang bảng mã ANSI: `á` thành 1 byte Latin-1, `ọ` / `ỗ` thành `?` |
| Gửi lại, câu đọc từ file UTF-8 (`-F "message=<file.txt"`) | 3/3 `FAIL · ICE_FAILURE`, số token khớp runner (928 / 920 / 921) | Lỗi biến mất khi chữ đúng |

**Kết luận:** không phải lỗi prompt hay Sanitizer; **không sửa prompt**. Trình duyệt luôn gửi UTF-8, nên người dùng web
không gặp. Thử Chat API bằng curl trên Windows thì gửi câu có dấu từ file UTF-8, hoặc gõ không dấu — cùng bẫy với tham số
`--question` của CLI (`CLAUDE.md`).

**Ca hồi quy thêm vào `benchmark/intent-check.yaml`:** 5 câu liệt kê có giá trị nhạy cảm (mong trong phạm vi), 3 câu chỉ nhờ
tra cứu giá trị (mong `OUT_OF_SCOPE`), và 3 câu gốc của lần chạy hỏng. Mốc: 22/23 trên bộ kiểm, 9/9 trên 3 câu gốc. Câu sai
duy nhất: *"sdt [CONTACT_REDACTED] email [CONTACT_REDACTED] goi loi"* → `OUT_OF_SCOPE` (không dấu, rất cụt); một lần sai
chưa đủ để sửa prompt, ghi ở Known Limitations của README.

## 4. Tóm tắt

| | |
|---|---|
| Scenario đạt | 1, 2, 3, 4, 5b, 6, ngoài phạm vi — intent đúng 11/11 câu |
| Lệch có chủ đích, chưa chốt | 5a: `SUCCESS` hạ tin cậy thay vì `UNKNOWN` |
| Điểm yếu phần chữ AI | Gán số sai nghĩa: mất gói một mẫu gọi là mất gói của caller (2.2); đề xuất TURN sai hướng (2.4); đề xuất 428 mơ hồ (2.3) — để Sprint 3 T1(a), T2(a) |
| Rò rỉ dữ liệu | 0 trong mọi phản hồi |
