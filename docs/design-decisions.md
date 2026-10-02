# Quyết định thiết kế

Ghi lại **lý do** đằng sau các lựa chọn, để trả lời được ở design review và trong PR.
Mỗi mục: quyết định — lý do — đánh đổi đã chấp nhận.

---

## 1. Spring Boot 3.5.16, không dùng 4.x

**Quyết định.** Dùng dòng 3.5 dù 4.1.1 đã phát hành chính thức.

**Lý do.** Boot 4 kéo theo **Jackson 3**, đổi hẳn tên package `com.fasterxml.jackson.*` →
`tools.jackson.*`. Dự án này parse JSON rất nặng (envelope signaling, cột `payload` của record
`#H3`, cột `data` của `#H4`), nên mọi ví dụ và tài liệu tra cứu sẽ không còn khớp. Boot 4 cũng
kéo Elasticsearch client 9.x, buộc chạy ES server 9.x — ít tài liệu hơn 8.x.

Đổi lại Boot 4 cho những gì? Null-safety JSpecify, API versioning, HTTP interface client —
không thứ nào liên quan tới bài toán này.

**Đánh đổi.** Chậm hơn một thế hệ framework. Chấp nhận được vì phần khó của dự án nằm ở
parsing và correlation, không ở framework.

---

## 2. Elasticsearch 8.18.8, khớp đúng client

**Quyết định.** Ghim image Docker ở `8.18.8`, đúng bằng phiên bản Elasticsearch Java client
mà Spring Boot 3.5.16 quản lý.

**Lý do.** Lệch major giữa client và server gây lỗi ở runtime. Ghim cùng số phiên bản là cách
rẻ nhất để loại hẳn một nhóm lỗi.

---

## 3. `_id` của document suy ra tất định từ `(callId, ordinal)`

**Quyết định.** `documentId() = callId + ":" + ordinal`, với `ordinal` là vị trí của event
trong file gốc.

**Lý do.** Yêu cầu "script import lặp lại được": chạy import nhiều lần phải cho cùng kết quả,
không sinh bản ghi trùng. Nếu để Elasticsearch tự sinh `_id`, mỗi lần chạy sẽ nhân đôi dữ liệu.

Không dùng hash nội dung vì data mẫu có nhiều event **giống hệt nhau về nội dung** (ví dụ 10
event `INIT_CALL` liên tiếp) — hash sẽ gộp chúng lại và làm mất dữ liệu thật.

**Đánh đổi.** Nếu mentor xuất lại file với thứ tự event khác, `_id` sẽ đổi. Chấp nhận được với
data mẫu tĩnh dùng cho phát triển local.

**Cập nhật Sprint 2 — gửi theo lô (theo nhận xét mentor).** Bản Sprint 1 gom mọi document vào
**một** bulk request. Với 1 059 document thì ổn, nhưng data lớn hơn sẽ đụng giới hạn
`http.max_content_length` của Elasticsearch (mặc định 100 MB) và giữ toàn bộ trong heap — chặn
đường cho Batch Analysis ở Sprint 3. Nay importer đọc từng file và gửi theo lô 500 document.
Tính idempotent được kiểm trên Elasticsearch thật (`SignalingImporterIntegrationTest`, qua
Testcontainers): import hai lần, ép nhiều lô, số document không đổi.

---

## 4. Adapter Elasticsearch trả `RawSignalingRecord`, không trả `CanonicalEvent`

**Quyết định.** `ElasticsearchSignalingSource` chỉ map document → bản ghi thô rồi dừng.
Việc chuẩn hoá (parse timestamp, suy ra leg, gán `EventType`) thuộc tầng domain.

**Lý do.** Nếu adapter trả thẳng `CanonicalEvent`, toàn bộ logic chuẩn hoá bị kẹt trong lớp
cần Elasticsearch thật mới test được. Tách ra thì test chuẩn hoá chỉ cần dựng `RawSignalingRecord`
bằng tay, chạy trong mili-giây.

Đây là hệ quả trực tiếp của luật "domain không chạm I/O".

---

## 5. Timestamp giữ nguyên kiểu `String` khi qua biên

**Quyết định.** `RawSignalingRecord.timestamp` và `SignalingDocument.timestamp` đều là `String`,
không parse sẵn thành `Instant`.

