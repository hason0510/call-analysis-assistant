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

**Đánh đổi.** Nếu bản export được xuất lại với thứ tự event khác, `_id` sẽ đổi. Chấp nhận được với
data mẫu tĩnh dùng cho phát triển local.

**Cập nhật Sprint 2 — gửi theo lô (theo góp ý review Sprint 1).** Bản Sprint 1 gom mọi document vào
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
2. **Người review đọc được mà không cần đọc Java.**
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

**Quyết định.** Ba luật, cập nhật ở Sprint 2 theo góp ý review Sprint 1:

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

Góp ý review Sprint 1: *"Độ tin cậy ở 311A9B6A đang là HIGH trong khi thiếu log callee. Nên hạ xuống
theo mức dữ liệu cho phép"* và *"Thiếu log client vẫn ra SUCCESS, và không thấy BYE cũng vẫn
SUCCESS"*. Lập luận Sprint 1 sai ở chỗ: nó dùng chính kết luận để phán phần dữ liệu thiếu là
"không liên quan". Độ tin cậy phải đi theo dữ liệu đã nhìn thấy, đúng như MVP mục 7.2: *"thiếu một
phần file → MEDIUM"*.

**Vì sao giữ ngoại lệ 428.** Kết luận dựa trên câu trả lời của **chính server** — `ACK` của
`INIT_CALL` kèm `callErrorCode: 428 … privacy_restricted`, đọc nguyên văn. Không log client nào
bổ sung hay phản bác được nó. **Ngoại lệ này chưa được xác nhận.**

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

# Sprint 2

## 22. Cloud AI `gpt-4o-mini`, Structured Outputs `strict`, temperature 0

**Quyết định.** Gọi OpenAI `gpt-4o-mini` qua Chat Completions, đầu ra ép theo JSON Schema (`strict: true`),
`temperature 0`, timeout 30 s. Key đọc từ `.env`; không có key thì app vẫn chạy và lùi về rule.

**Lý do.** AI Provider Proposal được duyệt ngày 2026-10-05. Structured output strict nghĩa là model không trả được
trường lạ hay sai kiểu — Guardrails chỉ còn phải kiểm *nội dung*. `temperature 0` phục vụ Consistency ≥ 95% (MVP 6.5).
Chi phí đo thật: ≈ 0,0007 USD mỗi câu hỏi (2 lời gọi), cả benchmark 5 lần ≈ 0,20 USD — vừa ngân sách ~6 USD.

**Đánh đổi.** Kết luận ổn định qua 5 lần lặp, nhưng **phần chữ** thì không (cùng câu hỏi, có lần trích 22/22 evidence,
lần khác 14/22). Chấp nhận vì kết luận cuối do rule + Guardrails quyết (mục 23).

---

## 23. AI lệch rule thì kết luận là `UNKNOWN` — chọn cách chặt trong hai cách MVP cho phép

