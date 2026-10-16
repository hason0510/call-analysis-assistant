# Runbook — AI Call Analysis Assistant

Tài liệu này dùng để dựng, chạy, kiểm thử và xử lý lỗi thường gặp của dự án trên một máy mới.

| | |
|---|---|
| Người phụ trách | Chủ repo (`hason0510`) |
| Cập nhật lần cuối | 2026-10-12 (sau Sprint 3 T8) |
| Đã chạy thử trên | Windows 11, PowerShell 7, JDK 21, Docker Desktop |
| Chưa chạy thử | macOS, Linux — lệnh tương đương ghi ở cuối mỗi mục |

Lệnh trong tài liệu viết cho **PowerShell trên Windows**, chạy ở thư mục gốc của repo. Trên macOS / Linux, thay
`.\build.ps1` bằng `./mvnw` và đặt `JAVA_HOME` trỏ tới JDK 21.

---

## 1. Cần chuẩn bị

- [ ] **JDK 21.** Không dùng JDK 24 trở lên: Spring Boot 3.5 không hỗ trợ. Máy có nhiều JDK thì không cần đổi
      `JAVA_HOME` của máy, xem bước 2.3.
- [ ] **Docker Desktop**, có Docker Compose v2. Elasticsearch cần khoảng 4 GB RAM trống (heap 2 GB).
- [ ] **Git.**
- [ ] **Data mẫu** `ai20k_sample/`, đặt cạnh repo (không đặt bên trong):

      ```
      <thư mục bất kỳ>/
      ├── call-analysis-assistant/   ← repo
      └── ai20k_sample/              ← fail/, success/, for_test/
      ```

      Mọi lệnh dùng đường dẫn `../ai20k_sample`. Data mẫu không có trong repo vì chứa IP công cộng thật.
- [ ] **Key OpenAI (tuỳ chọn).** Không có key, ứng dụng vẫn chạy đủ luồng nhưng report dựng từ rule.
- [ ] **Python 3 (tuỳ chọn).** Chỉ cần nếu chạy benchmark `dev` đầy đủ (bước 4.2).

---

## 2. Dựng lần đầu

### 2.1. Lấy code

```powershell
git clone https://github.com/hason0510/call-analysis-assistant.git
cd call-analysis-assistant
```

### 2.2. Tạo file `.env`

```powershell
Copy-Item .env.example .env
```

Mở `.env`, điền hai giá trị:

| Biến | Dùng để | Để trống thì |
|---|---|---|
| `OPENAI_API_KEY` | Gọi `gpt-4o-mini` | Report dựng từ rule, banner ghi "chưa cấu hình khoá AI" |
| `CALL_ANALYSIS_PSEUDONYM_KEY` | Khoá HMAC để tạo mã giả cho dữ liệu nhạy cảm | Mỗi lần chạy dùng một khoá ngẫu nhiên; mã giả đổi giữa các lần chạy |

Sinh khoá HMAC: `openssl rand -hex 32` (hoặc chuỗi ngẫu nhiên dài 64 ký tự hex bất kỳ).

`.env` không được commit (đã có trong `.gitignore`).

### 2.3. Build

```powershell
.\build.ps1 -q clean compile
```

`build.ps1` tự tìm JDK 21 theo thứ tự: biến `JAVA_HOME_21`, `%USERPROFILE%\.jdks\jbr-21.0.10`,
`C:\Program Files\Java\jdk-21`, `C:\Program Files\Eclipse Adoptium\jdk-21`. Nó chỉ đặt `JAVA_HOME` trong phiên chạy,
không đổi cấu hình máy. JDK đặt ở chỗ khác:

```powershell
$env:JAVA_HOME_21 = "C:\đường\dẫn\tới\jdk-21"
```

Kết quả đúng: lệnh chạy xong, không in `ERROR`.

### 2.4. Bật Elasticsearch

```powershell
docker compose up -d elasticsearch
curl.exe -s http://localhost:9200/_cluster/health
```