**Lý do.** Timestamp signaling có **9 chữ số thập phân** (`2026-09-21T08:44:28.953756952Z`).
Parse ở tầng adapter là thêm một chỗ có thể làm mất độ chính xác mà không đem lại lợi ích gì.
Mapping Elasticsearch dùng `strict_date_optional_time_nanos` để giữ nguyên nano ở phía lưu trữ.

---

## 6. `EventTime` là sealed interface `Absolute | Relative`

**Quyết định.** Không dùng `Instant` trực tiếp trong `CanonicalEvent`.

**Lý do.** Ba nguồn log dùng **ba hệ thời gian không cùng gốc**:

| Nguồn | Định dạng | Tính chất |
|---|---|---|
| `signaling.json` | `2026-09-21T08:44:28.953756952Z` | UTC tuyệt đối |
| `*_endcall.log` | `1789700842873` | epoch millis, đồng hồ client |
| `*_webrtc.log` | `[6652:953]` | **tương đối**, không có gốc |

WebRTC log không hề có giờ tuyệt đối. Ép nó về `Instant` bằng cách đoán gốc thời gian là
**bịa số liệu**. Sealed interface khiến compiler bắt buộc xử lý cả hai trường hợp ở mọi nơi,
không thể quên.

**Đánh đổi.** Code dùng `EventTime` dài dòng hơn `Instant`. Chấp nhận, vì đây đúng là chỗ
dễ sinh số liệu sai nhất.

---

## 7. Metadata `truncated` gắn vào từng document

**Quyết định.** Ba trường `sourceTruncated`, `sourceReturned`, `sourceTotalMatching` được
sao chép vào mọi document thay vì tách ra index riêng.

**Lý do.** Cuộc gọi `DE7DD314` trong data mẫu có `truncated: true` (trả 200/201 event) —
tức thiếu dữ liệu, và điều đó phải được nêu ở mục "Giới hạn dữ liệu" của report. Giữ metadata
cùng chỗ với event thì chỉ cần một truy vấn.

**Đánh đổi.** Dữ liệu lặp trên mọi document. Không đáng kể ở quy mô 1 059 document. Nếu sau này
cần lưu thêm metadata cấp cuộc gọi thì nên tách index riêng.

---

## 8. `SourceRef` là trường bắt buộc của `CanonicalEvent`

**Quyết định.** Mọi event bắt buộc mang theo tên file + số dòng + dòng gốc.

**Lý do.** Yêu cầu "mọi evidence trace được về dòng log gốc", và mẫu report in ra dạng
`[EV05][callee_endcall.log 10:00:41.000]`. Thêm trường này về sau đồng nghĩa với việc sửa
toàn bộ parser, nên đưa vào ngay từ model đầu tiên.

---

## 9. `attributes` bọc trong `LinkedHashMap` không sửa được

**Quyết định.** Compact constructor của `CanonicalEvent` sao chép phòng thủ map đầu vào vào
`LinkedHashMap` rồi bọc `unmodifiableMap`.

**Lý do.** Hai vấn đề riêng biệt:

- **Sao chép phòng thủ:** record chỉ khoá *tham chiếu*, không khoá nội dung collection. Không
  sao chép thì người gọi vẫn sửa được nội dung event sau khi tạo.
- **`LinkedHashMap`:** giữ thứ tự chèn. Thứ tự đổi giữa các lần chạy làm mất tính tất định,
  và yêu cầu về tính nhất quán của kết quả sẽ fail mà không rõ nguyên nhân.

---

## 10. Import thoát tường minh bằng `SpringApplication.exit`

**Quyết định.** Lệnh CLI gọi `System.exit(SpringApplication.exit(...))` sau khi chạy xong.

**Lý do.** `RestClient` của Elasticsearch giữ thread non-daemon, nên JVM không tự thoát sau khi
runner kết thúc — lệnh import treo vô hạn. Đã quan sát thấy thật: lần chạy đầu treo hơn 7 phút
dù import đã xong sau 1 giây. Thoát tường minh là điều kiện để import dùng được trong script và CI.

---

## 11. Dedupe ở mức FILE, không ở mức dòng

**Quyết định.** So sánh toàn bộ chuỗi sự kiện của từng file; chỉ loại khi hai file trùng
hoàn toàn. Trong một file thì không bao giờ loại dòng nào.

