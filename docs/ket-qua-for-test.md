# Kết quả chạy tập `for_test/` — 7 cuộc gọi

> **Mentor yêu cầu (2026-09-24):** *"Tập đó để các bạn chạy test và report xem kết quả
> thật sự thế nào."*

Tài liệu này là **kết quả thô** hệ thống tự kết luận, chạy ngày 2026-09-24. Không chỉnh
sửa, không chọn lọc — đủ cả 7 cuộc.

> **Tài liệu liên quan:** [`ket-qua-demo-sprint-1.md`](ket-qua-demo-sprint-1.md) là
> deliverable *"Demo tối thiểu 5 cuộc gọi"* của MVP mục 5.3 — chọn 5 ca để minh hoạ từng
> tính năng, có lời dẫn. Tài liệu này **không thay thế** nó: đây là phép đo mù trên tập
> không nhãn, chạy hết 7 cuộc và báo nguyên kết quả.

## Cách làm, và điều KHÔNG làm

| | |
|---|---|
| Tập này là gì | Tập con của `dev`, mentor cố tình **không đưa nhãn** |
| Có phải `held-out` không | **Không** — mentor cho phép xem và chạy |
| Có tính vào accuracy không | **Không.** Không có nhãn thì không có mẫu số |
| Có dùng để chỉnh rule / ngưỡng không | **Không.** Xem ghi chú bên dưới |

> **Không dùng kết quả tập này để tinh chỉnh.** MVP mục 6.3 ghi *"Không dùng kết quả
> `held-out` Mentor gửi lại để tinh chỉnh"*; em áp dụng cùng tinh thần cho `for_test/`,
> vì mục đích của nó là đo xem hệ thống làm được gì khi **chưa biết đáp án**. Chỉnh theo
> nó là mất luôn giá trị của phép đo.
>
> Mọi ngưỡng và rule trong hệ thống đều rút từ **13 cuộc có nhãn** (`fail/` 6 + `success/` 7).

Accuracy tự báo cáo vẫn là **13/13 = 100%** trên tập có nhãn, không đổi.

