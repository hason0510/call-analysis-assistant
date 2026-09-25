# Kết quả demo Sprint 1 — 5 cuộc gọi

> Deliverable theo MVP mục 5.3: *"Demo tối thiểu 5 cuộc gọi (chạy bằng CLI hoặc test,
> chưa cần UI)."*

Toàn bộ output dưới đây là **kết quả chạy thật**, chụp lại ngày 2026-09-24 (chạy lại sau khi
sửa parser end call log, thêm rule `TURN_FAILURE`, quy tắc N/A cho leg chưa nhận gói audio,
nêu rõ mã lỗi 428 / 421 trong lý do kết luận, xác định leg của file WebRTC bằng vai SDP, đưa
dòng căn cứ của kết luận vào evidence, và trích dẫn signaling dạng `signaling#N` (sự kiện thứ N,
không phải dòng N của file) — chi tiết ở đầu
[`ket-qua-for-test.md`](ket-qua-for-test.md)),
không chỉnh sửa bằng tay. Kịch bản trình bày trực tiếp nằm ở [`demo-sprint-1.md`](demo-sprint-1.md); tài
liệu này là **bằng chứng đã chạy** để mentor đọc được mà không cần dựng môi trường.

## Môi trường khi chụp

| | |
|---|---|
| Java | 21.0.10 (ép qua `build.ps1`) |
| Elasticsearch | 8.18.8 trong Docker, index `signaling-events` |
| Số document | **1 059** / 20 cuộc gọi |
| Unit test | **162 pass**, ~0,4 giây, không cần Elasticsearch |

Lệnh dựng lại:

```powershell
docker compose up -d elasticsearch
```
```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample"
```

---

> **Tài liệu liên quan:** [`ket-qua-for-test.md`](ket-qua-for-test.md) là bản báo cáo
> riêng cho 7 cuộc trong `for_test/` — mentor yêu cầu chạy và báo cáo kết quả trên tập
> **không có nhãn**. Hai tài liệu phục vụ hai mục đích khác nhau: tài liệu này **trình bày
> năng lực** (chọn ca để minh hoạ từng tính năng), tài liệu kia là **phép đo mù** (chạy
> hết, báo nguyên kết quả, không chọn lọc).

## Tổng hợp 5 cuộc gọi

| # | Call-ID | Nhóm | Kết luận | Chứng minh điều gì |
|---|---|---|---|---|
| 1 | `DE7DD314` | success | SUCCESS | Cuộc gọi bình thường, đủ 3 nguồn log |
| 2 | `1B009D42` | fail | FAIL | Server từ chối ngay ở `INIT_CALL` (mã 428) — quy tắc `N/A` |
| 3 | `2D9057AA` | fail | FAIL | **Ca quan trọng nhất** — signaling hoàn hảo nhưng vẫn FAIL |
| 4 | `EE129C8F` | success | SUCCESS | File đặt sai tên, gán lại leg theo nội dung |
| 5 | `9B556E56` | for_test | FAIL | File sai **loại**, nhận diện theo nội dung |

---