**Lý do.** Bản dedupe đầu tiên làm theo mức dòng và **xoá mất 92 sự kiện thật** ở cuộc gọi
`EE129C8F`. Kiểm lại data thì đó không phải trùng lặp: libwebrtc ghi
`openssl_adapter.cc ... TLS server done` **ba lần trong cùng một mili giây** — ba bản ghi
thật của ba phiên TLS. Xoá bớt sẽ làm sai mọi chỉ số đếm.

Trùng lặp thật trong bài toán này là **người dùng đính kèm cùng một file hai lần** dưới hai
tên khác nhau. Đó mới là thứ cần loại.

Cũng không được nhầm với một tình huống khác: cùng một sự kiện xuất hiện ở signaling *và*
end call log (ví dụ `INVITE`) là **hai góc nhìn** của cùng một việc, phải giữ cả hai.

**Đánh đổi.** Không bắt được trường hợp file trùng nhưng lệch một dòng. Chấp nhận được, vì
hậu quả của việc xoá nhầm dữ liệu thật nặng hơn nhiều.

---

## 12. Khi trùng nội dung thì giữ tên file đúng quy ước

**Quyết định.** Giữa hai file trùng nội dung, giữ file có tên khớp
`caller_/callee_ + _endcall/_webrtc.log`, không giữ file đứng trước theo bảng chữ cái.

**Lý do.** Bản đầu giữ file đứng trước theo thứ tự chữ cái, nên khi thử với một bản sao tên
`ban_sao.log` thì hệ thống bỏ mất `callee_webrtc.log`. Số liệu vẫn đúng, nhưng evidence lại
trích dẫn `[EV12][ban_sao.log:902]` — tên vô nghĩa với người đọc report.

---

## 13. Đếm số LẦN GỬI, không đếm số dòng log

**Quyết định.** Chỉ số "số lần gửi lại" gom các sự kiện cùng lệnh thành cụm theo ngưỡng
**250 ms**, rồi đếm số cụm trừ một.

**Lý do.** Một lần gửi sinh nhiều dòng log. Cuộc gọi `EE129C8F` có **10 sự kiện** `cmd=INVITE`
nhưng chỉ **1 `requestId`**, và timestamp gom thành **4 cụm** cách nhau 0,5s / 1,0s / 2,0s —
đúng dấu hiệu exponential backoff. Đếm thẳng số sự kiện ra 9 lần gửi lại thay vì 3.

Ngưỡng 250 ms không phải con số đoán. Đo toàn bộ **451 khoảng cách** giữa các sự kiện
signaling liên tiếp cùng lệnh, phân bố lưỡng cực rất sạch:

| Khoảng cách | Số lượng |
|---|---|
| dưới 100 ms | 363 (cùng một lần gửi) |
| **100 - 500 ms** | **0** |
| từ 500 ms | 88 (lần gửi lại) |

Dải trống 87 ms → 506 ms cho phép đặt ngưỡng ở giữa mà không có ca nào nhập nhằng.

---

## 14. Trừ thời gian ở độ chính xác nano rồi mới cắt về mili

**Quyết định.** Dùng `Duration.between(from, to).toMillis()`, không dùng
`to.toEpochMilli() - from.toEpochMilli()`.

**Lý do.** Timestamp signaling có 9 chữ số thập phân. Cắt từng `Instant` về mili **trước khi**
trừ làm mất phần nano của cả hai mốc, sai số lên tới 1 ms. Đối chiếu với tính tay độc lập cho
thấy lệch đúng 1 ms trên **5 trên 20 cuộc gọi**.

Metric Correctness là tiêu chí **bắt buộc 100%**, nên đây không phải sai số chấp nhận được.

---

## 15. Trạng thái ICE báo là "đạt được", không phải "cuối cùng"

**Quyết định.** Chỉ số ICE trả về `connected` nếu ICE từng đạt `connected`/`completed`;
chỉ khi chưa từng đạt mới trả trạng thái cuối cùng.

**Lý do.** Cuộc gọi thành công vẫn kết thúc ở `disconnected` khi người dùng cúp máy. Báo giá
trị cuối cùng sẽ hiển thị `disconnected` cho một cuộc gọi hoàn toàn bình thường — gây hiểu
nhầm cho người đọc report. Cái cần biết là ICE **có kết nối được không**.

---

## 16. Rule xét media TRƯỚC khi được phép kết luận SUCCESS

**Quyết định.** `RuleVerdictEngine` kiểm tra tín hiệu media trước nhánh SUCCESS, không phải
sau.