Lần đầu Docker tải image khoảng 600 MB. Đợi tới khi lệnh thứ hai trả về `"status":"green"` hoặc `"yellow"`.
Elasticsearch chỉ lắng nghe trên `localhost`, không bật xác thực, nên chỉ dùng trên máy cá nhân.

### 2.5. Nạp signaling của data mẫu

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample"
```

Kết quả đúng:

```
---- Kết quả import ----
File đọc được: 20
Document nạp : 1059
Vấn đề       : không có
```

Chạy lại không sinh bản trùng. Muốn xoá index rồi nạp lại từ đầu, thêm `--recreate-index` vào sau đường dẫn.

### 2.6. Kiểm nhanh

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample --ai.openai.api-key="
```

Kết quả đúng (ba dòng cuối):

```
Verdict Accuracy         : 13/13 = 100,0%  (chỉ tính cuộc gọi có nhãn)
Report hợp lệ theo schema: 20/20
Cuộc gọi không có nhãn   : 7
```

Lệnh này không gọi AI.

---

## 3. Chạy ứng dụng

### 3.1. Web UI và Chat API

```powershell
.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web"
```

Mở http://localhost:8081/. Nhập câu hỏi, kéo thả file log của một cuộc gọi (ví dụ hai file trong
`../ai20k_sample/fail/2D9057AA-…/`), bấm gửi. Kịch bản thử đủ 6 scenario: `docs/demo-sprint-3.md`.

- Server chỉ nghe ở `127.0.0.1`: Chat API không có đăng nhập mà mỗi request tốn tiền gọi OpenAI. Cần mở từ máy khác
  trong mạng LAN thì thêm `"-Dspring-boot.run.arguments=--server.address=0.0.0.0"` — khi đó ai trong mạng cũng gọi được.
- Mỗi lần phân tích nhận tối đa 10 file (`call-analysis.files.max-files-per-request`). Một cuộc gọi thường có 4 file.
- Cổng 8081 bận: thêm `"-Dspring-boot.run.arguments=--server.port=8089"`.
- Log của server in dạng JSON, mỗi dòng một sự kiện. Muốn đọc log dạng chữ: `"-Dspring-boot.run.profiles=web,plain-log"`.
- Dừng server: `Ctrl + C`.

Gọi API trực tiếp:

```powershell
curl.exe -X POST localhost:8081/api/analyze -F "message=Phan tich cuoc goi nay giup minh" `
    -F "files=@../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086/callee_endcall.log"
```

Câu hỏi có dấu thì gửi từ file UTF-8 (`-F "message=<cau-hoi.txt"`): curl trên Windows làm hỏng chữ có dấu khi gõ
thẳng vào lệnh.

### 3.2. Dòng lệnh

Phân tích một cuộc gọi và in report:

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"
```

Danh sách đầy đủ các lệnh (`--parse-*`, `--timeline`, `--metrics*`, `--ai-context*`, `--ai-analyze*`…): README, mục
"Toàn bộ lệnh".

---

## 4. Kiểm thử

### 4.1. Test tự động

```powershell
.\build.ps1 test
```

Kết quả đúng: 534 test, 0 lỗi. 3 test tích hợp chạy Elasticsearch thật qua Testcontainers; không có Docker thì 3 test
này tự bỏ qua. Unit test không cần Elasticsearch hay key AI.

### 4.2. Benchmark `dev`

Lần đầu, tạo hai thư mục biến thể mà bộ case cần (ca "tên file sai loại" và ca dữ liệu nhạy cảm giả):

```powershell
python scripts/benchmark-variants/make_variants.py ../ai20k_sample
```