## 1. `DE7DD314` — cuộc gọi bình thường

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/success/DE7DD314-F432-45CB-BCB4-AE9103CC0919"
```

**Điểm cần chỉ cho mentor:**

- Đủ cả 3 nguồn log, ICE hai bên đều `checking => connected`.
- `Thời lượng kết nối = 361311 ms` **khớp** với `_emitBye ... duration: 361` trong log
  client (đơn vị **giây**) — hai nguồn độc lập xác nhận lẫn nhau, đồng thời minh hoạ bẫy
  đơn vị giữa summary `endcall` (mili giây) và `_emitBye` (giây).
- `Bên kết thúc = CALLEE` khớp với `originator: 1` (*bên kia cúp*) trong
  `caller_endcall.log`.
- **Độ tin cậy `MEDIUM` chứ không `HIGH`**, vì bản export signaling trả về 200/201 event.
  Xem dòng đầu mục *Giới hạn dữ liệu*.

```text
# Báo cáo phân tích cuộc gọi
Call-ID: DE7DD314-F432-45CB-BCB4-AE9103CC0919
Kết luận: SUCCESS
Cờ chất lượng: Không
Độ tin cậy: MEDIUM
Tóm tắt: Cuộc gọi thiết lập và kết thúc bình thường. Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#9] Signaling INVITE từ caller
3. [EV03][signaling#18] Signaling TRYING từ callee
4. [EV04][signaling#19] Signaling RINGING từ callee
5. [EV05][signaling#24] Signaling OK từ callee
6. [EV06][signaling#27] Signaling OK_ACK_OK từ caller
7. [EV07][callee_endcall.log:609] Chỉ số media callee: MOS=4.42201, loss=0%, mediaFail=0, bytesRecv=1357527
8. [EV08][caller_endcall.log:603] Chỉ số media caller: MOS=4.42397, loss=0%, mediaFail=0, bytesRecv=1373626
9. [EV09][callee_endcall.log:613] Call summary callee: MOS=4.42201, loss=0%, RTT=63ms, bytesRecv=1357527
10. [EV10][caller_endcall.log:605] Call summary caller: MOS=4.42397, loss=0%, RTT=54ms, bytesRecv=1373626
11. [EV11][caller_endcall.log:607] Kết thúc phía client: _emitBye with originator: 1 duration: 361 codeReason: 480 failReason: Bạn đã huỷ
12. [EV12][signaling#192] Signaling BYE từ callee
13. [EV13][callee_webrtc.log:511] ICE chuyển new => checking
14. [EV14][callee_webrtc.log:778] ICE chuyển new => checking
15. [EV15][callee_webrtc.log:1109] ICE chuyển checking => connected
16. [EV16][callee_webrtc.log:1166] ICE chuyển checking => connected
17. [EV17][caller_webrtc.log:559] ICE chuyển new => checking
18. [EV18][caller_webrtc.log:635] ICE chuyển new => checking
19. [EV19][caller_webrtc.log:826] ICE chuyển checking => connected
20. [EV20][caller_webrtc.log:947] ICE chuyển checking => connected
21. [EV21][caller_webrtc.log:2561] ICE chuyển connected => disconnected

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | 8581 ms                                              | SIGNALING |
| Thời gian với tới callee                 | 2991 ms                                              | SIGNALING |
| Số lần gửi lại INVITE                    | 3 lần                                                | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | 4749 ms                                              | SIGNALING |
| Thời lượng kết nối                       | 361311 ms                                            | SIGNALING |
| Bên kết thúc                             | CALLEE                                               | SIGNALING |
| Số lần gửi lại BYE                       | 1 lần                                                | SIGNALING |
| MOS (caller)                             | 4.42397                                              | ENDCALL   |
| Packet loss (caller)                     | 0 %                                                  | ENDCALL   |
| RTT (caller)                             | 54 ms                                                | ENDCALL   |
| Jitter (caller)                          | 6 ms                                                 | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | 5044 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (caller)            | MOBIFONE / AS131429 / VN                             | SIGNALING |
| MOS (callee)                             | 4.42201                                              | ENDCALL   |
| Packet loss (callee)                     | 0 %                                                  | ENDCALL   |
| RTT (callee)                             | 63 ms                                                | ENDCALL   |
| Jitter (callee)                          | 10 ms                                                | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | 5049 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (callee)            | FPT / AS18403 / VN                                   | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 17 ms, trung vị 16 ms (4 mẫu)                    | SIGNALING |
| Số WARN theo service                     | 13 (S46TQW3OJDW=3, SCYZPESAVDK=10)                   | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: không xác định

## Đề xuất
- Không cần hành động thêm.

## Giới hạn dữ liệu
- Bản export signaling bị cắt bớt, có thể thiếu sự kiện
- callee_webrtc.log: 2803 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- caller_webrtc.log: 2473 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

## 2. `1B009D42` — server từ chối ngay ở `INIT_CALL`

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/fail/1B009D42-49CD-479E-B26C-3A2994AEB720"
```

Signaling chỉ có `INIT_CALL`. End call log của caller cho biết lý do: ACK của `INIT_CALL` trả
`callErrorCode: 428`, key `call.outgoing.error.privacy_restricted` — *"Người này hiện chưa thể
nhận cuộc gọi"*. Đây là server từ chối theo chính sách, không phải lỗi mạng; taxonomy MVP
mục 4.2 không có category riêng nên report xếp tạm `SIGNALING_FAILURE` và ghi rõ điều đó.

Kết luận trace được về dòng log gốc: evidence có `[EV02][caller_endcall.log:11]` (ACK của
`INIT_CALL` kèm `callErrorCode: 428`) và `[EV04][caller_endcall.log:17]` (dòng `_emitFailed` app tự
ghi, `code: 428`). Mã 428 trong câu tóm tắt được đọc từ chính dòng ACK đó, không suy ra.

**Điểm cần chỉ cho mentor:** đây là bằng chứng rõ nhất cho **quy tắc `N/A`** của MVP mục 4.3
(*"Chỉ số không có trong dữ liệu phải hiển thị `N/A` kèm lý do, KHÔNG được mặc định về 0"*).

Chú ý mỗi dòng `N/A` có **lý do khác nhau**, không dùng một câu chung chung:

| Lý do | Nghĩa là gì |
|---|---|
| `cuộc gọi không đạt tới OK_ACK_OK` | Cuộc gọi chết trước mốc đó |
| `không nhận được gói audio nào (audio.packetsReceived = 0)` | Có file và có summary, nhưng leg chưa từng có media — app ghi 0 vào chỗ trống, report không lặp lại số 0 đó |
| `không có end call log của callee` | Không có file của bên đó |
| `signaling không có trường text để đếm thông báo này` | Data không hỗ trợ chỉ số này |

Ba cái đầu là *"không đo được"*, khác hẳn *"đo được và bằng 0"*. Nếu mặc định về `0` thì
report sẽ nói dối rằng cuộc gọi thiết lập trong 0 ms.

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 1B009D42-49CD-479E-B26C-3A2994AEB720
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: SIGNALING_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. Server từ chối INIT_CALL (mã 428 call.outgoing.error.privacy_restricted): cuộc gọi bị chặn trước khi tới callee.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][caller_endcall.log:11] Server trả ACK cho INIT_CALL kèm callErrorCode: 428 call.outgoing.error.privacy_restricted
3. [EV03][caller_endcall.log:15] Call summary caller: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
4. [EV04][caller_endcall.log:17] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.privacy_restricted endReason: 0 code: 428 open:false

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Thời gian với tới callee                 | N/A (cuộc gọi không đạt tới INVITE)                  | SIGNALING |
| Số lần gửi lại INVITE                    | N/A (cuộc gọi không có lệnh INVITE)                  | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | N/A (cuộc gọi không đạt tới RINGING)                 | SIGNALING |
| Thời lượng kết nối                       | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Bên kết thúc                             | N/A (cuộc gọi không có BYE)                          | SIGNALING |
| Số lần gửi lại BYE                       | N/A (cuộc gọi không có lệnh BYE)                     | SIGNALING |
| MOS (caller)                             | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Packet loss (caller)                     | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| RTT (caller)                             | N/A (chưa có phản hồi STUN nào (transport.localStunResponse = 0) nên không đo được RTT; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Jitter (caller)                          | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | N/A (WebRTC log không ghi chuyển trạng thái ICE nào) | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | N/A (cuộc gọi không có PAIR_PING nào của caller)     | SIGNALING |
| ISP / ASN / quốc gia (caller)            | MOBIFONE / AS131429 / VN                             | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 6 ms, trung vị 5 ms (4 mẫu)                      | SIGNALING |
| Số WARN theo service                     | 2 (S46TQW3OJDW=1, SCYZPESAVDK=1)                     | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: SIGNALING_FAILURE — Cuộc gọi không thiết lập được ở tầng signaling: server chưa từng gọi tới callee, hoặc phiên bị huỷ/từ chối trước khi hai bên bắt tay xong.
- Khả dĩ khác: Điểm mơ hồ: Mã 428 privacy_restricted là server TỪ CHỐI theo chính sách của người nhận, không phải lỗi mạng; taxonomy MVP mục 4.2 không có category riêng nên xếp tạm vào đây — cần mentor xác nhận. Với CANCEL: 703100CF (cuộc CANCEL duy nhất của tập có nhãn có end call log) ghi `_waitingCandidateTimer with error` rồi `_emitFailed ... code: 421`, tức app TỰ huỷ khi hết `waitingCandidateTimeout` (6000 ms trong config của app); 7B56D7AD và E9D6C112 không có end call log nên chưa thấy trực tiếp lý do huỷ. Nếu TURN không cấp phát được thì xếp TURN_FAILURE, còn TURN tốt mà vẫn hết giờ thì lỗi nằm ở phía client.

## Đề xuất
- Kiểm tra log của service signaling quanh thời điểm INIT_CALL.
- Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.
- Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.

## Giới hạn dữ liệu
- Mã 428 call.outgoing.error.privacy_restricted là server từ chối theo chính sách, không phải lỗi mạng; taxonomy chưa có category riêng nên xếp tạm vào SIGNALING_FAILURE
- Thiếu end call log của callee — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của callee — không kiểm chứng được sự kiện ICE / TURN phía đó
- caller_webrtc.log: 6 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

## 3. `2D9057AA` — ca quan trọng nhất

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"
```

**Vì sao đây là ca đáng nói nhất:** nhìn riêng signaling thì cuộc gọi này **hoàn hảo** —
có `OK_ACK_OK`, có `BYE`, `PAIR_PING` hai bên đều đặn sau khi bắt tay. Ground truth lại là **FAIL**.

Bằng chứng nằm ở nguồn khác:

- `ICE chuyển checking => failed`, **không bao giờ** đạt `connected`
- `audio.bytesReceived = 0` — không một byte thoại nào đi qua
- `codeReason: 419` + `originator: 2` (*hệ thống tự ngắt*)

> Rule chỉ xét signaling sẽ kết luận SUCCESS và **sai ngay trên tập dev**. Đó là lý do
> `RuleVerdictEngine` xét media **TRƯỚC** khi được phép kết luận SUCCESS — thứ tự các
> nhánh là có chủ đích, không phải ngẫu nhiên.

Mục *Giới hạn dữ liệu* nêu đích danh hai thứ còn thiếu: **end call log** và **WebRTC log
của caller**. Kết luận ICE_FAILURE ở đây chỉ dựa trên log phía callee, và report nói thẳng
điều đó thay vì để người đọc tưởng đã xem đủ hai phía.

Cũng lưu ý `Khoảng trống PAIR_PING` được gắn nhãn **`[proxy]`** (MVP mục 4.3 bắt buộc):
PAIR_PING là nhịp tim của tầng *signaling* — nó **gợi ý** chứ không **chứng minh** tình trạng
media. Đọc kỹ signaling còn thấy thêm: PAIR_PING của **caller ngừng ở giây ~24,8** (18 giây sau
`OK_ACK_OK`), sau đó chỉ còn callee ping, và `BYE` của callee bị gửi lại 5 lần không có
`ACK_BYE`. Chỉ số "khoảng trống lớn nhất" chỉ đo **giữa hai lần ping** nên không bắt được
khoảng im lặng từ lần ping cuối tới hết cuộc gọi — một giới hạn của chỉ số proxy này.

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 2D9057AA-C496-48B2-946A-98FA2896D086
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: ICE_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. ICE chuyển sang failed và không bao giờ đạt connected.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#10] Signaling INVITE từ caller
3. [EV03][signaling#12] Signaling RINGING từ callee
4. [EV04][signaling#17] Signaling OK từ callee
5. [EV05][signaling#22] Signaling OK_ACK_OK từ caller
6. [EV06][callee_endcall.log:180] Chỉ số media callee: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=1 — MOS/loss/RTT không đo được
7. [EV07][callee_endcall.log:185] Call summary callee: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=1 — MOS/loss/RTT không đo được
8. [EV08][callee_endcall.log:187] Kết thúc phía client: _emitBye with originator: 2 duration: 26 codeReason: 419 failReason: Vui lòng kiểm tra kết nối mạng
9. [EV09][signaling#87] Signaling BYE từ callee
10. [EV10][callee_webrtc.log:285] ICE chuyển new => checking
11. [EV11][callee_webrtc.log:511] ICE chuyển new => checking
12. [EV12][callee_webrtc.log:902] ICE chuyển checking => failed
13. [EV13][callee_webrtc.log:903] ICE chuyển checking => failed

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | 6774 ms                                              | SIGNALING |
| Thời gian với tới callee                 | N/A (cuộc gọi không đạt tới TRYING)                  | SIGNALING |
| Số lần gửi lại INVITE                    | 1 lần                                                | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | 5565 ms                                              | SIGNALING |
| Thời lượng kết nối                       | 33892 ms                                             | SIGNALING |
| Bên kết thúc                             | CALLEE                                               | SIGNALING |
| Số lần gửi lại BYE                       | 4 lần                                                | SIGNALING |
| MOS (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Packet loss (caller)                     | N/A (không có end call log của caller)               | ENDCALL   |
| RTT (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Jitter (caller)                          | N/A (không có end call log của caller)               | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | N/A (không có WebRTC log của caller)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | 1019 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (caller)            | AS45903 / AS45903 / VN                               | SIGNALING |
| MOS (callee)                             | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Packet loss (callee)                     | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| RTT (callee)                             | N/A (chưa có phản hồi STUN nào (transport.localStunResponse = 0) nên không đo được RTT; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Jitter (callee)                          | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | failed                                               | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | 4447 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (callee)            | AS45903 / AS45903 / VN                               | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 10 ms, trung vị 6 ms (4 mẫu)                     | SIGNALING |
| Số WARN theo service                     | 9 (SCYZPESAVDK=9)                                    | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: ICE_FAILURE — Signaling bắt tay xong nhưng đường truyền media không thiết lập được: ICE chuyển sang failed và không bao giờ đạt connected.
- Khả dĩ khác: Điểm mơ hồ: Timeout ICE mặc định khoảng 15 giây; cuộc gọi bị cúp máy trước mốc đó sẽ không kịp sinh trạng thái failed dù media thật sự không chạy.

## Đề xuất
- Kiểm tra kết nối mạng của bên bị lỗi tại thời điểm cuộc gọi.
- Đối chiếu danh sách ICE candidate hai bên xem có cặp nào khả dĩ không.
- Xác nhận TURN server có cấp phát được relay không.

## Giới hạn dữ liệu
- Thiếu end call log của caller — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của caller — không kiểm chứng được sự kiện ICE / TURN phía đó
- callee_webrtc.log: 960 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

## 4. `EE129C8F` — file đặt sai tên

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/success/EE129C8F-EAD0-4302-AB68-920D32F8B8B7"
```

Thư mục này có file tên **`calleer_webrtc.log`** — thừa một chữ `e`. Tên gợi ý là của
*callee*, nhưng chính nội dung file cho biết vai: dòng 80 ghi `DoSetLocalDescription: offer`,
tức thiết bị này tạo SDP offer, nên đó là log của **caller** (file `callee_webrtc.log` thì ghi
`answer`). `LegCorrelator` tin vai SDP trước, đối chiếu nền tảng với end call log chỉ là dự phòng.

Hệ thống gán lại leg theo **nội dung**, và ghi việc đó vào *Giới hạn dữ liệu* thay vì âm
thầm sửa:

```text
- File calleer_webrtc.log được gán lại từ CALLEE sang CALLER (đối chiếu theo nội dung, không theo tên file)
```

Xem thêm bằng lệnh `--timeline`:

```text
Call-ID : EE129C8F-EAD0-4302-AB68-920D32F8B8B7
Tổng sự kiện: 2715  (main track 405, relative 2310)
Leg suy ra từ: FIRST_INIT_CALL

--- MAIN TRACK (12 sự kiện đầu) ---
2026-09-21T08:21:28.236Z         ENDCALL    CALLER  CALL_METADATA          caller_endcall.log:9
2026-09-21T08:21:28.237Z         ENDCALL    CALLER  init                   caller_endcall.log:10
2026-09-21T08:21:28.237Z         ENDCALL    CALLER  _emitOpening           caller_endcall.log:11
2026-09-21T08:21:28.237Z         ENDCALL    CALLER  _emitOpening           caller_endcall.log:12
2026-09-21T08:21:28.237Z         ENDCALL    CALLER  INIT_CALL              caller_endcall.log:13
2026-09-21T08:21:28.747Z         ENDCALL    CALLER  INIT_CALL              caller_endcall.log:14
2026-09-21T08:21:28.768692800Z   SIGNALING  CALLER  INIT_CALL              signaling#1
2026-09-21T08:21:28.770099704Z   SIGNALING  CALLER  INIT_CALL              signaling#2
2026-09-21T08:21:28.771270463Z   SIGNALING  CALLER  INIT_CALL              signaling#3
2026-09-21T08:21:28.773855375Z   SIGNALING  CALLER  INIT_CALL              signaling#4
2026-09-21T08:21:28.774994634Z   SIGNALING  CALLER  INIT_CALL              signaling#5
2026-09-21T08:21:28.776022328Z   SIGNALING  CALLER  INIT_CALL              signaling#6

--- RELATIVE TRACKS ---
callee_webrtc.log        leg=CALLEE  platform=ios      độ tin cậy=MATCHED_BY_SDP_ROLE  (1293 sự kiện)
calleer_webrtc.log       leg=CALLER  platform=android  độ tin cậy=MATCHED_BY_SDP_ROLE  (1017 sự kiện)

--- LỆCH ĐỒNG HỒ ---
CALLER : 532 ms (trung vị trên 19 cặp)
CALLEE : 79 ms (trung vị trên 14 cặp)

--- GHI CHÚ (5) ---
[LEG_UNCERTAIN] File calleer_webrtc.log được gán lại từ CALLEE sang CALLER (đối chiếu theo nội dung, không theo tên file)
[RELATIVE_TRACK] callee_webrtc.log: 1293 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
[RELATIVE_TRACK] calleer_webrtc.log: 1017 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
[CLOCK_OFFSET] Lệch đồng hồ CALLER so với server: 532 ms (trung vị trên 19 cặp, không đáng kể)
[CLOCK_OFFSET] Lệch đồng hồ CALLEE so với server: 79 ms (trung vị trên 14 cặp, không đáng kể)
```

Hai ghi chú `CLOCK_OFFSET` cho thấy nguyên tắc **đo và ghi lại, KHÔNG viết lại timestamp**:
lệch 532 ms và 79 ms đều dưới ngưỡng nên đánh dấu *không đáng kể*, nhưng vẫn được nêu ra.

```text
# Báo cáo phân tích cuộc gọi
Call-ID: EE129C8F-EAD0-4302-AB68-920D32F8B8B7
Kết luận: SUCCESS
Cờ chất lượng: Không
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi thiết lập và kết thúc bình thường. Đã đạt OK_ACK_OK, kết thúc bằng BYE, không có dấu hiệu lỗi media.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#80] Signaling INVITE từ caller
3. [EV03][signaling#86] Signaling TRYING từ callee
4. [EV04][signaling#89] Signaling RINGING từ callee
5. [EV05][signaling#97] Signaling OK từ callee
6. [EV06][signaling#100] Signaling OK_ACK_OK từ caller
7. [EV07][caller_endcall.log:151] Chỉ số media caller: MOS=4.42133, loss=0%, mediaFail=0, bytesRecv=106969
8. [EV08][caller_endcall.log:153] Call summary caller: MOS=4.42133, loss=0%, RTT=80ms, bytesRecv=106969
9. [EV09][caller_endcall.log:155] Kết thúc phía client: _emitBye with originator: 1 duration: 36 codeReason: 480 failReason: Bạn đã huỷ
10. [EV10][callee_endcall.log:133] Chỉ số media callee: MOS=4.41127, loss=0%, mediaFail=0, bytesRecv=104835
11. [EV11][callee_endcall.log:138] Call summary callee: MOS=4.41127, loss=0%, RTT=69ms, bytesRecv=104835
12. [EV12][signaling#120] Signaling BYE từ callee
13. [EV13][callee_webrtc.log:381] ICE chuyển new => checking
14. [EV14][callee_webrtc.log:622] ICE chuyển new => checking
15. [EV15][callee_webrtc.log:930] ICE chuyển checking => connected
16. [EV16][callee_webrtc.log:997] ICE chuyển checking => connected
17. [EV17][calleer_webrtc.log:346] ICE chuyển new => checking
18. [EV18][calleer_webrtc.log:398] ICE chuyển new => checking
19. [EV19][calleer_webrtc.log:550] ICE chuyển checking => connected
20. [EV20][calleer_webrtc.log:670] ICE chuyển checking => connected
21. [EV21][calleer_webrtc.log:961] ICE chuyển connected => disconnected

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | 13530 ms                                             | SIGNALING |
| Thời gian với tới callee                 | 1505 ms                                              | SIGNALING |
| Số lần gửi lại INVITE                    | 3 lần                                                | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | 5987 ms                                              | SIGNALING |
| Thời lượng kết nối                       | 36307 ms                                             | SIGNALING |
| Bên kết thúc                             | CALLEE                                               | SIGNALING |
| Số lần gửi lại BYE                       | 1 lần                                                | SIGNALING |
| MOS (caller)                             | 4.42133                                              | ENDCALL   |
| Packet loss (caller)                     | 0 %                                                  | ENDCALL   |
| RTT (caller)                             | 80 ms                                                | ENDCALL   |
| Jitter (caller)                          | 5 ms                                                 | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | 6066 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (caller)            | VIETTEL / AS38731 / VN                               | SIGNALING |
| MOS (callee)                             | 4.41127                                              | ENDCALL   |
| Packet loss (callee)                     | 0 %                                                  | ENDCALL   |
| RTT (callee)                             | 69 ms                                                | ENDCALL   |
| Jitter (callee)                          | 18 ms                                                | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | 5048 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (callee)            | MOBIFONE / AS131429 / VN                             | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 86 ms, trung vị 10 ms (34 mẫu)                   | SIGNALING |
| Số WARN theo service                     | 9 (S46TQW3OJDW=2, SCYZPESAVDK=7)                     | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: không xác định

## Đề xuất
- Không cần hành động thêm.

## Giới hạn dữ liệu
- File calleer_webrtc.log được gán lại từ CALLEE sang CALLER (đối chiếu theo nội dung, không theo tên file)
- callee_webrtc.log: 1293 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- calleer_webrtc.log: 1017 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

## 5. `9B556E56` — file sai **loại**, không chỉ sai tên

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/for_test/9B556E56-24D3-43BA-853D-7972AD009865"
```

Thư mục này chỉ có 2 file: `signaling.json` và `caller_webrtc.log`.

**Nhưng `caller_webrtc.log` KHÔNG phải WebRTC log.** Nội dung thật của nó bắt đầu bằng:

```text
#H1	#ts	#tag	appUserId	callId	callMode	callType	callUserId	duration	end	...
#H2	#ts	#tag	msg	status	type
```

Đó là **end call log**. Đây là ca mạnh hơn `EE129C8F`: không chỉ sai *bên*, mà sai hẳn
*loại file*. `FileTypeDetector` nhận đúng theo nội dung, nên 22 sự kiện được parse bằng
`EndCallLogParser` và đi vào main track với giờ tuyệt đối — thay vì bị `WebRtcLogParser`
đọc hỏng hoặc bị bỏ qua.

Mục *Giới hạn dữ liệu* vì vậy nêu đúng hai thứ còn thiếu — **end call log của callee** và
**WebRTC log của cả hai bên** — theo đúng tinh thần ví dụ `"- Thiếu callee_webrtc.log."`
trong mẫu MVP mục 4.5. Câu chữ nói *"WebRTC log của callee"* chứ không khẳng định một tên
file cụ thể, vì chính thư mục này chứng minh tên file không đáng tin.

> Đây chính là ca kiểm thử **F02** mà MVP mục 6.4 yêu cầu (*"Tên file không khớp nội dung"*),
> và nó có sẵn trong data mẫu chứ không phải ca dựng lên.

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 9B556E56-24D3-43BA-853D-7972AD009865
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: SIGNALING_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. Server từ chối INIT_CALL (mã 428 call.outgoing.error.privacy_restricted): cuộc gọi bị chặn trước khi tới callee.

## Evidence chính
1. [EV01][caller_webrtc.log:11] Server trả ACK cho INIT_CALL kèm callErrorCode: 428 call.outgoing.error.privacy_restricted
2. [EV02][caller_webrtc.log:15] Call summary caller: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
3. [EV03][caller_webrtc.log:17] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.privacy_restricted endReason: 0 code: 428 open:false
4. [EV04][signaling#1] Signaling INIT_CALL từ caller

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Thời gian với tới callee                 | N/A (cuộc gọi không đạt tới INVITE)                  | SIGNALING |
| Số lần gửi lại INVITE                    | N/A (cuộc gọi không có lệnh INVITE)                  | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | N/A (cuộc gọi không đạt tới RINGING)                 | SIGNALING |
| Thời lượng kết nối                       | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Bên kết thúc                             | N/A (cuộc gọi không có BYE)                          | SIGNALING |
| Số lần gửi lại BYE                       | N/A (cuộc gọi không có lệnh BYE)                     | SIGNALING |
| MOS (caller)                             | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Packet loss (caller)                     | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| RTT (caller)                             | N/A (chưa có phản hồi STUN nào (transport.localStunResponse = 0) nên không đo được RTT; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Jitter (caller)                          | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | N/A (không có WebRTC log của caller)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | N/A (cuộc gọi không có PAIR_PING nào của caller)     | SIGNALING |
| ISP / ASN / quốc gia (caller)            | VIETTEL / AS38731 / VN                               | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 9 ms, trung vị 8 ms (4 mẫu)                      | SIGNALING |
| Số WARN theo service                     | 2 (S46TQW3OJDW=1, SCYZPESAVDK=1)                     | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: SIGNALING_FAILURE — Cuộc gọi không thiết lập được ở tầng signaling: server chưa từng gọi tới callee, hoặc phiên bị huỷ/từ chối trước khi hai bên bắt tay xong.
- Khả dĩ khác: Điểm mơ hồ: Mã 428 privacy_restricted là server TỪ CHỐI theo chính sách của người nhận, không phải lỗi mạng; taxonomy MVP mục 4.2 không có category riêng nên xếp tạm vào đây — cần mentor xác nhận. Với CANCEL: 703100CF (cuộc CANCEL duy nhất của tập có nhãn có end call log) ghi `_waitingCandidateTimer with error` rồi `_emitFailed ... code: 421`, tức app TỰ huỷ khi hết `waitingCandidateTimeout` (6000 ms trong config của app); 7B56D7AD và E9D6C112 không có end call log nên chưa thấy trực tiếp lý do huỷ. Nếu TURN không cấp phát được thì xếp TURN_FAILURE, còn TURN tốt mà vẫn hết giờ thì lỗi nằm ở phía client.

## Đề xuất
- Kiểm tra log của service signaling quanh thời điểm INIT_CALL.
- Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.
- Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.

## Giới hạn dữ liệu
- Mã 428 call.outgoing.error.privacy_restricted là server từ chối theo chính sách, không phải lỗi mạng; taxonomy chưa có category riêng nên xếp tạm vào SIGNALING_FAILURE
- Thiếu end call log của callee — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của cả hai bên — không kiểm chứng được sự kiện ICE / TURN

Report hợp lệ theo schema v1: CÓ
```

---

## 6. Chạy toàn bộ và đối chiếu ground truth

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample"
```

Cột `GR.TRUTH` lấy từ tên thư mục `fail/` và `success/` — đó là **ground truth duy nhất**
mentor cung cấp. Thư mục `for_test/` không có nhãn nên bị loại khỏi phép đo, hiển thị
`(không)`.

Cột `KHỚP` và con số accuracy được **tính bằng code**, không đếm tay.

```text
NHÓM       CALL-ID    KẾT LUẬN  GR.TRUTH  KHỚP     VẤN ĐỀ               TIN CẬY  EV    SCHEMA
----------------------------------------------------------------------------------------------------
fail       1B009D42   FAIL      FAIL      OK       SIGNALING_FAILURE    HIGH     2     OK
fail       2D9057AA   FAIL      FAIL      OK       ICE_FAILURE          HIGH     13    OK
fail       703100CF   FAIL      FAIL      OK       TURN_FAILURE         HIGH     3     OK
fail       7B56D7AD   FAIL      FAIL      OK       TURN_FAILURE         MEDIUM   2     OK
fail       D114749E   FAIL      FAIL      OK       SIGNALING_FAILURE    HIGH     2     OK
fail       E9D6C112   FAIL      FAIL      OK       TURN_FAILURE         MEDIUM   2     OK
for_test   0A6C2821   FAIL      (không)   -        TURN_FAILURE         HIGH     3     OK
for_test   0EC7B700   FAIL      (không)   -        SIGNALING_FAILURE    HIGH     6     OK
for_test   271D1FAF   SUCCESS   (không)   -        NETWORK_PACKET_LOSS  LOW      20    OK
for_test   311A9B6A   FAIL      (không)   -        SIGNALING_FAILURE    HIGH     3     OK
for_test   45AA3011   FAIL      (không)   -        TURN_FAILURE         HIGH     3     OK
for_test   9B556E56   FAIL      (không)   -        SIGNALING_FAILURE    HIGH     2     OK
for_test   AA9791CE   FAIL      (không)   -        TURN_FAILURE         MEDIUM   2     OK
success    5E0800AE   SUCCESS   SUCCESS   OK       -                    MEDIUM   11    OK
success    6A7CE985   SUCCESS   SUCCESS   OK       -                    MEDIUM   15    OK
success    70A1F889   SUCCESS   SUCCESS   OK       -                    HIGH     18    OK
success    C8CF631E   SUCCESS   SUCCESS   OK       -                    HIGH     17    OK
success    DE7DD314   SUCCESS   SUCCESS   OK       -                    MEDIUM   21    OK
success    EE129C8F   SUCCESS   SUCCESS   OK       -                    HIGH     21    OK
success    F3D7914B   SUCCESS   SUCCESS   OK       -                    MEDIUM   16    OK
----------------------------------------------------------------------------------------------------
Verdict Accuracy         : 13/13 = 100,0%  (chỉ tính cuộc gọi có nhãn)
Report hợp lệ theo schema: 20/20
Cuộc gọi không có nhãn   : 7
```

---

## 7. Đối chiếu với acceptance criteria của MVP mục 5.1

| Yêu cầu | Kết quả đo được |
|---|---|
| Parse ≥ 90% log hợp lệ | **29 238 dòng → 28 522 event, 1 cảnh báo, 0 file không nhận diện** |
| Timeline đúng thứ tự, xử lý duplicate | Dedupe mức file + đo lệch đồng hồ, thứ tự tất định |
| Invalid input không crash pipeline | Parser trả `ParseWarning` thay vì throw |
| Chỉ số khớp tính tay ≥ 5 cuộc gọi | **9 cuộc gọi** — xem [`kiem-thu-tay.md`](kiem-thu-tay.md) |
| Unit test đầy đủ cho core logic | **162 test**, không cần Elasticsearch |

Ngoài acceptance criteria:

| | |
|---|---|
| Verdict Accuracy | **13/13 = 100%** trên toàn bộ data có nhãn |
| Report hợp lệ theo schema v1 | **20/20** |

---

## 8. Điều nên nói thẳng với mentor

Hai mục đầu **chặn Sprint 2**, mục thứ ba đã được anh giải đáp:

1. **Ground truth chỉ có verdict**, suy từ tên thư mục. Thiếu `issueCategory` và evidence
   → Sprint 2 không đo được *Issue Category Accuracy ≥ 70%*.
2. **Không có cuộc gọi chất lượng kém nào có nhãn.** 2/6 category (`NETWORK_PACKET_LOSS`,
   `NETWORK_DELAY_JITTER`) không có ca mẫu → rule viết theo phỏng đoán và đã đánh dấu
   `UNVALIDATED` trong `taxonomy.yaml`. (`TURN_FAILURE` đã có 3 ca: các cuộc CANCEL trong `fail/`.)
   Đường nền đo trên 844 mẫu stats của cuộc gọi khoẻ mạnh: loss cao nhất **3,704%**, MOS
   thấp nhất **4,335**, RTT cao nhất **266 ms**, jitter cao nhất **28 ms**. Ngưỡng cảnh báo
   bắt buộc phải đặt cao hơn đường nền, nếu không sẽ gắn cờ nhầm cho cuộc gọi tốt.
3. **7 cuộc `for_test/` không có nhãn** nên bị loại khỏi phép đo accuracy. Anh đã xác nhận
   (2026-09-24) đây là tập để em chạy và báo cáo kết quả — em nộp riêng ở
   [`ket-qua-for-test.md`](ket-qua-for-test.md), và không dùng kết quả đó để chỉnh rule.

Và một câu hỏi kỹ thuật đáng hỏi: toàn tập chỉ có candidate `typ relay` (200) và `typ host`
(32), **không có `srflx` nào**. Client đi thẳng qua TURN relay thay vì dùng STUN binding.
Config trong end call log trả lời một phần: `iceTransportPolicy: NOHOST` (24 lần) hoặc `RELAY`
(6 lần), `candidatePolicy: NO_PUBLIC` — relay là đường **mặc định theo cấu hình**. Câu còn
lại để hỏi: cấu hình này áp cho production hay chỉ cho môi trường test?