**Lý do.** Cuộc gọi `2D9057AA` có signaling **trông bình thường**: `OK_ACK_OK`, `BYE`, `PAIR_PING`
đều đặn sau khi bắt tay (PAIR_PING của caller ngừng ở giây ~24,8, BYE của callee không được ACK). Nhưng ground truth là FAIL — ICE chuyển `checking => failed` sau 15,6 giây,
`audio.bytesReceived = 0`, và hệ thống tự ngắt với `codeReason=419`, `originator=2`.

Rule nào chỉ xét signaling sẽ kết luận SUCCESS và **sai ngay trên tập dev**.

Tín hiệu mạnh nhất là `audio.bytesReceived = 0`, không phải MOS: MOS bằng 0 mơ hồ giữa
"đo được và bằng 0" với "không đo được", còn 0 byte thì dứt khoát.

---

## 17. Taxonomy và Sensitive Data Inventory để ở dạng dữ liệu

**Quyết định.** `taxonomy.yaml` và `sensitive-data-inventory.yaml` là file YAML trong
`resources/`, không phải hằng số trong Java.

**Lý do.** Ba lý do, theo thứ tự quan trọng:

1. **Sprint 2 nạp thẳng vào prompt.** Taxonomy là đầu vào của AI Analysis Engine; inventory
   là Policy Engine của Sanitizer. Nếu hard-code thì phải viết lại lần nữa.
2. **Mentor review được mà không cần đọc Java.**
3. **Đổi ngưỡng không cần build lại.**

Mỗi issue category có thêm trường `calibration` ghi rõ điều kiện phát hiện đã được kiểm chứng
trên data hay chưa. Đây là cách làm cho phần "chưa chắc chắn" hiện rõ thay vì trộn lẫn với
phần đã xác minh — ba category `UNVALIDATED` được tự động đưa vào mục "Giới hạn dữ liệu" của
report.

---

## 18. JSON Schema ép quy tắc nghiệp vụ, không chỉ kiểm cấu trúc

**Quyết định.** `report-v1.schema.json` dùng `oneOf` để ép: chỉ số thiếu giá trị thì **bắt
buộc** phải có `naReason`. Và đặt `additionalProperties: false` ở cấp gốc.

**Lý do.** `oneOf` biến quy tắc *"không được mặc định về 0"* thành ràng buộc máy kiểm được,
thay vì một dòng ghi chú mà ai đó có thể quên.

`additionalProperties: false` là chuẩn bị cho Sprint 2: khi AI điền report, nó không thể tự
thêm trường kiểu `aiConfidenceScore: 0.93`. Cùng validator này sẽ làm lớp Guardrails, nên ca
kiểm thử G02 ("AI response sai format") được chặn sẵn từ Sprint 1.

---

## 19. Đo lệch đồng hồ nhưng KHÔNG viết lại timestamp

**Quyết định.** Đo độ lệch giữa đồng hồ client và server bằng trung vị hiệu số, ghi vào
ghi chú của timeline, nhưng giữ nguyên mọi timestamp gốc.

**Lý do.** Đo thật trên data: **270 cặp khớp, trung vị 70,7 ms**. Các giá trị lớn (2 112 ms ở
`TRYING`) là do ghép nhầm giữa các lần gửi lại, không phải lệch thật — vì vậy dùng trung vị
chứ không dùng trung bình.

Hai lý do không viết lại timestamp:

1. **Không tách được hai thành phần.** Giá trị đo được là tổng của độ trễ mạng và lệch đồng
   hồ thật. Với log một chiều thì không có cách nào tách. Bù theo tổng sẽ bù quá tay.
2. **Rewrite phá khả năng trace.** Evidence phải trỏ về đúng dòng log gốc với đúng giờ ghi
   trong đó. Sửa timestamp là làm sai lệch bằng chứng.

Mức 50-210 ms đo được nhỏ hơn nhiều so với khoảng cách giữa các sự kiện (tính bằng giây), nên
không đủ làm đổi thứ tự. Vượt 1 giây thì đánh dấu `DANG KE` để cảnh báo.

**Đánh đổi.** Nếu tập `held-out` có máy lệch đồng hồ nặng, thứ tự sự kiện giữa client và
server có thể sai. Ghi chú `CLOCK_OFFSET` làm điều đó hiện ra thay vì âm thầm.

---

## 20. Xác định chủ sở hữu file bằng nội dung, không bằng tên