**Quyết định.** MVP 3.2 cho hai lựa chọn khi verdict AI lệch rule: "`UNKNOWN` hoặc gắn cờ cần kiểm tra". Code chọn
`UNKNOWN` + cờ cần kiểm tra + độ tin cậy LOW; tóm tắt do code viết ("Không đủ căn cứ để kết luận: AI kết luận …, rule
kết luận …"), không lấy chữ AI. Cùng verdict nhưng khác category thì giữ category của rule.

**Lý do.** Rule đúng 13/13 trên data có nhãn, AI chưa có bằng chứng tự suy luận đúng (AI thấy kết luận rule trong
context). In tóm tắt của AI cạnh dòng "Kết luận: UNKNOWN" thì report tự mâu thuẫn.

**Đánh đổi.** AI không bao giờ "cứu" được ca rule bó tay (ví dụ C02 trong bảng AI vs Rule của MVP 6.5: rule `UNKNOWN`,
AI `FAIL`, đúng là `FAIL`). Chưa có ca như vậy trong data để biết mất gì. Lựa chọn giữa hai cách còn để mở.

---

## 24. Context gửi AI là đối tượng có kiểu, chỉ `AiContextBuilder` tạo được

**Quyết định.** `AiContext` có constructor package-private; mọi chuỗi trong nó đi qua một phiên Sanitizer. Adapter tự
chọn định dạng gửi (JSON gọn). Guardrails lấy evidence ID và văn bản căn cứ cho G05 từ **chính context đã gửi**.

**Lý do.** Không lời gọi AI nào đi vòng qua bước làm sạch được — kiểu dữ liệu đảm bảo, không cần nhớ. Nếu bên gọi tự
truyền một danh sách evidence riêng cho Guardrails, hai danh sách có thể lệch: Guardrails kiểm theo thứ AI chưa từng thấy.

**Đánh đổi.** Không gửi được "thêm một trường nhanh" cho AI mà không sửa builder. Đó chính là điểm muốn có.

---

## 25. G05 khớp số nguyên văn; đổi đơn vị và làm tròn cũng là số không có trong input

**Quyết định.** G05 tách mọi con số trong chữ AI và đòi mỗi số có nguyên văn trong context đã gửi. `10942 ms` viết thành
`10.942` (giây) hay MOS `4.42397` viết thành `4.4` đều bị chặn. Prompt dặn chép nguyên văn số và đơn vị (quy tắc 4), và
với câu hỏi về khoảng thời gian thì không tự trừ mốc thời gian (quy tắc 4b).

**Lý do.** MVP 3.2 ("số liệu… giống nhau mọi lần chạy"), 3.3 ("AI không được tự tạo số liệu"), 6.5 ("khớp input"). Cùng
một câu hỏi, AI từng viết `10.942` (đổi đúng) và `11.942` (bịa) ở hai lần chạy — phép kiểm muốn cho đổi đơn vị thì phải
tự làm toán mới phân biệt được.

**Đánh đổi.** Đôi khi chặn nhầm một câu đúng → report lùi về rule (degraded): người dùng mất phần chữ AI, không bao giờ
thấy số sai. Đo được: 0-3% đầu ra thô bị chặn tuỳ lượt. Cách triệt để (Sprint 3): AI trích mã chỉ số `{Mxx}`, code điền — đã làm,
với mã theo tên thay cho số (mục 32).

---

## 26. Call-ID bằng regex, intent bằng AI có đường lui, câu từ chối cố định

**Quyết định.** Call-ID trích bằng regex UUID (hai Call-ID khác nhau thì không đoán). Intent và trọng tâm do AI phân
loại; AI lỗi hay trả sai thì lùi về bộ từ khoá (so trên chữ đã bỏ dấu). Câu `OUT_OF_SCOPE` nhận câu từ chối viết sẵn.

**Lý do.** MVP 3.2: tính được bằng code thì không giao AI; hiểu câu hỏi tự nhiên giao AI. Câu từ chối cố định thì không
bao giờ lỡ trả lời luôn câu ngoài phạm vi, và giống hệt nhau mọi lần.

**Đánh đổi.** Bộ từ khoá yếu hơn AI nhiều (83% so với 100% trên benchmark). Prompt phân loại từng đẩy nhầm câu ngắn,
không dấu ra ngoài phạm vi; đã sửa ngày 2026-10-06 và kiểm trên bộ câu mới viết trước khi sửa (`benchmark/intent-check.yaml`).

---

## 27. File lỗi bị loại riêng và nêu tên, không làm hỏng cả request

**Quyết định.** `FileValidator` xét từng file: quá lớn → rỗng → hỏng → không nhận diện được → end call log lệch Call-ID.
File không đạt bị loại khỏi phân tích và nêu tên ở "Giới hạn dữ liệu". File quá giới hạn không được nạp nội dung. Giới
hạn multipart của web (100 MB) đặt lớn hơn giới hạn mỗi file (20 MB).

**Lý do.** Một file sai không được làm mất phân tích của các file đúng (MVP 3.3: parser không được crash). Giới hạn
multipart nhỏ hơn thì cả request hỏng trước khi tới validator, người dùng không biết file nào gây lỗi.

**Đánh đổi.** WebRTC log không mang Call-ID nên không phát hiện được WebRTC log của cuộc khác.

---

## 28. Một Sanitizer cho cả ứng dụng; mã giả bằng HMAC; nhớ định danh trước rồi mới thay

**Quyết định.** Một instance Sanitizer dùng chung cho AI, report và Logback (`SanitizerHolder`). Định danh thay bằng mã
ổn định bằng HMAC có khoá (`CALL_ANALYSIS_PSEUDONYM_KEY`). Mỗi lần làm sạch mở một phiên: nhớ mọi định danh có cấu trúc
(thuộc tính event, cột TSV, khoá JSON) trước, rồi mới thay — cả trong văn bản tự do.

**Lý do.** Cùng một appUserId phải ra cùng một mã ở log lẫn report thì mới đối chiếu được. Hash trần thì không gian
appUserId nhỏ, ai cũng tính ngược được. Định danh xuất hiện lần đầu trong văn bản tự do (không có tên trường) thì regex
không nhận ra — phải nhớ từ chỗ có cấu trúc.

**Đánh đổi.** Logback khởi tạo trước Spring nên phải có một holder tĩnh. Đo leakage bằng một bộ đọc riêng (mục 31), không
tin vào chính Sanitizer.

---

## 29. Report luôn qua schema trước khi render; bố cục do code

**Quyết định.** Report JSON qua `report-v1.schema.json` (thêm ràng buộc chéo: kết luận từ rule thì không có phần phân tích
AI; degraded khi và chỉ khi có lý do fallback) rồi mới render. Report có phần AI mà sai schema → dựng lại từ rule. Bố cục
mẫu 4.5 nằm trong `ReportRenderer`; Web UI lấy nhãn và thứ tự mục qua `GET /api/report-layout`.

**Lý do.** MVP 6.1 T2: "render report theo template từ JSON đã validate". AI chỉ điền trường (MVP 3.2), không thể làm đổi
bố cục. Web và bản text cùng một nguồn bố cục thì không lệch nhau.

**Đánh đổi.** Thêm một vòng dựng lại report khi phần AI hỏng schema. Hiếm, và rẻ.

---

## 30. Evaluation Runner chạy qua đúng luồng Chat API, và dùng `call_id` của case

**Quyết định.** Mỗi câu hỏi của benchmark đi qua `ChatAnalysisService` như người dùng web. `call_id` của case được truyền
vào như khi người dùng ghi Call-ID trong câu hỏi; Call-ID ghi trong câu hỏi vẫn ưu tiên. Runner kiểm signaling trước khi
chạy và cảnh báo nếu ES tắt hoặc chưa nạp.

**Lý do.** Hệ thống được chấm phải là hệ thống người dùng dùng, không phải một đường riêng cho benchmark. WebRTC log không
mang Call-ID; không truyền `call_id` thì 5/13 cuộc mẫu (chỉ có WebRTC log) luôn ra `UNKNOWN`, và tập `held-out`
cũng có thể có ca như vậy. Không kiểm signaling trước thì ES tắt cho cả lượt toàn `UNKNOWN` mà không ai biết vì sao.

**Đánh đổi.** Đo trong điều kiện "đã biết Call-ID", thuận lợi hơn người dùng thật một chút. Điểm này còn chờ xác nhận.

---

## 31. Benchmark: không tự đặt nhãn, câu hỏi viết mới, đo leakage bằng giá trị gốc

**Quyết định.**
- Ground truth thiếu (category, evidence) để trống; metric tương ứng báo `N/A (lý do)`, không ghi 0%.
- Câu hỏi viết mới, viết trước khi xem hệ thống trả lời, không trùng câu dùng khi viết luật từ khoá hay prompt. Câu đọc
  được hai nghĩa thì không gắn nhãn intent.
- Security Leakage đo bằng cách gom **giá trị nhạy cảm gốc** của case bằng bộ đọc riêng rồi tra trong dữ liệu gửi AI và
  phản hồi — không dùng regex của Sanitizer.

**Lý do.** Tự ra đề rồi tự chấm thì con số vô nghĩa. Câu đã dùng để viết luật thì đo trên chính nó sẽ thổi phồng kết quả.
Dùng chung regex thì Sanitizer bỏ sót dạng nào, phép đo cũng bỏ sót đúng dạng đó.

**Đánh đổi.** Issue Category Accuracy chưa đo được cho tới khi ground truth có nhãn category. Phép đo leakage chưa gom IPv6 và giá
trị nằm trong câu hỏi.

---

## 32. AI nhắc chỉ số bằng mã theo tên chỉ số; code điền giá trị và tên (Sprint 3 T1a)

**Quyết định.** Mỗi chỉ số trong context gửi AI có mã theo tên `MetricKey` kèm leg (`SETUP_TIME`, `PACKET_LOSS_CALLER`,
`MOS_CALLEE`…). Prompt bắt AI nhắc giá trị chỉ số bằng mã trong ngoặc nhọn (`{PACKET_LOSS_CALLER}`), không tự chép số.
Mã lạ là G05. Lúc dựng report (`AnalyzeCallService.finalReport`), code thay mã bằng `giá trị [tên]`:
`1.83 % (cao nhất 11.32 %) [Packet loss (caller)]`. Kết quả AI giữ nguyên chữ có mã, để runner đếm được AI nhắc chỉ số
qua mã hay tự chép số (metric "Chỉ số AI nhắc qua mã").

**Lý do.** G05 chỉ kiểm **số có nguồn**, không kiểm **số được gán đúng nghĩa**: `271D1FAF` từng bị viết "mất gói của
caller là 11.3208%", trong khi đó là một mẫu stats, còn mất gói cả cuộc là 1.83 % (mục 25). Khi tên đi cùng giá trị thì
AI có chọn nhầm chỉ số, người đọc cũng thấy ngay tên sai chỗ.

- **Không đánh số `{M01}`, `{M02}`…** như kế hoạch ban đầu. Thử với AI thật: context có `M20` (trạng thái ICE của callee)
  thì AI trích evidence `EV20` không tồn tại ở 5/5 lần trên `2D9057AA` (EV01-EV13), kể cả với prompt cũ. Code trước T1a
  (context không có mã) thì không. Mã theo tên không có số để lẫn với `EVxx`, và AI chọn chỉ số theo nghĩa: bản đánh số từng dùng `{M16}` (MOS
  callee) để nói "không có gói audio".
- **Giá trị đứng trước, tên trong ngoặc vuông.** AI hay viết "Thời gian đổ chuông là {RINGING_TIME}": dạng `tên = giá trị`
  cho ra "Thời gian đổ chuông là Thời gian đổ chuông = 5987 ms".
- **Prompt không nêu ví dụ chữ sau khi thay.** Bản đầu nêu `"Thời gian đổ chuông = 10942 ms"` làm ví dụ, và AI chép đúng
  dạng đó bằng tay ("MOS (caller) = 4.42133") thay vì dùng mã.

**Đo** (2026-10-10, `dev` ×2, 132 lần chạy, prompt `e217a0b28a2d`): AI nhắc chỉ số qua mã 220/231 lần (95,2%); 11 số
tự chép trùng giá trị một chỉ số, ở 8/118 lần trả lời (cận trên). Unsupported Claim trước Guardrails 0/118, fallback 0,
Verdict / Consistency / Intent 100%. Token mỗi lời gọi phân tích 3 753 vào + 135 ra (mốc 3 172 + 149: mã và prompt dài
hơn, +18% token vào).

**Đánh đổi.** Câu có thể lặp tên ("MOS caller là 4.42133 [MOS (caller)]"). G05 vẫn cho qua số AI tự chép nếu số đó có
trong context: chặn hẳn sẽ đẩy thêm report về rule. Số AI tự chép được đo, không chặn. Số trong mô tả evidence (mẫu
stats) vẫn do AI chép, và prompt chỉ dặn đó là số của một mẫu.

---

## 33. Evidence mang cờ "căn cứ của rule"; AI chỉ trích căn cứ (Sprint 3 T1b)

**Quyết định.**
- `Evidence` có thêm `ruleBasis`: dòng nằm trong `SignalExtractor.basis()`, tức dòng mà một tín hiệu lỗi của rule dựa
  vào. Cờ này đi vào context AI.
- `basis()` thêm dòng ICE chuyển sang `failed` và call summary không có byte audio nào. Evidence Engine vốn đã chọn hai
  dòng này làm mốc, nên danh sách evidence không đổi, chỉ cờ đúng hơn. Trước đó ca ICE_FAILURE (`2D9057AA`) không có
  dòng căn cứ nào.
- Prompt: `evidenceIds` là căn cứ, thường 2 tới 6 dòng. Đồng ý với rule thì trích các dòng `ruleBasis`. Mốc bình thường
  chỉ trích khi kết luận dựa vào chính nó.
- Runner đo ba thứ: số evidence trích trên tổng, lần trích hết, lần trích đủ căn cứ của rule; và độ trùng (Jaccard)
  giữa các lần lặp cùng câu.

**Lý do.** Sprint 2 có lần AI trích 22/22 evidence, nên chip "Căn cứ" trên Web UI mất tác dụng. MVP 3.3 đòi kết luận
trace được về evidence: trích tất cả thì không chỉ ra được dòng nào là căn cứ.

**Đánh đổi.** Cờ `ruleBasis` nghiêng AI về phía rule. Chấp nhận vì kết luận cuối vẫn do rule + Guardrails quyết (mục 23),
và cờ chỉ nói dòng nào rule đã dùng, không nói AI phải đồng ý. Ca SUCCESS không cờ chất lượng không có dòng căn cứ: AI tự
chọn theo hướng dẫn của prompt. Không có Guardrail cho việc trích quá nhiều, vì đây chỉ là chuyện chất lượng, không phải
sai; số liệu được đo, không chặn.

**Đo** (2026-10-10, `dev` ×2, 132 lần chạy; trước = prompt T1a `e217a0b28a2d`, sau = `445a72ff32fd`):

| | Trước | Sau |
|---|---|---|
| Evidence AI trích, trung bình / tổng | 5,2 / 12,4 | 3,2 / 12,4 |
| Nhiều nhất một lần | 12 | 6 |
| Trích hết mọi evidence | 21/118 | 5/118 (cuộc có 3-4 evidence) |
| Trích đủ dòng căn cứ của rule | 26/32 | 40/46 (thêm căn cứ ICE) |
| Giống nhau giữa các lần lặp (Jaccard) | 89,5% · 45/59 câu giống hệt | 95,4% · 49/59 |
| Unsupported Claim · fallback | 0 · 0 | 0 · 0 |
| Token vào mỗi lời gọi phân tích | 3 753 | 4 075 (+8,6%) |

Bản nháp đầu của prompt có ví dụ "câu hỏi về thời gian đổ chuông dựa vào RINGING, OK". Ví dụ đó làm `D114749E` tái phát
G05 `0.112` (AI tự trừ hai mốc) ở 2/2 lần. Bỏ ví dụ và nói rõ ở quy tắc 4b: "không viết độ dài thời gian tự tính" thì hết.

## 34. Lỗi thân thiện và chịu lỗi: 429, câu hỏi hỏng mã hoá, ES tắt, sanitizer chậm (Sprint 3 T4)

**Quyết định.**
- **HTTP 429 của OpenAI tách thành hai lý do fallback:** `QUOTA_EXCEEDED` (`error.code = insufficient_quota`, chờ không
  giải quyết được, phải nạp thêm) và `RATE_LIMITED` (còn lại, thử lại sau ít phút). Trước đây cả hai là `PROVIDER_ERROR`,
  banner chỉ ghi "không gọi được dịch vụ AI". Chỉ đọc `error.code`, phần còn lại của thân lỗi không đi đâu. Không tự gọi
  lại: một lần thử lại cộng thêm tới 30 s vào thời gian chờ, mà fallback về rule đã cho report dùng được.
- **Câu hỏi hỏng mã hoá được phát hiện** (`GarbledText`): ký tự U+FFFD; từ hai chỗ `?` nằm giữa chữ cái (`g?i`); từ hai
  chỗ UTF-8 bị đọc như Windows-1252 (`Ã¡`). Chuẩn hoá NFC trước khi kiểm. Phát hiện thì thêm một dòng cố định vào
  "Giới hạn dữ liệu". Nếu câu bị xếp ngoài phạm vi thì **vẫn từ chối**, câu từ chối kèm lời nhắc gửi lại.
- **`--parse-*` không dừng khi ES tắt:** như `--analyze`, parse tiếp log đính kèm và nêu lý do thiếu signaling.
- **`SensitiveDataSanitizer.policyForKey` tra bảng dựng sẵn** thay cho duyệt cả inventory. Kết quả giữ nguyên
  equalsIgnoreCase: tên có ký tự ngoài ASCII (`ı` không chấm) đi đường duyệt cũ.
- **File vượt giới hạn vài byte nêu số byte:** hai số làm tròn tới 0,1 MB bằng nhau thì câu cũ ra "dung lượng 20.0 MB vượt
  giới hạn 20.0 MB".
- **U+FFFD trong mã nguồn viết bằng mã số** (`0xFFFD`), không viết ký tự: editor đọc sai bảng mã sẽ đổi ký tự đó mà
  không ai thấy. Sửa cả chỗ có từ Sprint 2 trong `FileValidator`.

**Lý do.**
- Câu hỏi hỏng mã hoá: ca demo 2026-10-06 (curl trên Git Bash gửi byte ANSI) bị từ chối mà người dùng không biết vì sao,
  và suýt dẫn tới sửa prompt không cần thiết. Chữ đã thành `?` thì không khôi phục được, chỉ báo được.
- Sanitizer: tìm ra khi thử request đồng thời. `AnalyzeCallService.sanitize` gọi `policyForKey` cho **mọi thuộc tính của
  mọi event**; cuộc `DE7DD314` đủ 4 file có rất nhiều thuộc tính (hàng trăm trường mỗi mẫu stats), mỗi lần duyệt
  19 mục × 68 tên. Thread dump chỉ đúng chỗ này.

**Đánh đổi.**
- Câu hỏng mã hoá bị xếp ngoài phạm vi vẫn bị từ chối thay vì đoán là câu phân tích: đoán thì chuỗi `?` vô nghĩa thành
  đường vòng qua OUT_OF_SCOPE. Người dùng mất một lượt gửi lại, nhưng biết phải sửa gì.
- Ngưỡng hai chỗ có thể bỏ sót câu rất ngắn chỉ mất một chữ; chấp nhận vì một `?` lẻ giữa chữ có thể là gõ nhầm.

**Đo** (2026-10-11, máy cá nhân, `spring-boot:run`, không khoá AI):

| | Trước | Sau |
|---|---|---|
| Một request `DE7DD314` 4 file (server ghi `latency_ms`) | 9 405 ms | 977 ms |
| 10 request đồng thời, cùng 4 file (thời gian phía curl) | 12,5 s mỗi request | 2,2 s |
| Report của 23 lần trên (trừ `requestId`) | 1 nội dung | 1 nội dung, trùng bản trước |
| `dev` ×1 không khoá AI: latency pipeline P50 / P95 | — | 163 / 890 ms |

Cả 4 case chậm nhất ở mọi lượt benchmark trước (case-002, 015, 016, 017) đều là `DE7DD314`. P95 đã báo (8,1 s ở mốc;
14-16 s tối 10/10) phần lớn là chi phí này, không phải AI. So sánh trước / sau giữa các lượt vẫn đúng vì lượt nào cũng
chịu cùng chi phí. Con số tuyệt đối cần đo lại bằng lượt có AI.

File sát 20 MB (WebRTC log 20 MB − 1 KB, 111 nghìn event): report sau 6,9 s; ba request như vậy đồng thời đều xong
(7,1 s), heap dùng khoảng 950 MB / 1,6 GB. File 20 MB + 184 byte bị loại và nêu tên.

## 35. Độ tin cậy gom về một `ConfidencePolicy`, report ghi lý do (Sprint 3 T3)

**Quyết định.**
- `domain/confidence/ConfidencePolicy` là nơi duy nhất quyết định HIGH / MEDIUM / LOW, theo đúng bốn yếu tố của MVP 7.2:
  độ mạnh bằng chứng, dữ liệu thiếu, AI khớp hay lệch rule.
- Mỗi nhánh của `RuleVerdictEngine` chỉ khai báo `ConfidenceBasis`: bằng chứng `STRONG` / `PARTIAL` / `WEAK` kèm lý do,
  và kết luận nhạy với gì (dựa trên việc không thấy sự kiện → nhạy với bản export bị cắt; SUCCESS → cần đủ ba nguồn;
  server từ chối INIT_CALL → không nhạy với thiếu log client). Dữ liệu thiếu (`DataCoverage`) policy tự đọc từ tín hiệu.
- Hai bước: `forRule` (rule cần mức của mình trước khi có AI — gửi sang AI làm mốc, là kết quả khi AI lỗi) và
  `forReport` (AI khớp → giữ; AI lệch → LOW; không có AI → tối đa MEDIUM).
- Report có trường `confidenceReasons` (schema bắt buộc, ít nhất một lý do); dòng "Độ tin cậy" in lý do sau dấu gạch:
  *"MEDIUM — thiếu log client của caller; bằng chứng rõ: ICE chuyển sang failed, không có byte audio nào"*. Yếu tố làm
  mức thấp đi đứng trước. Web UI hiện lý do cạnh thanh mức.
- Các câu "độ tin cậy tối đa MEDIUM vì…" bỏ khỏi "Giới hạn dữ liệu": lý do đã ở dòng đầu, giữ cả hai là nói hai lần.

**Lý do.** Trước đây mức gán ở 12 nhánh của engine, một bước hạ mức riêng khi thiếu log client, và hai chỗ trong
`ReportBuilder`. Trả lời "vì sao report này MEDIUM" phải đọc ba nơi; report chỉ nói lý do ở hai trường hợp hạ mức.

**Không đổi kết quả** (2026-10-11):
- `--analyze` trên 20 cuộc gọi mẫu + 2 biến thể (rule, không AI): mức của cả 22 report giữ nguyên. Khác biệt duy nhất là
  dòng "Độ tin cậy" thêm lý do và mất câu "Độ tin cậy tối đa MEDIUM vì thiếu log client…" ở 5 report.
- `dev` ×1 không khoá AI (nhánh fallback của report), chạy trên code trước T3 (worktree tạm) và sau T3: 66/66 lần chạy
  trùng verdict, category, cờ chất lượng, mức tin cậy, kết luận rule, Template Compliance, lý do fallback.
- Nhánh có AI (khớp → giữ, lệch → LOW) giữ đúng logic cũ, kiểm bằng `ReportDecisionTest` có từ Sprint 2; chưa chạy
  lượt có AI.

**Đánh đổi.**
- Dòng "Độ tin cậy" dài hơn mẫu 4.5 (mẫu chỉ có mức). Template Compliance chỉ kiểm nhãn đầu dòng nên vẫn đạt; mức vẫn đứng
  ngay sau nhãn.
- Context gửi AI mất câu "Độ tin cậy tối đa MEDIUM vì thiếu log client…" ở các cuộc thiếu một leg (câu đó nằm trong
  `dataLimitations` của rule). AI không quyết định độ tin cậy nên không ảnh hưởng kết luận; lượt có AI sau sẽ đo.

**Còn mở.** Luồng CLI chỉ rule (`--analyze`, không thử AI) giữ mức của rule như Sprint 1, nên có thể ra HIGH; còn report
có thử AI mà AI lỗi thì tối đa MEDIUM. Bảng 7.2 đòi "AI khớp rule" cho HIGH. Sản phẩm (Chat API, Web UI, runner) luôn thử
AI nên không gặp; chỉ công cụ CLI Sprint 1. Chưa đổi để không làm lệch số liệu Sprint 1.

## 36. Log có cấu trúc: JSON ở server, trường key-value, dòng AI call đúng bảy trường (Sprint 3 T5)

**Quyết định.**
- Trường log đi bằng key-value của SLF4J (`log.atInfo().addKeyValue(…)`), không ghép vào câu. Một sự kiện, hai cách in:
  - CLI (mặc định): log chữ, `%skvp` in `khoá=giá trị` sau thông điệp — report của CLI in qua log nên phải đọc được.
  - Profile `web`: JSON một dòng mỗi sự kiện, `SanitizedJsonLogFormatter` (`logging.structured.format.console`).
    Thêm profile `plain-log` để xem log chữ khi demo.
- Formatter JSON tự viết thay cho ECS / Logstash có sẵn của Spring Boot: hai định dạng đó ghi thông điệp và trường
  nguyên văn, bỏ qua lớp làm sạch. Ở đây thông điệp, giá trị trường, MDC và stack trace đều qua sanitizer.
- Dòng **AI call** (MVP 6.2) do một chỗ dựng (`AiCallLog`), thành công hay lỗi đều đúng một dòng, chỉ các khoá
  `request_id`, `call_id`, `model`, `latency_ms`, `result_status`, `token_usage`, `fallback_reason`. Test khẳng định
  tập khoá. Trước đây lời gọi lỗi không có dòng AI call nào; dòng fallback ở tầng service thiếu model và token.
- `call_id` là mã giả HMAC (`CALL_xxxxxx`, `SensitiveDataSanitizer.pseudonymizeCallId`): cùng cuộc gọi cùng mã, nối
  được các dòng mà không ghi Call-ID gốc. Mã nằm trong `AiContext` nhưng không đi vào payload gửi AI.
- `request_id` đặt vào MDC suốt request: dòng của adapter không biết request (truy vấn Elasticsearch) vẫn mang mã.
- Latency từng bước: dòng `Request done` có `intent_ms`, `analysis_ms` (parse + ES + rule), `ai_ms` (AI + Guardrails),
  `report_ms`; dòng `Signaling fetch` có `signaling_ms`; dòng `AI verdict` có `guardrails_ms`.

**Đánh đổi.** Log JSON khó đọc bằng mắt khi demo — vì vậy có `plain-log`. Runner (`--evaluate`) chạy kiểu CLI nên phép
đo leakage trong application log (T6) quét bản chữ; formatter JSON có test riêng cho việc làm sạch.

**Thử thật** (2026-10-11, profile `web`, không khoá AI, một request `2D9057AA`): 9/9 dòng là JSON hợp lệ; dòng truy
vấn ES mang `request_id` qua MDC; `Request done`: `intent_ms` 45, `analysis_ms` 1 769 (trong đó `signaling_ms` 881),
`ai_ms` 116, `report_ms` 217.

## 37. Đề xuất chuẩn của rule luôn giữ, AI chỉ bổ sung; phân tích trả lời đủ bốn câu (Sprint 3 T2)

**MVP 7.1 T2** đòi hai thứ: bộ đề xuất chuẩn theo issue category; phân tích trả lời được chuyện gì xảy ra, vì sao kết
luận vậy, evidence nào hỗ trợ, cần điều tra gì tiếp. Không cần nhãn mới — dựa trên category rule đã kết luận.

**Quyết định.**
- **Bốn câu, bốn dòng cố định**, có cả khi không có AI (report dựng từ rule):
  chuyện gì xảy ra = dòng "Chính"; vì sao kết luận = dòng mới "Vì sao kết luận" (lý do của rule, do code viết — AI lệch
  verdict thì nói AI và rule không thống nhất); evidence nào = dòng mới "Căn cứ của rule" (evidence `ruleBasis`), dòng
  của AI đổi tên "Căn cứ AI trích"; cần điều tra gì tiếp = mục Đề xuất.
- **Đề xuất chuẩn luôn giữ, AI chỉ bổ sung.** `suggestions` là bộ chuẩn của rule; `aiSuggestions` (tối đa 3) là phần AI
  thêm, câu trùng bộ chuẩn (so sau khi bỏ dấu câu, hoa thường) bị bỏ. Trước đây đề xuất AI thay hẳn — với `703100CF`
  (chưa request TURN nào rời máy) AI từng khuyên "kiểm tra cấu hình TURN server".
- **SIGNALING_FAILURE theo nguyên nhân con** (`SignalingCause`, 6 loại). Ba cuộc bị server từ chối mã 428 trước đây nhận
  "đối chiếu số lần gửi lại INVITE với cấu hình timeout"; nay nhận "đây là quyết định của server, không phải lỗi mạng;
  không cần điều tra mạng, ICE hay TURN".
- Context gửi AI mang `standardSuggestions`; prompt quy tắc 9-10: analysis giải thích vì sao + evidence nào, suggestions
  chỉ là phần bổ sung, không khuyên hướng mà evidence không ủng hộ. Bộ chuẩn cũng vào căn cứ của G05.

**Đo** (2026-10-12):
- `--analyze` 22 report (rule, không AI), trước / sau: kết luận, cờ, độ tin cậy, tóm tắt không đổi ở report nào; "Vì sao
  kết luận" 22/22; "Căn cứ của rule" 14/22 (8 cuộc bình thường không có dòng lỗi); đề xuất đổi ở 5 cuộc SIGNALING_FAILURE.
- `dev` ×1 có AI (prompt `d1f62cfe52ec`, ~0,05 USD): Verdict / Consistency / Intent / Template 100%, leakage 0, P50 2,7 s
  · P95 4,3 s. **Lượt đầu tìm ra lỗi**: case-008 (`E9D6C112`) bị G05 chặn 3/3 vì AI nhắc "cổng 3478" — số nằm trong đề
  xuất chuẩn vừa gửi AI nhưng chưa vào căn cứ của G05. Sửa (thêm bộ chuẩn vào `groundingTexts`), chạy lại case-008: 3/3
  qua Guardrails. Token phân tích vào 4 072 → 4 296 (+5,5%).
- `--ai-analyze` 5 cuộc để đọc đề xuất: AI thường **chép lại** bộ chuẩn dù prompt dặn không — bộ lọc trùng bỏ được câu
  chép nguyên văn (`703100CF`, `2D9057AA`), không bỏ được câu diễn đạt lại (quan sát trên `271D1FAF` thuộc `for_test/` —
  không dùng để chỉnh bộ lọc).

**Đánh đổi / còn mở.**
- Đề xuất AI diễn đạt lại bộ chuẩn có thể lọt vào "Bổ sung (AI)". Không so ngữ nghĩa: cần ngưỡng, mà chỉnh ngưỡng cần
  dữ liệu `dev` có nhãn đề xuất — chưa có.
- Đề xuất cho `NETWORK_PACKET_LOSS` / `NETWORK_DELAY_JITTER` chưa kiểm chứng trên ca thật (không có cuộc chất lượng kém
  có nhãn — cùng lý do hai category này còn UNVALIDATED).

## 38. Thử lại có kiểm soát; tách lỗi HTTP 4xx khỏi PROVIDER_ERROR (Sprint 3 T8)

**Quyết định.**
- **Chỉ thử lại lỗi tạm thời**: `TIMEOUT` và `PROVIDER_ERROR` (mất mạng, provider lỗi 5xx). Không thử lại 429, khoá sai,
  yêu cầu bị từ chối, câu trả lời sai định dạng, bị Guardrails loại (temperature 0 — hỏi lại gần như ra cùng câu).
- **Giới hạn:** tối đa 2 lần thử, chờ 500 ms giữa hai lần, ngân sách **45 s cho cả một lời gọi**. Lần sau chỉ dùng
  phần ngân sách còn lại (lần đầu quá giờ ở 30 s thì lần sau còn ~14,5 s); còn dưới 2 s thì thôi. Bị ngắt (server tắt,
  request huỷ) thì dừng ngay. Quyết định nằm ở `RetryPolicy` (domain, không ngủ, không gọi mạng); `OpenAiChatClient`
  thực hiện.
- **Phân loại lỗi HTTP** (trước đây mọi mã khác 2xx và 429 đều là `PROVIDER_ERROR`): 401/403 → `AUTH_FAILED`; 400 có
  `error.code = context_length_exceeded` → `INPUT_TOO_LARGE`; 4xx khác → `REQUEST_REJECTED`; 5xx và lỗi mạng →
  `PROVIDER_ERROR`. Chỉ đọc mã HTTP và `error.code`.
- **Log:** mỗi lần thử hỏng được thử lại ghi thêm một dòng `AI call` với `result_status=RETRY`, lý do ở
  `fallback_reason` — vẫn trong bảy trường MVP 6.2. Dòng kết quả cuối như cũ.
- Trình duyệt chờ 150 s (trước 120 s): tổng ngân sách các bước là 45 + 5 + 30 + 45 = 125 s.

**Lý do.** MVP 7.1 T8 "controlled retry, phân loại lỗi". Phân loại có từ T4 nhưng gộp lỗi cố định với lỗi tạm thời
vào một mã, nên không quyết được có nên thử lại. Câu hỏi vượt cửa sổ ngữ cảnh của model trước đây chỉ hiện "không
gọi được dịch vụ AI" (README, Known Limitations).

**Đánh đổi.** Trên 1 044 lần chạy có AI đã lưu, chưa gặp lỗi tạm thời nào — trên `dev`, thử lại chưa từng có ích; giá
trị của nó là lúc chạy thật khi mạng hoặc provider chập chờn. Thử lại kéo dài thời gian chờ xấu nhất (ngân sách 45 s
thay vì 30 s mỗi lời gọi).

**Kiểm** (2026-10-12): 26 test mới (server giả trên 127.0.0.1: 5xx rồi thành công, 5xx liên tục dừng sau 2 lần, lỗi cố
định một request, quá giờ rồi thành công trong ngân sách, hết ngân sách không thử). Chạy thật: base-url cổng chết → một
dòng `RETRY` rồi `FALLBACK PROVIDER_ERROR` sau ~0,55 s; **API OpenAI thật** với khoá giả → `AUTH_FAILED`, không thử lại;
câu hỏi 1 MB với khoá thật → `INPUT_TOO_LARGE` ở cả hai lời gọi, report lùi về rule với đúng lý do (request bị từ chối,
không tính phí).

## 39. Mỗi request một bản ghi vào Elasticsearch, xem trên Kibana (Sprint 3 T9)

**Quyết định.**
- Chat API ghi mỗi request một tài liệu vào index `analysis-requests` (mapping `dynamic: strict`): kết quả (REPORT /
  OUT_OF_SCOPE / INVALID_REQUEST / ERROR), intent, verdict, category, độ tin cậy, nguồn kết luận, có lùi về rule không và
  vì sao, latency từng bước, token, tên lớp exception khi lỗi.
- **Chỉ mã, số và cờ** — không câu hỏi, không tóm tắt, không thông điệp lỗi. `call_id` là mã giả. MVP 6.2 đặt "dữ liệu
  ghi vào ES local" dưới Output Sanitizer; không chở chữ tự do thì không có gì để lộ, và test khẳng định điều đó.
- Ghi bất đồng bộ trên một luồng nền, hàng đợi 1 000; đầy thì bỏ bản ghi, Elasticsearch lỗi thì cảnh báo một lần cho
  mỗi đợt lỗi. Request của người dùng không chậm đi và không hỏng vì Elasticsearch.
- Chỉ bật ở profile `web` (`call-analysis.request-log.enabled`): lượt benchmark và lệnh CLI không ghi, Kibana chỉ thấy
  request thật. Cổng `RequestRecordSink`; không có adapter thì không ghi.
- `scripts/kibana/setup_views.py` tạo data view và 4 saved search (tất cả, fallback, lỗi, chậm > 10 s); id cố định,
  chạy lại được.

**Lý do.** MVP 7.1 T9: "Ghi log và kết quả phân tích vào ES local; Kibana view cho request, latency, fallback, lỗi". Log
JSON (mục 36) đọc được bằng máy nhưng nằm ở console; Kibana cần dữ liệu trong Elasticsearch.

**Đánh đổi.** Không ghi từng dòng log, chỉ bản tóm tắt của request: đủ cho bốn thứ MVP nêu, và không phải đối chiếu từng
dòng log với Output Sanitizer. Bản ghi có thể mất nếu ứng dụng tắt đột ngột hoặc hàng đợi đầy — chấp nhận vì đây là dữ
liệu quan sát, không phải dữ liệu nghiệp vụ.

**Kiểm** (2026-10-12): 4 test mới (tài liệu khớp mapping, không trường chữ tự do, mỗi request một bản ghi kể cả lỗi).
Chạy thật: 6 request (có AI, không khoá AI, ngoài phạm vi, không file) → 6 tài liệu, index tự tạo đúng mapping; script
tạo đủ data view và 4 saved search; saved search "fallback" trên Kibana hiện đúng bản ghi `NOT_CONFIGURED`.

## 40. Ca FAIL: dòng Tóm tắt do code viết từ lý do của rule (dataset mới, 2026-10-14)

**Quyết định.** Khi AI khớp kết luận với rule và kết luận là **FAIL**, dòng "Tóm tắt" của report là câu code viết từ lý
do của rule ("Cuộc gọi không thành công. <lý do>."), không lấy `summary` của AI. AI vẫn viết phần phân tích (trả lời
trọng tâm câu hỏi trước) và đề xuất bổ sung; kết luận AI vẫn được đối chiếu với rule như cũ. SUCCESS và UNKNOWN giữ
tóm tắt của AI.

**Vì sao.** Trên `ai20k_new_dataset`, hai cuộc FAIL ĐÃ thiết lập rồi mới mất dữ liệu (`70C388AD` nhận ~0,7 s âm thanh
rồi im 12 s; `FE4EF5E7` nói ~30 s rồi rớt). `gpt-4o-mini` mở đầu tóm tắt bằng "Cuộc gọi không thiết lập được…" ở 10/10
lần chạy, kể cả sau khi thêm quy tắc 9a vào prompt và sửa định nghĩa ICE_FAILURE ("…hoặc đã thiết lập rồi không duy trì
được") — có lần còn viết "không thiết lập được do ICE không duy trì được kết nối", tự mâu thuẫn. Guardrail không bắt
được vì câu không bịa số, không sai evidence ID. MVP 3.2: phần code tính được thì không giao cho AI; câu "vì sao hỏng"
chính là lý do của rule, vốn đã có dòng căn cứ trong evidence.

**Đánh đổi.** Tóm tắt ca FAIL mất phần trả lời trọng tâm câu hỏi — phần đó nằm ở "Phân tích". Lỗi diễn giải trong phần
phân tích (gắn mã chỉ số vào câu "không nhận được âm thanh", ghi nhầm bên của `network_lost`) vẫn còn: đó là phần MVP
giao cho AI, ghi ở Known Limitations. Cùng cơ chế với ca AI lệch rule (mục 23), code đã tự viết tóm tắt từ trước.

## 41. Cài `NETWORK_DELAY_JITTER` theo ngưỡng Microsoft Teams CQD (2026-10-14)

**Quyết định.** Một leg bật cờ độ trễ khi nhận > 500 gói và RTT (`transport.currentRttMs`) TRUNG BÌNH > 500 ms hoặc
jitter (`audio.jitter`) TRUNG BÌNH > 30 ms, tính trên các mẫu stats đã có âm thanh. Xét SAU `NETWORK_PACKET_LOSS`
(MVP mục 10 scenario 2: "packet loss ↑, RTT ↑" → NETWORK_PACKET_LOSS). Độ tin cậy LOW (`ConfidenceBasis.weak`).
Evidence: mẫu có RTT cao nhất của mỗi leg vượt ngưỡng là dòng căn cứ; mô tả mẫu stats in thêm RTT và jitter.

**Vì sao ngưỡng này.** Ngưỡng cũ 300 ms / 50 ms là phỏng đoán. Đã kiểm nguồn gốc (2026-10-14):
- Microsoft Teams CQD (Microsoft Learn, cập nhật 06/2026): luồng âm thanh "Poor" khi > 500 gói và RTT trung bình > 500
  ms, mất gói trung bình > 10 % hoặc jitter trung bình > 30 ms — một bộ PHÂN LOẠI cấp luồng, đo đúng loại số liệu end
  call log có, dùng trung bình nên không bật cờ vì một mẫu. Microsoft tự ghi chú vượt ngưỡng chưa chắc người dùng nghe
  thấy vấn đề.
- Twilio Voice SDK: cảnh báo RTT > 400 ms / jitter > 30 ms ở 3/5 mẫu liên tiếp. Áp lên toàn bộ data cho cùng kết quả
  cuối (chỗ hai bộ khác nhau — FE4E4BDC cuộc 9 giây, 06BCA48C một đợt jitter — đều đã bị luật mất gói bắt trước).
- ITU-T G.114 (05/2003, vẫn hiệu lực; Amd.2 11/2009 chỉ thêm phụ lục): trễ MỘT CHIỀU > 400 ms không chấp nhận được cho
  quy hoạch mạng, < 150 ms gần như trong suốt. 3GPP TS 23.203 QCI 1: ngân sách trễ 100 ms, mất gói 10⁻² — là ngân sách
  thiết kế bearer, không phải tiêu chí phân loại cuộc gọi.

**Không đổi luật mất gói.** Các nguồn vênh nhau xa (Cisco / 3GPP 1 %, Twilio > 1 % ở 3/5 mẫu, Teams 10 % trung bình);
theo Teams thì B5F19381 (mất gói 8,48 %, MOS thấp nhất 1,94) thành "Good". Không có data có nhãn để chọn.

**Ảnh hưởng.** Data `dev` và `for_test` không cuộc nào chạm ngưỡng (RTT trung bình cao nhất 126 ms, jitter trung bình
cao nhất 24 ms) — kết luận không đổi, 13/13. Dataset mới: chỉ FC3187B4 đổi, từ SUCCESS không cờ sang SUCCESS +
NETWORK_DELAY_JITTER (RTT trung bình 538 / 547 ms cả cuộc).


## 42. Hardening theo nhận xét Sprint 2 của mentor (2026-10-15)

**Quyết định.**
- **Profile web chỉ nghe `127.0.0.1`.** Chat API không có đăng nhập mà mỗi request gọi OpenAI có trả phí; nghe mọi
  interface thì ai trong mạng cũng tiêu được tiền của chủ key. Demo qua LAN thì bật tay `--server.address=0.0.0.0`.
  Cổng mặc định 8081 (`bf0114b`): trên máy phát triển, cổng 8080 bị dịch vụ Windows `AgentService` giữ thường trực.
  README, runbook và kịch bản demo trước đó vẫn ghi 8080 — nay sửa theo 8081.
- **Tối đa 10 file mỗi request** (`call-analysis.files.max-files-per-request`). Kiểm ở controller, TRƯỚC khi đọc nội dung
  file và trước lời gọi AI phân loại, nên request kiểu này không tốn bộ nhớ hay tiền AI. 10 = 4 file chuẩn của một cuộc
  gọi + dư cho leg nhiều thiết bị (dataset mới `fc3187b4`: 6 file). Web UI biết giới hạn qua `/api/report-layout` và
  tắt nút gửi trước khi tới server.
- **Web UI không ghi đè file trùng tên.** Trước đây `Map` theo tên file nên kéo `callee_endcall.log` của máy thứ hai vào
  là mất file của máy thứ nhất, không báo gì. Nay: cùng tên + cùng cỡ + cùng thời điểm sửa là cùng một file (bỏ qua);
  cùng tên mà khác file thì giữ cả hai và cảnh báo. Server không phải đổi: loại file và Call-ID nhận theo nội dung,
  dedupe ở mức file (`docs/bay-du-lieu.md` bẫy #15).
- **IP ví dụ theo RFC 5737 / RFC 3849.** IP công cộng thật (lấy từ data mẫu, gồm địa chỉ TURN server) nằm trong tài
  liệu, `sensitive-data-inventory.yaml` và test; nay là `192.0.2.x`, `198.51.100.x`, `203.0.113.x`, `2001:db8::`.
  Sanitizer và bộ đo leakage coi mọi IPv4 (trừ 0.0.0.0, 255.255.255.255, 127.x) là địa chỉ thật, nên test giữ nguyên ý
  nghĩa. Lịch sử git cũ vẫn còn IP thật — không viết lại lịch sử.
- **`.gitignore` liệt kê từng file riêng** thay cho chặn `*.md` kèm danh sách ngoại lệ, để tài liệu mới không bị bỏ sót
  khi commit. Thêm `benchmark/results/` và `docs/*.patch`: trước đây không bị bỏ qua, `git add .` sẽ kéo vào.

**Ảnh hưởng.** Không đổi logic kết luận. Test: 534 (thêm `AnalyzeControllerTest.tooManyFilesIsRejectedBeforeAnalysis`).
Request bị từ chối vì quá số file chỉ để lại một dòng log `TOO_MANY_FILES`, không có bản ghi Kibana.


## 43. G05 bắt số viết liền đơn vị (góp ý mentor Sprint 2, 2026-10-15)

**Quyết định.** Regex số của G05 nhận số có đơn vị viết liền phía sau: `ms`, `s`, `sec`, `min`, `h`, `kbps`, `mbps`,
`bps`, `kb`, `mb`, `gb`, `hz`, `khz`, `fps` (không phân biệt hoa thường). Vi phạm ghi cả đơn vị (`250ms`) cho dễ đọc.

**Vì sao.** Regex cũ đòi sau số không có chữ cái, nên `250ms`, `45s` không được xét — "Unsupported Claim 0%" chỉ là
cận dưới (mentor). Mới chỉ nhận đơn vị trong danh sách, và mỗi đơn vị có ít nhất một chữ cái KHÔNG thuộc hệ hex: mảnh
Call-ID / UUID như `48B2`, `5D`, `12DB` vẫn không bị đọc thành "số + đơn vị". Bỏ `b` (byte) và `db` vì toàn chữ hex.

**Ảnh hưởng.** Không đổi số liệu nào đã đo cho tới khi chạy lại benchmark: file kết quả cũ không lưu chữ AI viết, nên
không chấm lại offline được. `MetricMentions` (đo tỉ lệ AI nhắc chỉ số qua mã) dùng chung hàm đọc số nên cũng đếm thêm
số có đơn vị.

## 44. Cổng fail-closed ngay trước khi gửi AI (góp ý mentor Sprint 2, 2026-10-15)

**Quyết định.** `OpenAiChatClient.complete` quét tin nhắn người dùng (context phân tích, hoặc câu hỏi gửi AI phân loại)
bằng `domain/security/OutboundGate` TRƯỚC mọi bước khác. Còn IP, bí mật (JWT, Bearer, `sk-`, cặp `token=…`), SĐT hay
email thì không gửi: ném `SENSITIVE_DATA_BLOCKED`, bên gọi lùi về rule như mọi lỗi AI khác. Không thử lại.

**Vì sao.** Trước đây context chỉ qua Sanitizer; benchmark đo 0 rò rỉ nhưng đó là đo SAU. Cổng dùng bộ nhận diện của phép
đo Security Leakage (`SensitiveValues.inFreeText`), không dùng regex của Sanitizer — Sanitizer sót dạng nào thì cổng vẫn
có cơ hội bắt. Đặt ở `OpenAiChatClient` vì đó là nơi DUY NHẤT chuỗi rời máy, phủ cả hai lời gọi AI. Đặt trước bước
kiểm key để chạy không key (`--ai.openai.api-key=`) vẫn kiểm được cổng trên toàn bộ data mà không tốn tiền.

**Kiểm báo nhầm.** Chạy không key trên `dev` (65 lời gọi phân tích), `for_test/` và `ai20k_new_dataset`: 0 lần chặn —
mọi lời gọi ra `NOT_CONFIGURED`. Giá trị Sanitizer đã thay (`[IP]`, `[REDACTED]`, mã giả `USER_a81f2c`) không tính.

**Giới hạn.** Cổng chỉ nhận dữ liệu có HÌNH DẠNG riêng (IP, token, SĐT, email). Định danh dạng chuỗi ngẫu nhiên
(`appUserId`, `deviceId`) không có hình dạng riêng — vẫn chỉ Sanitizer xử lý, và phép đo leakage kiểm sau.

## 45. Context semi-structured để so sánh với JSON (T11 Sprint 2, theo ví dụ mentor 2026-10-15)

**Quyết định.** `domain/ai/AiContextText` dựng bản VĂN BẢN của đúng `AiContext.Payload` — không thêm, không bớt dữ kiện
— theo hình dạng mentor gửi (dòng kết luận của rule, `[EVxx][nguồn, leg, giờ] mô tả`, danh sách chỉ số). Chọn bằng
`ai.context-format=json|text` (mặc định `json`). Runner ghi dòng "Context AI" để hai lượt không bị so nhầm.

**Vì sao giữ tên trường trong ngoặc.** System prompt gọi tên các phần (`ruleVerdict`, `evidence`, `ruleBasis`,
`metrics`…). Giữ tên đó trong bản văn bản ("Evidence (evidence)", "căn cứ của rule (ruleBasis)") thì dùng được đúng một
prompt cho cả hai dạng — hai lượt đo chỉ khác cách trình bày, cùng dấu vân tay prompt. Danh sách số G05 được nhắc lại
không đổi vì dữ kiện không đổi; test khẳng định mọi văn bản đó đều có trong bản văn bản.

**Kết quả** (`dev` ×5 mỗi dạng, 2026-10-15, Final Evaluation Report mục 3b): accuracy và consistency như nhau (100%) — trên
`dev` không phân biệt được, vì AI thấy kết luận của rule. Bản văn bản ít hơn 7,5 % token vào (4 768 so với 5 153 mỗi lời
gọi phân tích); latency chênh trong mức dao động. Bản JSON cho evidence tốt hơn: AI trích đủ căn cứ của rule 96/115 lần
so với 77/115, trích hết mọi evidence 6 so với 20 lần. **Giữ JSON làm mặc định.**

## 46. Ngưỡng mất gói: cả cuộc từ 5 % (mentor, 2026-10-15)

**Quyết định.** `NETWORK_PACKET_LOSS` bật khi một leg có mất gói CẢ CUỘC từ 5 %: packetsLost ÷ (packetsLost +
packetsReceived) ở mẫu stats mang bộ đếm lớn nhất (`LegQuality.degraded`). Một mẫu > 5 %, MOS < 3,5, hasMediaPoor = 1
không còn tự bật cờ; vẫn được đếm và in ở phần "Đối chiếu" của câu căn cứ. Dòng căn cứ của cờ là mẫu mang bộ đếm.

**Vì sao.** Mentor: "packet loss < 5 %, jitter < 30 ms là ổn định"; chủ repo chốt hiểu là cả cuộc (phương án 1 trong bảng
độ nhạy). Luật cũ (mục 41 "Không đổi luật mất gói") là phỏng đoán và nhạy với mất gói thoáng qua.

**Bảng độ nhạy** (`scripts/quality-thresholds/sensitivity.py`, đọc thẳng end call log, độc lập với Java):

| Phương án | success/ (4 cuộc có end call log) | for_test/ | ai20k_new_dataset (7) |
|---|---|---|---|
| Cũ: một mẫu > 5 % / MOS < 3,5 / hasMediaPoor | 0 | 1 (271D1FAF) | 5 |
| Cả cuộc ≥ 1 % | 0 | 1 | 3 |
| Cả cuộc ≥ 2 % / 3 % | 0 | 0 | 2 (B5F19381, FE4E4BDC) |
| **Cả cuộc ≥ 5 % (chọn)** | 0 | 0 | 1 (B5F19381) |
| Cả cuộc ≥ 10 % (Teams CQD) | 0 | 0 | 0 |
| Jitter TB ≥ 20 / **30** / 50 ms | 1 (70A1F889) / **0** / 0 | 0 | 0 |

**Ảnh hưởng.** `dev` không đổi (không cuộc nào trong `dev` bị gắn cờ, trước cũng như sau); `--analyze-all`: 13/13.
Dataset mới: `06BCA48C`, `4FD495C1`, `FE4E4BDC` thành `SUCCESS` không cờ. Kịch bản demo scenario 2 đổi từ `271D1FAF`
(cả cuộc 1,83 %) sang `B5F19381` (8,48 %), chưa có nhãn. Đánh đổi: cuộc MOS rất thấp mà mất gói cả cuộc thấp
(`FE4E4BDC`: MOS 1,00) không bị gắn cờ.

---

## 47. Rà soát theo MVP: năm chỗ hở (2026-10-19)

Rà soát từng mục MVP với code (không với README) tìm ra năm chỗ mà test vẫn xanh:

1. **Call-ID phân biệt hoa thường.** ES (`keyword`) và end call log viết hoa; tên thư mục `ai20k_new_dataset` viết
   thường. Runner dùng nguyên văn `call_id` của file case, nên file `held-out` ghi chữ thường sẽ cho 0 event signaling
   và loại mọi end call log vì "lệch Call-ID" — mọi case `UNKNOWN`. Chuẩn hoá một lần ở `FileValidator` (mọi luồng phân
   tích đều qua) và ở `BenchmarkCaseLoader` (runner còn truy vấn ES trước khi phân tích). Không đổi chỗ so tên thư mục:
   vốn đã không phân biệt hoa thường.
2. **Trích dẫn evidence chưa làm sạch.** Report giữ nguyên `sourceRef` vì coi nó là "vị trí"; nhưng tên file do người
   dùng đặt. Viết test cho chỗ này mới lộ chỗ thứ hai: mẫu SĐT dùng ranh giới `\w`, mà `\w` gồm `_`, nên
   `0912345678_caller.log` không bị nhận ở cả context AI. Đổi ranh giới thành chữ / số; quét lại data mẫu cho cùng số khớp.
3. **"No sessions found" luôn N/A với lý do sai.** Bản export thô có chữ này trong `message`, nhưng `message` cố ý không
   nạp (mang User-ID gốc). Đọc thành cờ có kiểu lúc import (`SignalingMessages`, tầng domain quyết định nghĩa) — đúng quy
   ước "dữ kiện ở metadata truyền xuống dạng có kiểu, không dò chuỗi". Cờ là `Boolean`: null (bản gọn) khác false. Đếm
   theo lần gửi như INVITE. Không phải tín hiệu lỗi: 3/3 cuộc có dòng này, callee vẫn trả `TRYING` — nên không vào rule.
   Mapping `strict` từ chối trường lạ, nên index có sẵn được bổ sung mapping mỗi lần nạp thay vì bắt `--recreate-index`.
4. **S06 kiểm code chết.** `JsonSanitizer` làm sạch theo cấu trúc nhưng pipeline không gọi; pipeline làm sạch chuỗi.
   Xoá class, viết S06 trên đường thật — thử trước cho thấy đường thật xử lý được đúng ca cũ.
5. **"Missing fields" / "oversized input" (MVP 7.1).** Jackson điền mặc định cho trường thiếu (`{"verdict":"FAIL"}` thành
   `qualityFlag=false`, `analysis=null`). Kiểm theo chính `required` của schema gửi đi, nên không có danh sách thứ hai
   để lệch. Trần 2 000 token ra (dài nhất từng đo 224). Câu hỏi quá 4 000 ký tự bị Chat API từ chối trước lời gọi AI
   (dài nhất trong benchmark 139) — chặn ở controller như giới hạn số file, runner và CLI không chặn.

## Còn để mở

Năm mục từng để mở ở bản trước nay đã chốt — xem mục 11, 17, 19, 20, 21.

Từ Sprint 1, vẫn còn mở:

- **Neo WebRTC log vào giờ tuyệt đối.** End call log có các dòng `onIceConnectionChange`,
  `onIceCandidate` lặp lại cùng nội dung với WebRTC log nhưng kèm timestamp tuyệt đối — ghép
  chuỗi candidate là ra offset. Chưa làm vì Sprint 1 không cần.
- **Ngưỡng chất lượng thật.** Đang là phỏng đoán; chỉ biết chắc phải cao hơn đường nền đo
  được (844 mẫu): loss 3,7% · MOS 4,335 · RTT 266 ms · jitter 28 ms.
- **Ngữ nghĩa của `CANCEL`.** Đã rõ ở mọi cuộc có end call log để kiểm: app tự huỷ khi hết 6
  giây chờ candidate (`_waitingCandidateTimer with error`, rồi `_emitFailed … code: 421`). Các
  cuộc CANCEL không có end call log chỉ có cùng dấu vân tay trong WebRTC log. Cuộc nào WebRTC log cho thấy TURN không
  cấp phát được lần nào thì xếp `TURN_FAILURE`; còn lại vẫn là `SIGNALING_FAILURE`.

Mở thêm ở Sprint 2 (chi tiết: README, mục Known Limitations):

- **AI lệch rule nên `UNKNOWN` hay giữ kết luận AI kèm cờ** (mục 23).
- **Đề xuất: rule giữ, AI bổ sung** — trên `for_test/`, đề xuất của rule tốt hơn ở 4/7 cuộc.
- **"Semi-structured context" của T11** — mentor gửi ví dụ ngày 2026-10-15 (cùng dữ kiện với JSON, viết thành văn bản); đang làm.
- **Rule `NETWORK_DELAY_JITTER`** đã cài theo ngưỡng Teams CQD (mục 41) nhưng vẫn `UNVALIDATED`; cần cuộc gọi có nhãn
  chất lượng kém để kiểm.
