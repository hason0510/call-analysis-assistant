# Bẫy dữ liệu

Những chỗ dữ liệu mẫu (`ai20k_sample`, `ai20k_new_dataset`) dễ đọc sai, mỗi bẫy kèm bằng chứng đo trên data và cách
code xử lý. Các tài liệu khác trích theo số hiệu ("bẫy #13"). Số hiệu cố định: bẫy mới thêm vào cuối, không đánh lại.

Nguồn: mục "Bẫy data" của ghi chú làm việc của dự án (chép ra đây ngày 2026-10-19 vì ghi chú đó không nằm trong repo).

1. **`split("\t", -1)`** — thiếu `-1` thì Java xoá hết cột rỗng cuối dòng. End call log đầy cột rỗng → lệch cột hàng loạt mà không có exception nào.
2. **Số hiệu `#Hn` KHÔNG cố định giữa các file — tra loại bản ghi theo cột `#tag`.** Chỉ `H1` (`info`), `H2` (`log_detail`), `H3` (`send_cmd`/`recv_cmd`) là cố định. Còn lại đánh theo những loại bản ghi file đó có: `stats` là H5/H6/H7, `endcall` (summary) là H5/H7/H8/H9. Chỉ 2/16 file có đủ 9 header như `sample.md`.
   Map cứng theo số từng biến 359 dòng stats của caller `DE7DD314` thành `ICE_CANDIDATE` và coi dòng `config` là "chỉ số cuối", nên MOS caller ra `N/A` dù có đủ dữ liệu. Summary `endcall` có ở **16/16** file; MOS/loss/RTT có giá trị thật ở **8 leg / 5 cuộc gọi** (các leg còn lại chưa từng có media).
3. **Summary (`endcall`) chỉ chụp lúc kết thúc.** Muốn thấy suy giảm thoáng qua phải quét chuỗi `stats`. Ví dụ `EE129C8F` có loss cuối = 0 nhưng từng lên 3,7%.
   Summary **trùng đúng mẫu stats cuối** (8/8 leg có media). Và `audio.packetLostPercent` là tỉ lệ mất gói **trong khoảng** giữa hai lần đo (khớp 996/996 mẫu), nên **đừng dùng nó làm packet loss của cuộc gọi** — summary ghi 0 % ở cả 8 leg. Packet loss cả cuộc = `audio.packetsLost ÷ (packetsLost + packetsReceived)`: hai trường này là bộ đếm cộng dồn. Cách phân biệt: trường có đuôi `Pts` là giá trị trong khoảng (`packetsReceivedPts` = hiệu của `packetsReceived`, 996/996). MOS / RTT / jitter không có bộ đếm, chỉ có giá trị lúc kết thúc.
4. **`transport.rttMs` là giá trị TÍCH LUỸ**, không phải RTT hiện tại (8385 khi RTT thật là 63). Dùng `transport.currentRttMs`.
5. **WebRTC timestamp `[giây:mili]` có trường giây độ rộng BIẾN THIÊN** — từ `[000:000]` tới `[12844:xxx]`. Regex `\d{3}` bỏ sót phần lớn dữ liệu, phải dùng `\d+`.
   Đã chứng minh KHÔNG phải `[PID:TID]`: trường sau có đúng 1000 giá trị (0-999); trường đầu chỉ là hằng số ở 2/27 file; `A×1000+B` đơn điệu ở 23/27 file; đối chiếu với đồng hồ tuyệt đối cho tỉ lệ 1,021.
   `[NNN]` **là thread ID** — cả 6 chỗ lùi thời gian trên 27 file đều lùi 1-5 ms và đều ở ranh giới đổi `[NNN]`.
6. **WebRTC log có dòng nối tiếp** (1 649/26 695 dòng, 6,2%) — phải gộp vào record trước, bỏ qua là mất dữ liệu. Con số "22% (5 966 dòng)" ghi trước đây SAI: nó được đếm bằng regex `\d{3}`, nên mọi dòng có trường giây ≥ 1000 bị tính nhầm là dòng nối tiếp (đếm lại bằng `\d{3}` ra 5 949 = 22,3%; bằng `\d+` ra 1 649 = 6,2%). Chính bẫy #5 ở trên, lần này mắc ở tài liệu chứ không ở parser — parser dùng `\d+` và sinh đúng 100% record.
   iOS còn có **hai dạng con**: log C++ thì ngoặc đầu là nguồn thật; log Objective-C đi qua `RTCLogging.mm:34` thì nguồn thật ở ngoặc THỨ HAI (691 dòng, gồm `RTCAudioSession`/`RTCRtpSender`).