**Quyết định.** Đối chiếu format của WebRTC log (Format 1 = iOS, Format 2 = Android) với cột
`platform` và `role` của bản ghi `#H1` trong end call log. Chỉ khi không phân biệt được mới
quay về gợi ý từ tên file, và đánh dấu độ tin cậy thấp hơn.

**Lý do.** Data mẫu có file `calleer_webrtc.log` — thừa một chữ `e` — và thư mục đó **không
có** `caller_webrtc.log`. Suy theo tên thì đây là CALLEE (vì `"calleer"` bắt đầu bằng
`"callee"`), nhưng đối chiếu nội dung cho ra CALLER:

```text
callee_endcall.log  →  role=callee   platform=ios
caller_endcall.log  →  role=caller   platform=android

callee_webrtc.log   →  Format 1 (iOS)      ✓ khớp callee
calleer_webrtc.log  →  Format 2 (Android)  ✓ khớp caller
```

Gán sai leg thì mọi chỉ số per-leg sau đó đều sai. Độ tin cậy được ghi lại
(`MATCHED_BY_SDP_ROLE` / `MATCHED_BY_PLATFORM` / `FILE_NAME_ONLY` / `UNRESOLVED`) vì Confidence
Design ở Sprint 3 cần phân biệt kết luận dựa trên dữ liệu chắc chắn với kết luận dựa trên suy đoán.

**Cập nhật 2026-09-24: vai SDP được xét TRƯỚC nền tảng.** File WebRTC có `DoSetLocalDescription: offer`
là của caller, `answer` là của callee (22/27 file có dòng này; khớp 100% với vai DTLS server/client).
So nền tảng chỉ còn là dự phòng cho file không có SDP, vì nó giả định leg kia khác nền tảng: khi
chỉ một bên có end call log mà hai bên cùng iOS, nó từng gán nhầm `C8CF631E/callee_webrtc.log`
sang caller và `0EC7B700/caller_webrtc.log` sang callee. Sau khi sửa: 27/27 file đúng (trước là 25/27).

**Đánh đổi.** File không có SDP mà hai bên cùng nền tảng thì không phân biệt được, phải quay về
tên file; trường hợp đó được đánh dấu `FILE_NAME_ONLY` và ghi vào "Giới hạn dữ liệu". Không được
bỏ hẳn luật nền tảng để "tin tên file": `D114749E/callee_webrtc.log` thật ra do máy caller ghi
(cùng `deviceId` với `1B009D42`; hai lần gọi cách nhau 8,085 s ở cả đồng hồ tuyệt đối lẫn đồng hồ
tương đối của log), và luật nền tảng gán nó đúng cho caller.

---

## 21. Độ tin cậy theo mức dữ liệu nhìn được; thiếu căn cứ thì `UNKNOWN`

**Quyết định.** Ba luật, cập nhật ở Sprint 2 theo nhận xét mentor:

1. **Không đủ căn cứ để nói `SUCCESS` → `UNKNOWN`**, không phải `SUCCESS` hạ tin cậy:
   - không có log client nào (chỉ có signaling);
   - đạt `OK_ACK_OK` nhưng không thấy `BYE`.
2. **`SUCCESS` chỉ đạt `HIGH` khi hội đủ ba điều kiện** (giữ từ Sprint 1). `FAIL` được phép đạt
   `HIGH` ngay khi có **một** bằng chứng đủ mạnh.
3. **Thiếu log client của một leg → tối đa `MEDIUM`, cho MỌI kết luận**, kể cả `FAIL`. Ngoại lệ
   duy nhất: server từ chối `INIT_CALL` kèm mã nguyên văn (428).

```java
// RuleVerdictEngine.decide — luật 3 áp lên kết quả của luật 1 và 2
return capByMissingClientLegs(decideBySignals(signals), signals);

// confidenceForSuccess — luật 2
if (availableSources.size() == 3      // đủ cả ba nguồn log
        && iceEverConnected           // WebRTC xác nhận media ĐÃ kết nối
        && !signalingTruncated)       // bản export không thiếu event
    return HIGH;
return MEDIUM;                        // không có log client nào thì đã ra UNKNOWN từ trước
```