Chạy không tốn tiền (không gọi AI, report dựng từ rule):

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample --repeat=1 --ai.openai.api-key="
```

Chạy có AI: bỏ `--ai.openai.api-key=`. Chi phí đo được khoảng 0,05 USD cho `--repeat=1` (66 câu hỏi), 0,20 USD cho
`--repeat=5`. Thời gian khoảng 3 phút cho `--repeat=1`.

Kết quả ghi vào `target/evaluation/evaluation-<thời điểm>.md` (bảng metric) và `.json` (từng lần chạy). Đầu file `.md`
có dòng `Prompt AI`: hai lượt khác dòng này thì AI nhận chỉ dẫn khác nhau, số liệu không so trực tiếp được.

`mvn clean` xoá thư mục `target/`. Kết quả muốn giữ thì chép sang `benchmark/results/<tên lượt>/`.

### 4.3. Chấm lại không gọi AI

Sau khi sửa đáp án trong file case:

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--rescore=target\evaluation\evaluation-<thời điểm>.json"
```

Không cần Elasticsearch, không tốn tiền.

### 4.4. Bộ case khác (ví dụ `held-out`)

Các bước xếp thư mục log, viết file case, nạp signaling và chạy: README, mục "Chạy bộ case `held-out`".

---

## 5. Xem request và dashboard trên Kibana

Server profile `web` ghi mỗi request một bản ghi vào index `analysis-requests` (chỉ mã, số, cờ — không chữ tự do).
Dựng view một lần:

```powershell
docker compose up -d                          # thêm Kibana
python scripts/kibana/setup_views.py          # data view + 4 saved search; chạy lại được
python scripts/kibana/setup_dashboard.py      # dashboard kết quả phân tích (T11); cần chạy setup_views.py trước
```

Mở http://localhost:5601/app/discover → **Open** → chọn một saved search: *Requests — tất cả*, *fallback*, *lỗi*,
*chậm (> 10 s)*. Index chỉ có dữ liệu sau khi đã gửi ít nhất một request tới server profile `web`.
Saved search tự đặt khoảng thời gian 7 ngày. Mở Discover trực tiếp thì Kibana chỉ xem 15 phút gần nhất, nên trang
trống "No results match your search criteria" dù index có dữ liệu: đổi khoảng thời gian ở góc phải.

Dashboard **Kết quả phân tích cuộc gọi**: http://localhost:5601/app/dashboards#/view/analysis-results-dashboard — số
report, tỉ lệ UNKNOWN, tỉ lệ fallback, độ trễ P50 / P95, phân bố kết luận / loại lỗi / độ tin cậy, lý do fallback, độ
trễ theo thời gian (MVP 7.1 T11). Tỉ lệ tính trên request ra report; câu ngoài phạm vi không tính. Dashboard tự đặt
khoảng thời gian 7 ngày.

---

## 6. Dừng và dọn

```powershell
docker compose down        # dừng Elasticsearch, giữ dữ liệu
docker compose down -v     # dừng và xoá dữ liệu; lần sau phải nạp lại (bước 2.5)
```

Kibana dừng cùng Elasticsearch khi `docker compose down`.

---