> **Chạy lại 2026-09-24, sau chín thay đổi:** (1) parser end call log đọc loại bản ghi theo
> cột `#tag` thay vì số `#Hn` — số hiệu khác nhau giữa các file nên bản cũ gán nhãn sai
> stats/summary; (2) rule `TURN_FAILURE` theo MVP mục 4.2 (*"lỗi TURN socket / allocation /
> relay"*); (3) chỉ số chất lượng của leg chưa nhận được gói audio nào hiển thị `N/A` thay vì
> `0` (MVP mục 4.3), và dòng evidence tương ứng không in `MOS=0`; (4) lý do kết luận nêu rõ mã
> lỗi đọc được từ end call log — `428 privacy_restricted` (server từ chối) và `421` (hết giờ chờ
> candidate) — cùng phần "điểm mơ hồ" cập nhật trong `taxonomy.yaml`; (5) leg của file WebRTC xác định trước hết
> bằng vai SDP (`offer` = caller, `answer` = callee) — trước đó `0EC7B700` bị gán nhầm file
> `caller_webrtc.log` sang callee, nên report từng ghi sai "thiếu WebRTC log của caller"; (6) dòng
> giới hạn về ngưỡng chất lượng ghi "tập data **có nhãn**" thay vì "tập data mẫu" — câu cũ tự
> mâu thuẫn trên chính report của `271D1FAF`, cuộc đang bị gắn cờ suy giảm; (7) evidence có thêm
> **dòng căn cứ của chính kết luận** (MVP mục 3.3: *"Mọi kết luận phải trace được về evidence"*):
> dòng ACK `INIT_CALL` mang `callErrorCode`, dòng `_waitingCandidateTimer`, dòng `_emitFailed`, dòng
> TURN báo lỗi đầu tiên của file không cấp phát được relay (đã bỏ địa chỉ IP), và mẫu stats mất gói
> nặng nhất của mỗi leg; mã lỗi trong câu lý do nay đọc **nguyên văn** từ dòng `_emitFailed` thay vì
> viết cứng "421"; (8) phần "điểm mơ hồ" của `SIGNALING_FAILURE` chỉ khẳng định lý do CANCEL cho
> cuộc có end call log chứng minh (`703100CF`); (9) trích dẫn signaling đổi từ `signaling.json:N` thành
> `signaling#N` — N là **thứ tự sự kiện** trong kết quả truy vấn Elasticsearch, không phải số dòng của
> file `signaling.json` (dạng cũ khiến người đọc mở nhầm dòng). Verdict, category, độ tin cậy và chỉ số
> của cả 7 cuộc không đổi. Các khối báo cáo bên dưới là output mới, không chỉnh tay.

---

## Tổng hợp

| Call-ID | Kết luận | Issue category | Cờ chất lượng | Độ tin cậy | Số dòng giới hạn dữ liệu |
|---|---|---|---|---|---|
| `0A6C2821` | FAIL | TURN_FAILURE | — | HIGH | 3 |
| `0EC7B700` | FAIL | SIGNALING_FAILURE | — | HIGH | 4 |
| `271D1FAF` | SUCCESS | NETWORK_PACKET_LOSS | **Có** | LOW | 4 |
| `311A9B6A` | FAIL | SIGNALING_FAILURE | — | HIGH | 3 |
| `45AA3011` | FAIL | TURN_FAILURE | — | HIGH | 3 |
| `9B556E56` | FAIL | SIGNALING_FAILURE | — | HIGH | 3 |
| `AA9791CE` | FAIL | TURN_FAILURE | — | MEDIUM | 3 |

**Phân bố:** 6 `FAIL` + 1 `SUCCESS` (có cờ chất lượng). Không cuộc nào rơi vào `UNKNOWN`.

Tách theo căn cứ kết luận:

| Căn cứ | Số cuộc | Call-ID |
|---|---|---|
| TURN không cấp phát được lần nào → không có candidate để gửi `INVITE` | 3 | `0A6C2821`, `45AA3011`, `AA9791CE` |
| Hết 6 giây chờ candidate (mã 421) dù TURN vẫn tốt | 1 | `311A9B6A` |
| Server từ chối `INIT_CALL` (mã 428 `privacy_restricted`) | 1 | `9B556E56` |
| Có `FAIL_HARD` | 1 | `0EC7B700` |
| Đủ `OK_ACK_OK` → `BYE`, nhưng có giây mất gói > 5% | 1 | `271D1FAF` |

---

## Đối chiếu chéo với tập có nhãn

Em kiểm lại xem những kiểu cuộc gọi này có xuất hiện trong tập **có nhãn** không, để biết
kết luận của hệ thống có căn cứ hay chỉ là suy đoán:

| Kiểu | Trong `for_test/` | Trong tập có nhãn | Ground truth |
|---|---|---|---|
| TURN không cấp phát được, `INIT_CALL` → `CANCEL` | 3 cuộc | `703100CF`, `7B56D7AD`, `E9D6C112` | **FAIL** |
| Server từ chối `INIT_CALL` (428) | 1 cuộc (`9B556E56`) | `1B009D42`, `D114749E` | **FAIL** |
| Hết giờ chờ candidate dù TURN tốt | 1 cuộc (`311A9B6A`) | — | — |
| `FAIL_HARD` | 1 cuộc (`0EC7B700`) | — | — |
| Đủ `OK_ACK_OK` → `BYE` | 1 cuộc (`271D1FAF`) | cả 7 cuộc `success/` | **SUCCESS** |

**5/7 cuộc** có kiểu trùng khớp với ca đã có nhãn, nên kết luận `FAIL` / `SUCCESS` của hệ
thống dựa trên tiêu chí đã được kiểm chứng.

Ba điểm chưa kiểm chứng được bằng ground truth:

- `0EC7B700` (`FAIL_HARD`): callee bấm nghe, 5,8 giây sau bị `force_end_call` (`reason: 750`),
  chưa bao giờ gửi `OK`. Tập có nhãn không có ca `FAIL_HARD` nào.
- `311A9B6A`: TURN cấp phát được 4/4 cổng, nhưng `CreateOffer` chỉ chạy **6,66 giây** sau khi
  tạo PeerConnection — muộn hơn mốc 6 giây chờ candidate. End call log cho thấy khởi tạo camera
  kéo dài ~3,8 giây. Hệ thống ghi đúng hiện tượng (hết giờ, TURN vẫn tốt, lỗi ở phía client)
  nhưng taxonomy không có category cho lỗi phía client nên vẫn là `SIGNALING_FAILURE`.
- `271D1FAF` được gắn cờ `NETWORK_PACKET_LOSS` (xem mục dưới). Tập có nhãn không có cuộc
  chất lượng kém nào, nên ngưỡng này **chưa kiểm chứng được**.

---

## Về `271D1FAF` — cờ chất lượng kém

Cuộc gọi bình thường (đủ `OK_ACK_OK` → `BYE`, MOS cuối caller 4,15 / callee 4,37). Trước khi
sửa parser, stats của cả hai leg bị gán nhãn sai nên hệ thống không nhìn thấy; nay đọc được:

| Leg | Mẫu stats | Mẫu mất gói > 5% | Chuỗi liên tiếp dài nhất | Mất gói cao nhất |
|---|---|---|---|---|
| caller | 80 | 18 | 2 | 11,32% |
| callee | 80 | 3 | 1 | 7,55% |

Code hiện gắn cờ khi **một** mẫu vượt 5%, trong khi `taxonomy.yaml` mô tả điều kiện là
*"kéo dài trên nhiều mẫu liên tiếp"*. Hai chỗ này đang lệch nhau.

Report của cuộc này trích đúng hai mẫu nặng nhất làm evidence — `[EV06][callee_endcall.log:97]`
loss 7,55% và `[EV07][caller_endcall.log:108]` loss 11,32% — nên người đọc thấy ngay căn cứ của cờ,
thay vì chỉ thấy bản ghi cuối `loss=0%`. Em **không** dùng cuộc này để
chọn con số "bao nhiêu mẫu liên tiếp", vì `for_test/` không dùng để hiệu chỉnh. Cần anh xác nhận
cuộc này có tính là chất lượng kém không, hoặc cho thêm ca có nhãn.

---

## Về các cuộc có `CANCEL` — đã rõ nguyên nhân

Bản trước của tài liệu này đặt câu hỏi *"caller bấm huỷ là lỗi hệ thống hay hành vi người
dùng?"*. End call log và WebRTC log trả lời được: **không phải người dùng huỷ**.

### Trước hết: đây KHÔNG phải SIP

Giữ nguyên nhận định cũ: `MVP.md` không nhắc SIP lần nào, data không có `sip:` URI, và phần lớn
lệnh (`INIT_CALL`, `PAIR_PING`, `OK_ACK_OK`, `FAIL_HARD`, `LOG_STATS`, `ICE`…) không tồn tại
trong SIP. Đây là giao thức riêng mượn một phần từ vựng của SIP, nên em **không** dùng RFC 3261
để lập luận.

### Bằng chứng — tách rõ cơ sở và kết quả

**Cơ sở rút từ tập CÓ NHÃN** (`fail/`):

| Nguồn | `703100CF` | `7B56D7AD` | `E9D6C112` |
|---|---|---|---|
| WebRTC log: số lần TURN allocate thành công | **0** | **0** | **0** |
| WebRTC log: dấu hiệu | 8 × `Failed to create TURN client socket` | 40 × `Failed to send TURN message, error: 65` | 40 × TURN probe timeout |
| End call log | `_waitingCandidateTimer with error` → `_emitFailed … network_check code: 421` | không có file | không có file |
| Signaling: `INIT_CALL` → `CANCEL` sau | 6,25 s | 6,38 s | 8,07 s |

Config của app (`candidateConfig`) có `waitingCandidateTimeout: 6000`: hết 6 giây chưa đủ ICE
candidate thì app tự huỷ. `INVITE` phải mang SDP offer kèm lô candidate đầu tiên, và relay là
đường mặc định (`iceTransportPolicy: NOHOST`), nên TURN hỏng thì `INVITE` không bao giờ được gửi.
So sánh: 7/8 cuộc có nhãn gửi `INVITE` sau 0,52 - 0,64 giây.

**Kết quả quan sát trên `for_test/`** — báo cáo lại, không dùng để hiệu chỉnh:

| Call-ID | TURN allocate thành công | Dấu hiệu | `CANCEL` sau |
|---|---|---|---|
| `0A6C2821` | 0 | 8 × `Failed to create TURN client socket` (có VPN `tun0`), mã 421 | 6,22 s |
| `45AA3011` | 0 | như trên, mã 421 | 6,33 s |
| `AA9791CE` | 0 | 60 × TURN probe timeout, không có end call log | 8,35 s |
| `311A9B6A` | **4** | TURN tốt, offer tạo muộn, mã 421 | 6,66 s |

Ba cuộc `Failed to create TURN client socket` (`703100CF`, `0A6C2821`, `45AA3011`) đều là
Android có giao diện VPN `tun0` và cùng một `appUserId`; không file nào khác có `tun0`. Việc VPN
là nguyên nhân chỉ là **giả thuyết**, chưa kiểm chứng.

### Câu hỏi cho anh

1. Ca `428 privacy_restricted` (người nhận chặn cuộc gọi) có tính là `FAIL` không, và nên thuộc
   category nào? Taxonomy MVP mục 4.2 không có category cho "bị chặn theo chính sách"; hiện xếp
   tạm `SIGNALING_FAILURE` kèm ghi chú.
2. `271D1FAF` có được coi là chất lượng kém không (xem mục trên)?
3. `force_end_call` với `reason: 750` ở `0EC7B700` nghĩa là gì?

---

## Báo cáo đầy đủ từng cuộc

### `0A6C2821`

**Đính kèm:** `caller_endcall.log, caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=10, CANCEL=11

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 0A6C2821-0A19-49F4-9C05-8F8BCEACD64F
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: TURN_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. Không cấp phát được relay trên TURN server nào (0 lần allocate thành công) nên không có candidate để gửi INVITE; app hết thời gian chờ candidate (_emitFailed: mã 421 call.outgoing.error.network_check).

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][caller_endcall.log:24] Log phía client: _waitingCandidateTimer with error
3. [EV03][caller_endcall.log:26] Call summary caller: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
4. [EV04][caller_endcall.log:28] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.network_check endReason: 0 code: 421 open:true
5. [EV05][signaling#11] Signaling CANCEL từ caller
6. [EV06][caller_webrtc.log:214] TURN: Failed to create TURN client socket

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
| ISP / ASN / quốc gia (caller)            | ? / ? / US                                           | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 10 ms, trung vị 6 ms (4 mẫu)                     | SIGNALING |
| Số WARN theo service                     | 11 (S46TQW3OJDW=8, SCYZPESAVDK=3)                    | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: TURN_FAILURE — Không cấp phát được relay trên TURN server nào. Trong hệ thống này relay là đường MẶC ĐỊNH (iceTransportPolicy NOHOST), nên không có relay thì không có candidate để gói vào INVITE và cuộc gọi không bắt đầu được.
- Khả dĩ khác: Điểm mơ hồ: Không được đếm riêng dòng lỗi allocate: "TURN allocate error response code=401" là bước bắt tay xác thực chuẩn của TURN (RFC 8656), không phải lỗi.

## Đề xuất
- Kiểm tra tình trạng và credential của TURN server.
- Xác nhận cổng UDP 3478 không bị chặn từ phía mạng người dùng.

## Giới hạn dữ liệu
- Thiếu end call log của callee — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của callee — không kiểm chứng được sự kiện ICE / TURN phía đó
- caller_webrtc.log: 327 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

### `0EC7B700`

**Đính kèm:** `callee_endcall.log, callee_webrtc.log, caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=9, INVITE=15, TRYING=1, RINGING=2, FAIL_HARD=2

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 0EC7B700-6B5D-45A3-9BB6-1463B56C9634
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: SIGNALING_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. Xuất hiện FAIL_HARD trước khi thiết lập xong.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#10] Signaling INVITE từ caller
3. [EV03][signaling#25] Signaling TRYING từ callee
4. [EV04][signaling#26] Signaling RINGING từ callee
5. [EV05][callee_endcall.log:44] Call summary callee: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
6. [EV06][callee_endcall.log:46] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.canceled endReason: 750 code: 480 open:true
7. [EV07][signaling#29] Signaling FAIL_HARD từ callee

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Thời gian với tới callee                 | 9402 ms                                              | SIGNALING |
| Số lần gửi lại INVITE                    | 4 lần                                                | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | N/A (cuộc gọi không đạt tới OK)                      | SIGNALING |
| Thời lượng kết nối                       | N/A (cuộc gọi không đạt tới OK_ACK_OK)               | SIGNALING |
| Bên kết thúc                             | N/A (cuộc gọi không có BYE)                          | SIGNALING |
| Số lần gửi lại BYE                       | N/A (cuộc gọi không có lệnh BYE)                     | SIGNALING |
| MOS (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Packet loss (caller)                     | N/A (không có end call log của caller)               | ENDCALL   |
| RTT (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Jitter (caller)                          | N/A (không có end call log của caller)               | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | N/A (WebRTC log không ghi chuyển trạng thái ICE nào) | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | N/A (cuộc gọi không có PAIR_PING nào của caller)     | SIGNALING |
| ISP / ASN / quốc gia (caller)            | VIETTEL / AS38731 / VN                               | SIGNALING |
| MOS (callee)                             | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Packet loss (callee)                     | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| RTT (callee)                             | N/A (chưa có phản hồi STUN nào (transport.localStunResponse = 0) nên không đo được RTT; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Jitter (callee)                          | N/A (không nhận được gói audio nào (audio.packetsReceived = 0) nên không đo được; số 0 trong log là giá trị trống, không phải kết quả đo) | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (WebRTC log không ghi chuyển trạng thái ICE nào) | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | VIETTEL / AS38731 / VN                               | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 25 ms, trung vị 16 ms (4 mẫu)                    | SIGNALING |
| Số WARN theo service                     | 7 (S46TQW3OJDW=5, SCYZPESAVDK=2)                     | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: SIGNALING_FAILURE — Cuộc gọi không thiết lập được ở tầng signaling: server chưa từng gọi tới callee, hoặc phiên bị huỷ/từ chối trước khi hai bên bắt tay xong.
- Khả dĩ khác: Điểm mơ hồ: Mã 428 privacy_restricted là server TỪ CHỐI theo chính sách của người nhận, không phải lỗi mạng; taxonomy MVP mục 4.2 không có category riêng nên xếp tạm vào đây — cần mentor xác nhận. Với CANCEL: 703100CF (cuộc CANCEL duy nhất của tập có nhãn có end call log) ghi `_waitingCandidateTimer with error` rồi `_emitFailed ... code: 421`, tức app TỰ huỷ khi hết `waitingCandidateTimeout` (6000 ms trong config của app); 7B56D7AD và E9D6C112 không có end call log nên chưa thấy trực tiếp lý do huỷ. Nếu TURN không cấp phát được thì xếp TURN_FAILURE, còn TURN tốt mà vẫn hết giờ thì lỗi nằm ở phía client.

## Đề xuất
- Kiểm tra log của service signaling quanh thời điểm INIT_CALL.
- Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.
- Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.

## Giới hạn dữ liệu
- Thiếu end call log của caller — không kiểm chứng được chỉ số chất lượng phía đó
- callee_webrtc.log: 211 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- caller_webrtc.log: 307 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- Lệch đồng hồ CALLEE so với server là 1615 ms, đủ lớn để ảnh hưởng thứ tự sự kiện

Report hợp lệ theo schema v1: CÓ
```

---

### `271D1FAF`

**Đính kèm:** `callee_endcall.log, callee_webrtc.log, caller_endcall.log, caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=10, INVITE=5, RINGING=2, OK=4, OK_ACK_OK=1, PAIR_PING=57, BYE=4

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 271D1FAF-26D4-4150-AC3F-9204505BD84B
Kết luận: SUCCESS
Cờ chất lượng: Có - NETWORK_PACKET_LOSS
Độ tin cậy: LOW
Tóm tắt: Cuộc gọi thiết lập thành công nhưng có dấu hiệu suy giảm chất lượng. Cuộc gọi thiết lập và kết thúc bình thường nhưng chỉ số chất lượng vượt ngưỡng.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#11] Signaling INVITE từ caller
3. [EV03][signaling#14] Signaling RINGING từ callee
4. [EV04][signaling#20] Signaling OK từ callee
5. [EV05][signaling#25] Signaling OK_ACK_OK từ caller
6. [EV06][callee_endcall.log:97] Chỉ số media callee: MOS=4.34143, loss=7.54717%, mediaFail=0, bytesRecv=36311
7. [EV07][caller_endcall.log:108] Chỉ số media caller: MOS=4.19547, loss=11.3208%, mediaFail=0, bytesRecv=59244
8. [EV08][callee_endcall.log:179] Chỉ số media callee: MOS=4.37331, loss=0%, mediaFail=0, bytesRecv=141379
9. [EV09][caller_endcall.log:181] Chỉ số media caller: MOS=4.15346, loss=0%, mediaFail=0, bytesRecv=177699
10. [EV10][caller_endcall.log:185] Call summary caller: MOS=4.15346, loss=0%, RTT=16ms, bytesRecv=177699
11. [EV11][caller_endcall.log:187] Kết thúc phía client: _emitBye with originator: 0 duration: 81 codeReason: 487 failReason: Bạn đã huỷ
12. [EV12][callee_endcall.log:181] Call summary callee: MOS=4.37331, loss=0%, RTT=18ms, bytesRecv=141379
13. [EV13][callee_endcall.log:183] Kết thúc phía client: _emitBye with originator: 1 duration: 81 codeReason: 487 failReason: Bạn đã huỷ
14. [EV14][signaling#87] Signaling BYE từ caller
15. [EV15][callee_webrtc.log:253] ICE chuyển new => checking
16. [EV16][callee_webrtc.log:381] ICE chuyển new => checking
17. [EV17][callee_webrtc.log:493] ICE chuyển checking => connected
18. [EV18][callee_webrtc.log:532] ICE chuyển checking => connected
19. [EV19][caller_webrtc.log:325] ICE chuyển new => checking
20. [EV20][caller_webrtc.log:357] ICE chuyển new => checking
21. [EV21][caller_webrtc.log:428] ICE chuyển checking => connected
22. [EV22][caller_webrtc.log:515] ICE chuyển checking => connected

## Chỉ số cuộc gọi
| Chỉ số                                   | Giá trị                                              | Nguồn     |
| Thời gian thiết lập                      | 3840 ms                                              | SIGNALING |
| Thời gian với tới callee                 | N/A (cuộc gọi không đạt tới TRYING)                  | SIGNALING |
| Số lần gửi lại INVITE                    | 1 lần                                                | SIGNALING |
| Số lần No sessions found                 | N/A (signaling không có trường text để đếm thông báo này) | SIGNALING |
| Thời gian đổ chuông                      | 3032 ms                                              | SIGNALING |
| Thời lượng kết nối                       | 80358 ms                                             | SIGNALING |
| Bên kết thúc                             | CALLER                                               | SIGNALING |
| Số lần gửi lại BYE                       | 1 lần                                                | SIGNALING |
| MOS (caller)                             | 4.15346                                              | ENDCALL   |
| Packet loss (caller)                     | 0 %                                                  | ENDCALL   |
| RTT (caller)                             | 16 ms                                                | ENDCALL   |
| Jitter (caller)                          | 12 ms                                                | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | 6007 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (caller)            | AS45903 / AS45903 / VN                               | SIGNALING |
| MOS (callee)                             | 4.37331                                              | ENDCALL   |
| Packet loss (callee)                     | 0 %                                                  | ENDCALL   |
| RTT (callee)                             | 18 ms                                                | ENDCALL   |
| Jitter (callee)                          | 3 ms                                                 | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | connected                                            | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | 5993 ms [proxy]                                      | SIGNALING |
| ISP / ASN / quốc gia (callee)            | AS45903 / AS45903 / VN                               | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 19 ms, trung vị 14 ms (4 mẫu)                    | SIGNALING |
| Số WARN theo service                     | 38 (SCYZPESAVDK=38)                                  | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: NETWORK_PACKET_LOSS — Cuộc gọi thiết lập và chạy được nhưng mất gói đủ làm giảm chất lượng thoại.
- Khả dĩ khác: Điểm mơ hồ: Mất gói thoáng qua là bình thường. Phải xét CHUỖI bản ghi stats chứ không chỉ đọc bản ghi summary (endcall) cuối cùng — DE7DD314 có loss cuối = 0 nhưng từng lên 2,0% giữa cuộc gọi.

## Đề xuất
- Kiểm tra chất lượng mạng của bên bị ảnh hưởng trong thời gian cuộc gọi.
- Đối chiếu chuỗi bản ghi stats theo từng giây để xem mất gói kéo dài hay chỉ thoáng qua.

## Giới hạn dữ liệu
- Ngưỡng phát hiện chất lượng kém CHƯA kiểm chứng được: tập data có nhãn không có cuộc gọi nào bị suy giảm chất lượng
- callee_webrtc.log: 1168 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- caller_webrtc.log: 960 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling
- Điều kiện phát hiện NETWORK_PACKET_LOSS chưa kiểm chứng được trên data mẫu: KHÔNG có ca mẫu chất lượng kém trong tập có nhãn. Đường nền đo được trên 844 mẫu stats của 6 leg thuộc success/: loss cao nhất 3,704%, MOS thấp nhất 4,335. Ngưỡng cảnh báo vì vậy phải đặt CAO HƠN 3,7% và MOS THẤP HƠN 4,3, nếu không sẽ gắn cờ nhầm cho cuộc gọi tốt. Con số 5.0 / 3.5 là phỏng đoán.

Report hợp lệ theo schema v1: CÓ
```

---

### `311A9B6A`

**Đính kèm:** `caller_endcall.log, caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=10, CANCEL=19

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 311A9B6A-0D30-4C30-9E01-9A42E4EF11E6
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: SIGNALING_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. App hết thời gian chờ candidate (_emitFailed: mã 421 call.outgoing.error.network_check) trước khi gửi được INVITE; TURN vẫn cấp phát được nên nguyên nhân nằm ở bước tạo offer hoặc thu candidate phía client.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][caller_endcall.log:28] Log phía client: _waitingCandidateTimer with error
3. [EV03][caller_endcall.log:31] Call summary caller: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
4. [EV04][caller_endcall.log:33] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.network_check endReason: 0 code: 421 open:true
5. [EV05][signaling#11] Signaling CANCEL từ caller

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
| ISP / ASN / quốc gia (caller)            | VIETTEL / AS38731 / VN                               | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 10 ms, trung vị 8 ms (4 mẫu)                     | SIGNALING |
| Số WARN theo service                     | 19 (S46TQW3OJDW=13, SCYZPESAVDK=6)                   | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: SIGNALING_FAILURE — Cuộc gọi không thiết lập được ở tầng signaling: server chưa từng gọi tới callee, hoặc phiên bị huỷ/từ chối trước khi hai bên bắt tay xong.
- Khả dĩ khác: Điểm mơ hồ: Mã 428 privacy_restricted là server TỪ CHỐI theo chính sách của người nhận, không phải lỗi mạng; taxonomy MVP mục 4.2 không có category riêng nên xếp tạm vào đây — cần mentor xác nhận. Với CANCEL: 703100CF (cuộc CANCEL duy nhất của tập có nhãn có end call log) ghi `_waitingCandidateTimer with error` rồi `_emitFailed ... code: 421`, tức app TỰ huỷ khi hết `waitingCandidateTimeout` (6000 ms trong config của app); 7B56D7AD và E9D6C112 không có end call log nên chưa thấy trực tiếp lý do huỷ. Nếu TURN không cấp phát được thì xếp TURN_FAILURE, còn TURN tốt mà vẫn hết giờ thì lỗi nằm ở phía client.

## Đề xuất
- Kiểm tra log của service signaling quanh thời điểm INIT_CALL.
- Đối chiếu số lần gửi lại INVITE với cấu hình timeout phía server.
- Bổ sung end call log của cả hai bên để xác nhận phía nào không phản hồi.

## Giới hạn dữ liệu
- Thiếu end call log của callee — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của callee — không kiểm chứng được sự kiện ICE / TURN phía đó
- caller_webrtc.log: 268 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

### `45AA3011`

**Đính kèm:** `caller_endcall.log, caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=10, CANCEL=11

```text
# Báo cáo phân tích cuộc gọi
Call-ID: 45AA3011-1034-4D13-82A4-634A72D432B6
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: TURN_FAILURE)
Độ tin cậy: HIGH
Tóm tắt: Cuộc gọi không thành công. Không cấp phát được relay trên TURN server nào (0 lần allocate thành công) nên không có candidate để gửi INVITE; app hết thời gian chờ candidate (_emitFailed: mã 421 call.outgoing.error.network_check).

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][caller_endcall.log:24] Log phía client: _waitingCandidateTimer with error
3. [EV03][caller_endcall.log:26] Call summary caller: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=0 — MOS/loss/RTT không đo được
4. [EV04][caller_endcall.log:28] Thất bại phía client: _emitFailed with originator: 0 reason: call.outgoing.error.network_check endReason: 0 code: 421 open:true
5. [EV05][signaling#11] Signaling CANCEL từ caller
6. [EV06][caller_webrtc.log:221] TURN: Failed to create TURN client socket

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
| ISP / ASN / quốc gia (caller)            | ? / ? / US                                           | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 15 ms, trung vị 14 ms (4 mẫu)                    | SIGNALING |
| Số WARN theo service                     | 11 (S46TQW3OJDW=8, SCYZPESAVDK=3)                    | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: TURN_FAILURE — Không cấp phát được relay trên TURN server nào. Trong hệ thống này relay là đường MẶC ĐỊNH (iceTransportPolicy NOHOST), nên không có relay thì không có candidate để gói vào INVITE và cuộc gọi không bắt đầu được.
- Khả dĩ khác: Điểm mơ hồ: Không được đếm riêng dòng lỗi allocate: "TURN allocate error response code=401" là bước bắt tay xác thực chuẩn của TURN (RFC 8656), không phải lỗi.

## Đề xuất
- Kiểm tra tình trạng và credential của TURN server.
- Xác nhận cổng UDP 3478 không bị chặn từ phía mạng người dùng.

## Giới hạn dữ liệu
- Thiếu end call log của callee — không kiểm chứng được chỉ số chất lượng phía đó
- Thiếu WebRTC log của callee — không kiểm chứng được sự kiện ICE / TURN phía đó
- caller_webrtc.log: 336 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

---

### `9B556E56`

**Đính kèm:** `caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=10

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

### `AA9791CE`

**Đính kèm:** `caller_webrtc.log`
**Lệnh signaling:** INIT_CALL=9, CANCEL=19

```text
# Báo cáo phân tích cuộc gọi
Call-ID: AA9791CE-13D8-4DD7-942D-4D78304FD458
Kết luận: FAIL
Cờ chất lượng: Không (nguyên nhân: TURN_FAILURE)
Độ tin cậy: MEDIUM
Tóm tắt: Cuộc gọi không thành công. Không cấp phát được relay trên TURN server nào (0 lần allocate thành công) nên không có candidate để gửi INVITE.

## Evidence chính
1. [EV01][signaling#1] Signaling INIT_CALL từ caller
2. [EV02][signaling#10] Signaling CANCEL từ caller
3. [EV03][caller_webrtc.log:338] TURN: TURN probe request 3733554d5231483770547a59 timeout

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
| MOS (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Packet loss (caller)                     | N/A (không có end call log của caller)               | ENDCALL   |
| RTT (caller)                             | N/A (không có end call log của caller)               | ENDCALL   |
| Jitter (caller)                          | N/A (không có end call log của caller)               | ENDCALL   |
| Trạng thái ICE đạt được (caller)         | N/A (WebRTC log không ghi chuyển trạng thái ICE nào) | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (caller) | N/A (cuộc gọi không có PAIR_PING nào của caller)     | SIGNALING |
| ISP / ASN / quốc gia (caller)            | VNPT / AS45899 / VN                                  | SIGNALING |
| MOS (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Packet loss (callee)                     | N/A (không có end call log của callee)               | ENDCALL   |
| RTT (callee)                             | N/A (không có end call log của callee)               | ENDCALL   |
| Jitter (callee)                          | N/A (không có end call log của callee)               | ENDCALL   |
| Trạng thái ICE đạt được (callee)         | N/A (không có WebRTC log của callee)                 | WEBRTC    |
| Khoảng trống PAIR_PING lớn nhất (callee) | N/A (cuộc gọi không có PAIR_PING nào của callee)     | SIGNALING |
| ISP / ASN / quốc gia (callee)            | N/A (không có sự kiện signaling nào của callee)      | SIGNALING |
| Latency API nội bộ lúc INIT_CALL         | max 18 ms, trung vị 16 ms (4 mẫu)                    | SIGNALING |
| Số WARN theo service                     | 18 (S46TQW3OJDW=13, SCYZPESAVDK=5)                   | SIGNALING |
| Số ERROR theo service                    | 0 lần                                                | SIGNALING |

## Vấn đề chất lượng / nguyên nhân khả dĩ
- Chính: TURN_FAILURE — Không cấp phát được relay trên TURN server nào. Trong hệ thống này relay là đường MẶC ĐỊNH (iceTransportPolicy NOHOST), nên không có relay thì không có candidate để gói vào INVITE và cuộc gọi không bắt đầu được.
- Khả dĩ khác: Điểm mơ hồ: Không được đếm riêng dòng lỗi allocate: "TURN allocate error response code=401" là bước bắt tay xác thực chuẩn của TURN (RFC 8656), không phải lỗi.

## Đề xuất
- Kiểm tra tình trạng và credential của TURN server.
- Xác nhận cổng UDP 3478 không bị chặn từ phía mạng người dùng.

## Giới hạn dữ liệu
- Không có end call log nên không kiểm chứng được chất lượng media
- Thiếu WebRTC log của callee — không kiểm chứng được sự kiện ICE / TURN phía đó
- caller_webrtc.log: 500 sự kiện dùng mốc thời gian tương đối, chưa đồng bộ được với timeline signaling

Report hợp lệ theo schema v1: CÓ
```