**Lý do.** Bất đối xứng của bằng chứng. `FAIL` là khẳng định *"**thấy** X"* — thấy `ICE failed` là
xong, không cần nhìn chỗ khác. `SUCCESS` là khẳng định *"**không thấy gì cả**"*, mà không thấy có
hai nguyên nhân khác hẳn nhau: **không có lỗi để thấy**, hoặc **không có chỗ để nhìn**. Code
không phân biệt được hai cái đó, nên thay vì đoán, nó đếm xem đã nhìn được bao nhiêu chỗ — và khi
chỗ không nhìn được lại chính là chỗ kết luận cần, thì không kết luận.

Mỗi điều kiện bịt một lỗ hổng cụ thể:

| Điều kiện | Bỏ đi thì hỏng thế nào |
|---|---|
| Có log client (luật 1) | Lỗi media **vô hình** với signaling. `2D9057AA` không đính kèm file nào thì signaling vẫn hoàn hảo, trong khi ground truth là `FAIL` |
| Có `BYE` (luật 1) | MVP mục 4.1: `SUCCESS` là thiết lập được **và kết thúc bình thường**. Không có `BYE` thì không chứng minh được vế sau — cuộc gọi có thể rớt giữa chừng, hoặc bản export thiếu |
| Đủ ba nguồn (luật 2) | Thiếu end call log thì không có MOS, packet loss, RTT, jitter để loại trừ chất lượng kém |
| `iceEverConnected` (luật 2) | *Có* file WebRTC ≠ *có* bằng chứng. File không ghi chuyển trạng thái ICE nào thì không chứng minh được media từng chạy |
| `!signalingTruncated` (luật 2) | Kết luận dựa trên "không thấy dấu hiệu lỗi", mà event bị cắt **rất có thể chính là dấu hiệu đó** — và không có cách nào biết event nào bị mất |
| Đủ log hai leg (luật 3) | Không nhìn thấy phía bên kia thì không loại trừ được nguyên nhân nằm ở đó |

**Thay đổi so với Sprint 1.** Bản Sprint 1:

- chỉ có signaling → `SUCCESS` / `LOW`; không thấy `BYE` → `SUCCESS` / `MEDIUM`;
- chỉ hạ tin cậy khi giới hạn **đụng tới căn cứ của chính kết luận**. Lập luận khi đó: `311A9B6A`
  chưa từng gửi `INVITE`, callee không hề tham gia, nên thiếu log callee không làm kết luận yếu đi
  → giữ `HIGH`.

Mentor nhận xét: *"Độ tin cậy ở 311A9B6A đang là HIGH trong khi thiếu log callee. Nên hạ xuống
theo mức dữ liệu cho phép"* và *"Thiếu log client vẫn ra SUCCESS, và không thấy BYE cũng vẫn
SUCCESS"*. Lập luận Sprint 1 sai ở chỗ: nó dùng chính kết luận để phán phần dữ liệu thiếu là
"không liên quan". Độ tin cậy phải đi theo dữ liệu đã nhìn thấy, đúng như MVP mục 7.2: *"thiếu một
phần file → MEDIUM"*.

**Vì sao giữ ngoại lệ 428.** Kết luận dựa trên câu trả lời của **chính server** — `ACK` của
`INIT_CALL` kèm `callErrorCode: 428 … privacy_restricted`, đọc nguyên văn. Không log client nào
bổ sung hay phản bác được nó. **Ngoại lệ này chưa được mentor xác nhận.**

**Kết quả trên 20 cuộc** (`--analyze-all`, 2026-10-01). Verdict vẫn **13/13**: không cuộc nào
trong data rơi vào hai nhánh `UNKNOWN` mới — mọi cuộc đạt `OK_ACK_OK` đều có `BYE`, mọi cuộc đều
có ít nhất một log client. Thay đổi chỉ nằm ở độ tin cậy, 5 cuộc `HIGH` → `MEDIUM`:

| Call | Kết luận | Log client có của | Sprint 1 | Nay |
|---|---|---|---|---|
| `2D9057AA` | `FAIL` / `ICE_FAILURE` | callee | `HIGH` | `MEDIUM` |
| `703100CF` | `FAIL` / `TURN_FAILURE` | caller | `HIGH` | `MEDIUM` |
| `0A6C2821` | `FAIL` / `TURN_FAILURE` | caller | `HIGH` | `MEDIUM` |
| `311A9B6A` | `FAIL` / `SIGNALING_FAILURE` | caller | `HIGH` | `MEDIUM` |
| `45AA3011` | `FAIL` / `TURN_FAILURE` | caller | `HIGH` | `MEDIUM` |