## 7. Xử lý sự cố

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Mọi report ra `UNKNOWN`, mục "Giới hạn dữ liệu" ghi *"Không truy vấn được signaling"* | Elasticsearch chưa bật, chưa sẵn sàng, hoặc chưa nạp | Kiểm tra `curl.exe -s http://localhost:9200/_cluster/health`; chạy lại bước 2.4 và 2.5 |
| `--evaluate` in `Signaling: có cho 0/… Call-ID` ở đầu báo cáo | Chưa nạp signaling của bộ log đang dùng | Chạy bước 2.5 với đúng thư mục log đó |
| `Unknown lifecycle phase ".run.profiles=web"` | PowerShell cắt tham số `-D…` tại dấu chấm | Đặt cả `"-D…"` trong ngoặc kép như các lệnh mẫu |
| Build báo lỗi phiên bản Java, hoặc in nhiều `WARNING` về `sun.misc.Unsafe` | Maven chạy trên JDK 24 trở lên | Dùng `.\build.ps1` thay cho `mvn`; JDK 21 đặt ở chỗ lạ thì đặt `$env:JAVA_HOME_21` |
| Web không mở được | Cổng 8081 bận | Thêm `"-Dspring-boot.run.arguments=--server.port=8089"` |
| Mở được trên máy chạy server, máy khác thì không | Server chỉ nghe ở `127.0.0.1` | Thêm `"-Dspring-boot.run.arguments=--server.address=0.0.0.0"` (mục 3.1) |
| Banner *"Quá nhiều file đính kèm"* | Quá 10 file một lần | Bỏ bớt file; giới hạn đặt ở `call-analysis.files.max-files-per-request` |
| Cổng 9200 bận khi bật Elasticsearch | Có Elasticsearch khác đang chạy | Dừng bản kia, hoặc đổi cổng trong `docker-compose.yml` và `spring.elasticsearch.uris` |
| Banner vàng *"Report dựng từ rule vì AI không dùng được"* | AI không dùng được; banner ghi lý do | Chưa có key: điền `.env`. *"Khoá AI không hợp lệ"*: kiểm tra `OPENAI_API_KEY`. *"Giới hạn tốc độ gọi"*: thử lại sau ít phút. *"Hết hạn mức"*: nạp thêm tiền cho tài khoản OpenAI. *"Vượt giới hạn độ dài của model"*: hỏi câu ngắn hơn. Quá giờ / mất mạng: hệ thống đã tự thử lại một lần |
| Report hoặc câu từ chối có dòng *"Câu hỏi có ký tự bị hỏng mã hoá"* | Câu hỏi có dấu bị hỏng trước khi tới server (curl, dòng lệnh Windows) | Gõ trên Web UI, gõ không dấu, hoặc gửi từ file UTF-8 |
| Log server khó đọc (toàn JSON) | Profile `web` in log JSON | Thêm profile `plain-log` |
| Trình duyệt báo *"Quá thời gian chờ"* sau 150 giây | Server treo hoặc Elasticsearch không phản hồi | Kiểm tra server còn chạy, Elasticsearch còn sống; thử lại |
| File log bị loại, "Giới hạn dữ liệu" ghi *"vượt giới hạn"* | File lớn hơn 20 MB | Giới hạn đặt ở `call-analysis.files.max-size-per-file` trong `application.yml` |
| `--evaluate` báo case thiếu file `_variants/…` | Chưa tạo thư mục biến thể | Chạy `make_variants.py` (bước 4.2) |

Lỗi không có trong bảng: xem log của server (mỗi dòng có `request_id`, lọc theo mã này để thấy đủ các bước của một
request), rồi mục Known Limitations trong README.

---

## 8. Tra cứu nhanh

| | |
|---|---|
| Web UI | http://localhost:8081/ |
| Chat API | `POST /api/analyze` (multipart: `message`, `files`) |
| Elasticsearch | http://localhost:9200, index `signaling-events` |
| Kibana | http://localhost:5601 — view request: `python scripts/kibana/setup_views.py`; dashboard: `python scripts/kibana/setup_dashboard.py` |
| Index Elasticsearch | `signaling-events` (signaling nạp vào), `analysis-requests` (bản ghi request, profile `web`) |
| Kết quả benchmark | `target/evaluation/` (tạm), `benchmark/results/` (lưu lại) |
| Cấu hình | `src/main/resources/application.yml`, `application-web.yml` |
| Chi phí AI | khoảng 0,0007 USD và 2-4 giây cho mỗi câu hỏi |
| Thời gian chờ | AI 30 s mỗi lần thử, tối đa 45 s cả lời gọi (kể cả thử lại); Elasticsearch 5 s kết nối, 30 s đọc; trình duyệt 150 s |

Lệnh tương đương trên macOS / Linux (chưa chạy thử):

```bash
export JAVA_HOME=/đường/dẫn/tới/jdk-21
cp .env.example .env
./mvnw -q clean compile
docker compose up -d elasticsearch
curl -s http://localhost:9200/_cluster/health
./mvnw -q spring-boot:run -Dspring-boot.run.arguments="--import-signaling=../ai20k_sample"
./mvnw spring-boot:run -Dspring-boot.run.profiles=web
./mvnw test
```
