# Kịch bản demo Sprint 1

> MVP mục 5.3: *"Demo tối thiểu 5 cuộc gọi (chạy bằng CLI hoặc test, chưa cần UI)."*
> Tổng thời gian khoảng 15 phút. Năm cuộc gọi được chọn để mỗi cuộc **chứng minh một điều
> khác nhau**, không phải chọn ngẫu nhiên.

## Lưu ý về shell

Lệnh dưới đây viết cho **PowerShell**. Bốn khác biệt so với Git Bash:

| Git Bash | PowerShell |
|---|---|
| `cmd1 && cmd2` | Windows PowerShell 5.1 **không hỗ trợ `&&`** — tách hai dòng |
| `curl` | Phải gõ **`curl.exe`**, vì `curl` là alias của `Invoke-WebRequest` |
| `./mvnw` | **`.\mvnw.cmd`** |
| `export VAR=x` | `$env:VAR = "x"` |

Bản Git Bash nằm ở [phụ lục cuối trang](#phụ-lục--bản-cho-git-bash).

---

### Nếu output hiện ra toàn dấu `?`

Report in bằng tiếng Việt có dấu (MVP mục 4.5). Chương trình luôn xuất **UTF-8**;
dấu `?` là do **console Windows đang ở bảng mã cũ**, không phải do dữ liệu sai.

Cách chắc chắn nhất là chạy qua `build.ps1` — script này tự đặt UTF-8 rồi trả lại
nguyên trạng khi chạy xong:

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample"
```

Muốn sửa cho cả phiên đang mở thì gõ hai dòng này trước:

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
```

Kiểm tra nhanh xem phiên hiện tại đã đúng chưa:

```powershell
chcp
[Console]::OutputEncoding.WebName
```

Phải ra `65001` và `utf-8`. Nếu đã đúng cả hai mà **vẫn** ra `?` thì là **font**:
cửa sổ console đang dùng font raster (`Terminal`). Chuột phải lên thanh tiêu đề →
`Properties` → `Font` → chọn `Consolas` hoặc `Cascadia Mono`.

---

## Chuẩn bị (làm trước khi demo 10 phút)

```powershell
cd D:\HASON\2025.2\VIN\OJT\PROJECT\call-analysis-assistant
```
```powershell
$env:JAVA_HOME = "C:\Users\Admin\.jdks\jbr-21.0.10"
```
```powershell
docker compose up -d elasticsearch
```
```powershell
curl.exe -s http://localhost:9200/_cluster/health
```

Đợi tới khi thấy `"status":"green"` hoặc `"yellow"`.

```powershell
.\mvnw.cmd -q clean compile
```

Nạp sẵn dependency để không phải chờ lúc demo.

Mở sẵn ba cửa sổ:
1. Terminal ở `call-analysis-assistant`
2. Trình soạn thảo mở `ai20k_sample\fail\2D9057AA-...\callee_endcall.log`
3. `docs\architecture-v1.md`

---

## Phần 0 — Nạp lại data từ đầu (2 phút)

**Chứng minh:** mục 8.1 *"người khác dựng và chạy lại được"*.

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample --recreate-index"
```

Xoá index rồi nạp lại từ đầu. Kết quả luôn là **1 059 document / 20 cuộc gọi**, vì `_id` được
suy ra tất định từ `(callId, ordinal)` — chạy bao nhiêu lần cũng không sinh bản trùng.

Chạy lại **lần thứ hai**, không có `--recreate-index`:

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample"
```

Vẫn 1 059 — idempotent được kiểm bằng kết quả chạy, không chỉ bằng thiết kế.

---

## Phần 1 — Cuộc gọi bình thường: `DE7DD314` (2 phút)

**Chứng minh:** chỉ số tính đúng, evidence trace được về dòng log gốc.

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/success/DE7DD314-F432-45CB-BCB4-AE9103CC0919"
```

Ba chỗ cần xem trong output:

| Chỗ | Giải thích |
|---|---|
| `Thời gian thiết lập 8581 ms` | Khớp chính xác với giá trị tính tay từ `signaling.json`. Timestamp có 9 chữ số nano — phải trừ ở độ chính xác đầy đủ rồi mới cắt về mili, cắt sớm thì lệch 1 ms. |
| `[EV06][callee_endcall.log:...]` | Mỗi evidence có tên file và số dòng; mở đúng dòng đó là thấy bản ghi gốc. |
| `RTT (callee) 63 ms` | Log có hai trường RTT. `transport.rttMs` bằng 8385 vì đó là giá trị tích luỹ; giá trị đúng là `currentRttMs`. |

---

## Phần 2 — Bị server từ chối: `1B009D42` (1,5 phút)

**Chứng minh:** quy tắc `N/A` kèm lý do, không mặc định về 0.

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/fail/1B009D42-49CD-479E-B26C-3A2994AEB720"
```

Cuộc gọi chỉ có 10 event `INIT_CALL` trong 16 mili giây rồi im lặng — server chưa từng gửi
`INVITE` nên máy callee không đổ chuông. End call log của caller cho biết lý do: server trả mã
428 `privacy_restricted`, tức người nhận đang chặn cuộc gọi. Report ghi đúng lý do đó, không đổ
cho mạng.

Cột chỉ số không có giá trị nào bằng 0. Mỗi chỉ số thiếu đều là `N/A` kèm **lý do riêng** —
*cuộc gọi không đạt tới OK_ACK_OK* khác với *không có end call log của caller*. Đây là yêu cầu
mục 4.3, được ép ở tầng JSON Schema bằng `oneOf`: thiếu giá trị thì bắt buộc phải có `naReason`.

---

## Phần 3 — Ca quan trọng nhất: `2D9057AA` (4 phút)

**Chứng minh:** vì sao không được kết luận chỉ bằng signaling.

### Bước 1 — signaling trước

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--metrics=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"
```

Nhìn riêng signaling thì cuộc gọi này hoàn hảo: thiết lập 6 774 ms, đổ chuông 5 565 ms, kết nối
33 892 ms, callee gửi BYE, `PAIR_PING` hai bên đều đặn sau khi bắt tay. Rule nào chỉ xét
signaling cũng sẽ kết luận SUCCESS.

### Bước 2 — kết luận thật

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"
```

Ba evidence quyết định:

```text
[EV07][callee_endcall.log:185] Call summary callee: không nhận được gói audio nào (packetsReceived=0, bytesRecv=0), mediaFail=1 — MOS/loss/RTT không đo được
[EV08][callee_endcall.log:187] _emitBye ... codeReason: 419 failReason: Vui lòng kiểm tra kết nối mạng
[EV12][callee_webrtc.log:902]  ICE chuyển checking => failed
```

Ground truth là FAIL. Signaling sống nhưng media chết — **không một byte audio nào đi qua**.
ICE kẹt ở `checking` đúng 15,6 giây rồi `failed`, và hệ thống tự ngắt với `originator=2`, mã 419.

Vì vậy trong `RuleVerdictEngine`, **thứ tự kiểm tra là có chủ đích**: phải xét media trước khi
được phép kết luận SUCCESS. Đây là ca duy nhất trong tập dev có kiểu hỏng này, và nó một mình
chứng minh vì sao đề bài tách `verdict` khỏi `issueCategory`.

**Vì sao tin `bytesReceived` hơn MOS:** MOS bằng 0 mơ hồ — không phân biệt được *đo được và bằng
0* với *không đo được*. `bytesReceived = 0` thì dứt khoát.

---

## Phần 4 — File đặt sai tên: `EE129C8F` (2,5 phút)

**Chứng minh:** nhận diện theo nội dung, không theo tên file.

### Bước 1 — tên file

```powershell
Get-ChildItem ..\ai20k_sample\success\EE129C8F-EAD0-4302-AB68-920D32F8B8B7\ -Name
```

```text
callee_endcall.log   caller_endcall.log   callee_webrtc.log   calleer_webrtc.log   signaling.json
```

`calleer_webrtc.log` thừa một chữ `e`, và thư mục **không có** `caller_webrtc.log`.

### Bước 2 — hệ thống tự gán lại

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--timeline=../ai20k_sample/success/EE129C8F-EAD0-4302-AB68-920D32F8B8B7"
```

```text
calleer_webrtc.log       leg=CALLER  platform=android  độ tin cậy=MATCHED_BY_SDP_ROLE  (1017 sự kiện)
[LEG_UNCERTAIN] File calleer_webrtc.log được gán lại từ CALLEE sang CALLER (đối chiếu theo nội dung, không theo tên file)
```

Suy theo tên file thì đây là CALLEE, vì `calleer` bắt đầu bằng `callee`. Nhưng dòng 80 của file
ghi `DoSetLocalDescription: offer` — thiết bị này tạo SDP offer, mà trong hệ thống này caller là
bên tạo offer. Vậy đây là log của **caller**, bị gõ sai tên. Gán sai leg thì mọi chỉ số per-leg
sau đó đều sai.

---

## Phần 5 — Thiếu dữ liệu: `9B556E56` (1,5 phút)

**Chứng minh:** khai báo giới hạn dữ liệu thay vì đoán bừa.

```powershell
Get-ChildItem ..\ai20k_sample\for_test\9B556E56-24D3-43BA-853D-7972AD009865\ -Name
```
```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/for_test/9B556E56-24D3-43BA-853D-7972AD009865"
```

Cuộc này chỉ có `signaling.json` và `caller_webrtc.log`, không có end call log nào. Mục
*Giới hạn dữ liệu* nêu rõ thiếu gì và điều đó ảnh hưởng ra sao — mục 3.3 yêu cầu thiếu dữ liệu
thì phải nêu, không được đoán.

---

## Phần 6 — Không crash với input xấu (1,5 phút)

**Chứng minh:** acceptance criteria *"Invalid input không làm crash pipeline"*.

> **Quan trọng:** thư mục đích phải **giữ nguyên tên Call-ID**, vì hệ thống lấy Call-ID từ
> tên thư mục để tra signaling trong Elasticsearch. Đổi tên thành `demo-call` là mất 128
> event signaling và demo trông như bị lỗi.

```powershell
$demo = Join-Path $env:TEMP "demo-call\EE129C8F-EAD0-4302-AB68-920D32F8B8B7"
```
```powershell
New-Item -ItemType Directory -Path $demo -Force | Out-Null
```
```powershell
Copy-Item ..\ai20k_sample\success\EE129C8F-EAD0-4302-AB68-920D32F8B8B7\* $demo -Force
```
```powershell
[System.IO.File]::WriteAllBytes("$demo\file_rac.log", [byte[]](0,1,2,255,254))
```
```powershell
New-Item -ItemType File "$demo\file_rong.log" -Force | Out-Null
```
```powershell
Set-Content "$demo\dong_cuc_dai.log" ("X" * 300000)
```
```powershell
Copy-Item "$demo\callee_webrtc.log" "$demo\ban_sao.log" -Force
```
```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--timeline=$demo"
```

Kết quả đúng: `Tổng sự kiện: 2715  (main track 405, relative 2310)`.

Bốn loại input xấu: nhị phân, file rỗng, một dòng 300 000 ký tự, và file trùng nội dung đặt tên
khác. Không có stack trace nào. Ba file rác bị bỏ qua và được nêu tên; file trùng bị loại, và
hệ thống **giữ lại `callee_webrtc.log` chứ không giữ `ban_sao.log`** — giữ tên vô nghĩa thì
evidence sẽ trích dẫn khó đọc.

---

## Phần 7 — Chạy toàn bộ và đối chiếu ground truth (2 phút)

```powershell
.\mvnw.cmd -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample"
```

Cả 6 cuộc trong `fail/` ra FAIL, cả 7 cuộc trong `success/` ra SUCCESS — **13/13 trên toàn bộ
data có nhãn, chưa cần đến AI**. Accuracy do chương trình tự tính từ nhãn thư mục, không đếm tay.
20/20 report hợp lệ theo schema.

Bảy cuộc trong `for_test/` không có nhãn nên không tính vào mẫu số. Tập này dùng để chạy và
báo cáo kết quả, không dùng để chỉnh ngưỡng.

```powershell
.\mvnw.cmd test
```

Toàn bộ unit test chạy trong vài giây, **không cần Elasticsearch**, vì tầng `domain` không chạm
I/O. Luật đó không phải để code cho đẹp, mà là điều kiện để đạt Consistency ≥ 95% ở Sprint 2.

---

## Các vấn đề còn mở

### 1. AI Provider Proposal — chờ duyệt

`docs/ai-provider-proposal.md` so sánh hai phương án. Điểm cần quyết: **log production đã
sanitize có được đi qua API bên thứ ba không**. Nếu được thì chọn cloud API vì ép được JSON
Schema ở phía server; nếu không thì chạy local model trên GPU của máy (RTX 4050, 6 GB VRAM).
Latency và chi phí của cả hai chưa đo, sẽ đo ở đầu Sprint 2.

### 2. Data có nhãn chưa phủ hết taxonomy

Data có nhãn phủ **3/6 issue category**. `NETWORK_PACKET_LOSS`, `NETWORK_DELAY_JITTER`, `UNKNOWN`
không có ca mẫu nào, nên rule cho ba category này viết theo phỏng đoán và được đánh dấu
`UNVALIDATED` trong `taxonomy.yaml`. `TURN_FAILURE` thì có: 3 cuộc CANCEL trong `fail/` thật ra
là TURN không cấp phát được relay nào, app hết 6 giây chờ candidate nên tự huỷ.

Đường nền đo trên 844 mẫu của các cuộc gọi khoẻ: loss cao nhất 3,7%, MOS thấp nhất 4,335, RTT
cao nhất 266 ms. Ngưỡng phải cao hơn mức đó, nhưng chưa có dữ liệu để biết cao hơn bao nhiêu.
Thiếu cuộc gọi packet loss cao hoặc độ trễ cao có nhãn thì scenario #2 của Final Demo cũng chưa
có gì để chạy.

### 3. Ba lỗi đã tự phát hiện và sửa

1. **`Received TURN allocate error response code=401` không phải lỗi TURN.** Dòng này có ở cả
   cuộc gọi thành công — đó là bước bắt tay xác thực chuẩn RFC 8656. Toàn tập có 73 dòng error và
   **đúng 73** dòng allocate thành công — cổng nào nhận 401 cũng cấp phát được ngay sau đó. Đếm
   dòng lỗi sẽ báo nhầm 11/20 cuộc gọi, gồm cả 7/7 cuộc thành công. TURN hỏng thật trông khác hẳn:
   cả file không có một lần allocate thành công nào (3 ca CANCEL trong `fail/`).
2. **Đếm số dòng log thành số lần gửi lại INVITE** — ra 9 thay vì 3. Một lần gửi sinh nhiều dòng
   log; phải đo phân bố khoảng cách mới tìm được ngưỡng gom cụm.
3. **Dedupe ở mức dòng xoá mất 92 event thật**, vì libwebrtc ghi lặp cùng một dòng trong cùng
   mili giây.

Rút ra: **trước khi coi một chuỗi là tín hiệu lỗi, grep nó trên thư mục `success/`** — xuất hiện
ở đó thì là nhiễu.

---

## Bảng tra nhanh khi demo

| Cuộc gọi | Chứng minh điều gì |
|---|---|
| `DE7DD314` | Chỉ số đúng, evidence trace được |
| `1B009D42` | `N/A` kèm lý do, không về 0 |
| **`2D9057AA`** | **Signaling hoàn hảo vẫn có thể FAIL** |
| `EE129C8F` | Nhận diện theo nội dung, sửa được file sai tên |
| `9B556E56` | Khai báo giới hạn dữ liệu |

| Con số | Giá trị |
|---|---|
| Verdict Accuracy | 13/13 trên data có nhãn |
| Parse | 29 238 dòng → 28 522 event, 1 cảnh báo |
| Chỉ số khớp tính tay | 9 cuộc gọi |
| Report hợp lệ theo schema | 20/20 |
| Unit test | chạy trong vài giây, không cần ES |

## Dọn dẹp sau demo

```powershell
Get-ChildItem "$env:TEMP\demo-call" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
```
```powershell
docker compose down
```

---

## Phụ lục — bản cho Git Bash

Nếu dùng Git Bash thì thay như sau, logic giữ nguyên:

```bash
cd call-analysis-assistant && export JAVA_HOME="/c/Users/Admin/.jdks/jbr-21.0.10"
docker compose up -d elasticsearch && sleep 40 && curl -s http://localhost:9200/_cluster/health

# Mọi lệnh phân tích đều theo mẫu này
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--analyze=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--analyze-all=../ai20k_sample"
./mvnw test

# Phần 6 — input xấu. Dấu / cuối bắt buộc: giữ nguyên tên thư mục Call-ID (xem Phần 6)
ID=EE129C8F-EAD0-4302-AB68-920D32F8B8B7
mkdir -p /tmp/demo-call && cp -r ../ai20k_sample/success/$ID /tmp/demo-call/
printf '\x00\x01\x02 rac\xff\xfe' > /tmp/demo-call/$ID/file_rac.log
: > /tmp/demo-call/$ID/file_rong.log
python -c "print('X'*300000)" > /tmp/demo-call/$ID/dong_cuc_dai.log
cp /tmp/demo-call/$ID/callee_webrtc.log /tmp/demo-call/$ID/ban_sao.log
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--timeline=/tmp/demo-call/$ID"
```