`1B009D42`, `D114749E`, `9B556E56` cũng thiếu log callee nhưng giữ `HIGH` — cả ba là ca 428.
(`D114749E` có file tên `callee_webrtc.log` nhưng thật ra do máy caller ghi — xem mục 20.)

Đối chiếu 7 cuộc `SUCCESS` có nhãn:

| Call | Log client có của | Số **nguồn** | Bị cắt | Kết quả |
|---|---|---|---|---|
| `EE129C8F` | cả hai leg | 3 | không | `HIGH` |
| `70A1F889` | cả hai leg | 3 | không | `HIGH` |
| `C8CF631E` | cả hai leg | 3 | không | `HIGH` |
| `DE7DD314` | cả hai leg | 3 | **200/201** | `MEDIUM` |
| `6A7CE985` | cả hai leg (**chỉ WebRTC**) | 2 | không | `MEDIUM` |
| `F3D7914B` | cả hai leg (**chỉ WebRTC**) | 2 | không | `MEDIUM` |
| `5E0800AE` | **chỉ caller** (WebRTC) | 2 | không | `MEDIUM` |

Ba điều đọc ra từ bảng:

- `DE7DD314` là cuộc **duy nhất trong toàn tập** bị cắt bớt (1/20), và cũng là cuộc duy nhất
  đủ ba nguồn, đủ hai leg mà không đạt `HIGH`. Đủ file nhưng **dữ liệu không toàn vẹn**.
- `6A7CE985` và `F3D7914B` có đủ hai leg nhưng chỉ **2 nguồn**, vì cả hai file đều là WebRTC log.
  **Số file không bằng số nguồn.** Thiếu hẳn end call log nghĩa là không có MOS, packet loss,
  RTT, jitter.
- `5E0800AE` vừa thiếu nguồn vừa thiếu leg, nhưng cùng mức `MEDIUM` với hai cuộc trên: luật chỉ
  có một mức trần cho "thiếu một phần".

`271D1FAF` (`for_test/`, không có nhãn) ra `SUCCESS` kèm cờ chất lượng, độ tin cậy `LOW`: cờ chất
lượng luôn `LOW` vì ngưỡng chưa kiểm chứng được (xem "Còn để mở").

Khớp với bảng của MVP mục 7.2: đủ file + evidence mạnh → `HIGH`; thiếu một phần → `MEDIUM`;
evidence yếu hoặc mơ hồ → `LOW`.

> **Nguyên tắc rút ra:** verdict trả lời *bằng chứng nói gì*; độ tin cậy trả lời *đã nhìn
> được bao nhiêu dữ liệu để nói điều đó*. Thiếu dữ liệu thì hạ độ tin cậy — trừ khi phần thiếu
> chính là phần kết luận cần (media cho `SUCCESS`, `BYE` cho "kết thúc bình thường"): khi đó
> trả `UNKNOWN`. Kiểm bằng `RuleVerdictEngineTest`: `noClientLogAtAllIsUnknown`,
> `missingByeIsUnknown`, `missingCalleeLogCapsConfidenceAtMedium`, `serverRejectionIsNotCappedByMissingLeg`.

---

## Còn để mở

Năm mục từng để mở ở bản trước nay đã chốt — xem mục 11, 17, 19, 20, 21.

Những thứ còn lại, để sang Sprint 2:

- **Neo WebRTC log vào giờ tuyệt đối.** End call log có các dòng `onIceConnectionChange`,
  `onIceCandidate` lặp lại cùng nội dung với WebRTC log nhưng kèm timestamp tuyệt đối — ghép
  chuỗi candidate là ra offset. Chưa làm vì Sprint 1 không cần.
- **Ngưỡng chất lượng thật.** Đang là phỏng đoán; chỉ biết chắc phải cao hơn đường nền đo
  được (844 mẫu): loss 3,7% · MOS 4,335 · RTT 266 ms · jitter 28 ms.
- **Ngữ nghĩa của `CANCEL`.** Đã rõ ở mọi cuộc có end call log để kiểm: app tự huỷ khi hết 6
  giây chờ candidate (`_waitingCandidateTimer with error`, rồi `_emitFailed … code: 421`). Các
  cuộc CANCEL không có end call log chỉ có cùng dấu vân tay trong WebRTC log. Cuộc nào WebRTC log cho thấy TURN không
  cấp phát được lần nào thì xếp `TURN_FAILURE`; còn lại vẫn là `SIGNALING_FAILURE`.