7. **`Received TURN allocate error response` KHÔNG phải lỗi** — là bắt tay xác thực chuẩn của TURN (RFC 8656, dùng cơ chế long-term credential của STUN RFC 8489): request đầu không kèm credential → server trả `401` + nonce → client gửi lại → thành công.
   Bằng chứng trên data: toàn tập có **73 error-response và đúng 73 allocate-successfully**, và **0 cổng nào có lỗi mà không phục hồi** (22 file). Ngay cả `fail/2D9057AA` cũng 3 lỗi → 3 thành công.
   Đếm dòng này để kết luận `TURN_FAILURE` sẽ **báo nhầm 11/20 cuộc gọi**, gồm cả 7/7 cuộc thuộc `success/` (con số 17/20 ghi trước đây không tái tạo được).
   TURN hỏng THẬT có dấu hiệu khác: cả file không có một lần `allocate requested successfully` nào — 3 ca CANCEL trong `fail/` (`703100CF`, `7B56D7AD`, `E9D6C112`), nay là `TURN_FAILURE` VALIDATED.
   Trong file TURN hỏng, **phân kiểu bằng hai con số**: số `TURN allocate request sent` và số phản hồi (`allocate error response`). 0 request + `Failed to create TURN client socket` = chưa request nào rời máy (`703100CF`); có `Failed to send TURN message` mà 0 phản hồi = lỗi ngay trên máy (`7B56D7AD`); có request mà 0 phản hồi = không ai trả lời (`E9D6C112`). Đề xuất "kiểm tra credential / cổng 3478" chỉ hợp với kiểu cuối — xem `TurnFailure`.
   Nhiễu tương tự: `Received TURN probe error response`, `TURN probe … timeout` (5/13 file WebRTC của `success/`), `Failed to send TURN message` đứng riêng (`success/70A1F889`: `error: 101` khi gửi `TURN REFRESH` lúc đổi Wi-Fi sang 4G), `failed_networks = 0`, `level=WARN` trong signaling (223/1059 event).
   VPN `tun0` chỉ có ở 3 cuộc, và cả 3 **cùng một máy** (cùng `deviceId`) — n = 1, chỉ nêu như dữ kiện, không viết thành nguyên nhân.
   → Hai nguyên tắc: **(a)** trước khi coi một chuỗi là tín hiệu lỗi, grep nó trên `../ai20k_sample/success/`; **(b)** với sự kiện có vòng đời (allocate/connect/handshake), đừng đếm lỗi đơn lẻ — kiểm xem có sự kiện thành công theo sau trên **cùng đối tượng** không. Lỗi rồi phục hồi là bình thường.
8. **Đơn vị lệch nhau:** `_emitBye ... duration: 26` là **giây**; `endCall.duration = 35597` ở summary `endcall` là **mili giây**.
9. **`codeReason` 480/487 ("Bạn đã huỷ") có ở cuộc gọi THÀNH CÔNG.** Chỉ `419` mới chắc chắn là lỗi.
10. **Signaling không có trường `leg` và không có trường text nào.** Leg phải suy ra từ cột `role` của `#H1`, hoặc `appUserId` của `INIT_CALL` đầu tiên.
11. **Tín hiệu hỏng mạnh nhất là `audio.bytesReceived = 0`**, không phải MOS — MOS bằng 0 mơ hồ giữa "đo được và bằng 0" với "không đo được".
12. **Cuộc `2D9057AA` có signaling hoàn hảo nhưng ground truth là FAIL** (ICE failed, 0 byte audio). Rule chỉ dựa vào signaling sẽ sai case này. Vì vậy `RuleVerdictEngine` xét media TRƯỚC khi được phép kết luận SUCCESS.
13. **Một lần gửi sinh NHIỀU dòng log.** `EE129C8F` có 10 sự kiện `cmd=INVITE` nhưng chỉ 1 `requestId` và 4 cụm thời gian cách nhau 0,5s / 1,0s / 2,0s — exponential backoff. Đếm sự kiện ra 9 lần gửi lại thay vì 3. Phải gom cụm theo ngưỡng **250 ms** (đo 451 khoảng cách, không cái nào rơi vào dải 100-500 ms).
14. **Trừ thời gian ở độ chính xác NANO rồi mới cắt về mili.** Cắt từng `Instant` trước khi trừ làm lệch 1 ms trên 5/20 cuộc gọi. Dùng `Duration.between()`.
15. **Dedupe ở mức FILE, không mức dòng.** libwebrtc ghi `TLS server done` ba lần trong cùng mili giây — đó là bản ghi thật. Dedupe mức dòng từng xoá mất 92 event. Và khi hai file trùng nội dung thì giữ tên **đúng quy ước** (`caller_/callee_ + _endcall/_webrtc.log`), không giữ tên đứng trước theo bảng chữ cái.
16. **Trạng thái ICE báo là "đạt được", không phải "cuối cùng".** Cuộc gọi thành công vẫn kết thúc ở `disconnected` khi người dùng cúp máy — báo giá trị cuối sẽ gây hiểu nhầm.
17. **`transport.hasMediaPoor` / `audio.bytesReceived` mạnh hơn MOS.** MOS bằng 0 mơ hồ giữa "đo được và bằng 0" với "không đo được"; `bytesReceived = 0` thì dứt khoát.
18. **Gán leg cho WebRTC log: tin vai SDP trước, nền tảng sau.** `DoSetLocalDescription: offer` = caller, `answer` = callee (22/27 file có dòng này, khớp 100% với vai DTLS server/client). So nền tảng với end call log chỉ là dự phòng cho file không có SDP: nó giả định leg kia khác nền tảng, và từng gán nhầm `C8CF631E` (callee_webrtc → caller) và `0EC7B700` (caller_webrtc → callee) khi chỉ một bên có end call log mà hai bên cùng iOS. Đừng thay dự phòng bằng "tin tên file": `D114749E/callee_webrtc.log` thật ra do máy caller ghi (cùng deviceId với `1B009D42`, hai lần gọi cách nhau 8,085 s ở cả hai đồng hồ).
19. **Signaling không có "số dòng" — trích dẫn là `signaling#N`, không phải `signaling.json:N`.** Signaling đến từ Elasticsearch, không từ file; N là thứ tự sự kiện (`ordinal + 1`, tức vị trí trong mảng `events` của bản export). Dạng cũ `signaling.json:87` bị đọc thành "dòng 87 của file": với `2D9057AA`, dòng 87 là `"service": …`, còn `BYE` được trích nằm ở dòng 1 128. Log đính kèm vẫn dùng `tên file:số dòng` (`SourceRef.citation`).
20. **`signaling.json` có HAI định dạng export.** `ai20k_new_dataset` trộn bản gọn (`callId`, `events[].cmd`) với bản thô
   (`call_id`, `logs[].grpcMethod`, `service.name`, `log.level`, không có `truncated` → suy từ `returned < total_matching`).
   `grpcMethod` cùng bộ giá trị với `cmd`. Bản thô có `message` (chữ tự do) và `clientIp` (IP thật) — `SignalingJsonReader`
   cố ý bỏ, đừng nạp vào ES mà không qua Sanitizer. Từ `message` chỉ lấy CỜ có kiểu `noSessionsFound`
   (`SignalingMessages`, lúc import) cho chỉ số "Số lần No sessions found"; bản gọn không có `message` → chỉ số `N/A`.
21. **Một leg có thể có NHIỀU thiết bị** (`fc3187b4`: 2 end call log callee). Mọi máy cùng đổ chuông, một máy bắt máy,
   máy kia dừng với `_emitFailed … another_device.accepted … code: 490` — bình thường, không phải lỗi. Lấy summary "đầu
   tiên" của leg là lấy máy KHÔNG bắt máy (dừng sớm hơn) → chỉ số callee N/A và AI kết luận "callee không nhận được âm
   thanh". Chỉ số lấy từ máy có âm thanh (`CallTimeline.audioFile`); evidence gắn nhãn thiết bị; Giới hạn dữ liệu nêu máy kia.
22. **Cuộc gọi rớt GIỮA CHỪNG vẫn là ICE_FAILURE** (taxonomy MVP 4.2 không có loại riêng). `FE4EF5E7`: nói ~30 s, máy callee
   mất mạng, callee cúp máy nhưng BYE không tới server, caller nghe im lặng ~8 s rồi cúp, `hasMediaFail=1` chỉ bật SAU đó.
   Lý do của rule phải nói "đã connected, đã có âm thanh, sau đó mất kết nối" — câu cũ "hasMediaFail bật cờ" đứng cạnh định
   nghĩa "media không thiết lập được" làm AI viết ngược evidence. `signal=network_lost originator=remote` là bản ghi app ghi
   trong END CALL log, KHÔNG phải lệnh signaling và không có trong `signaling.json`; 0 cuộc success/ có nó — mới dùng để giải
   thích, chưa dùng để kết luận. Nó KHÔNG chứng minh bên kia mất mạng: app báo nó ~7 s sau khi hết dữ liệu từ bên kia, và ở
   `70C388AD` máy bên kia vẫn ping server thêm 22 s. Viết "không nhận được dữ liệu từ bên kia", đừng viết "bên kia mất mạng".
23. **Call-ID trong ES và end call log viết HOA, tên thư mục `ai20k_new_dataset` viết thường.** `callId` trong ES là
   `keyword` (so đúng từng ký tự): truy vấn bằng Call-ID chữ thường ra 0 event, và File Validator từng loại mọi end call
   log vì "lệch Call-ID" → mọi case `UNKNOWN`. Web UI không gặp (Call-ID lấy từ log), CLI theo tên thư mục và runner
   theo `call_id` của file case thì gặp. Chuẩn hoá bằng `CallIdExtractor.normalize` ở `FileValidator` (mọi luồng phân
   tích đều qua) và `BenchmarkCaseLoader` (runner truy vấn ES trước khi phân tích); so tên thư mục thì `equalsIgnoreCase`.
