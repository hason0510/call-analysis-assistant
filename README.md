# AI Call Analysis Assistant

Trợ lý phân tích cuộc gọi: nhận câu hỏi tiếng Việt kèm log cuộc gọi, trả về report chuẩn hoá
gồm kết luận (`SUCCESS` / `FAIL` / `UNKNOWN`), evidence trích dẫn được về dòng log gốc,
chỉ số cuộc gọi, đề xuất và giới hạn dữ liệu.

Dự án OJT AI 20K — 6 tuần, 3 sprint. **Đang ở cuối Sprint 3** (Hardening, Consistency & Handover): độ tin cậy theo
chính sách, thử lại có kiểm soát, log JSON đã làm sạch, Kibana view + dashboard, phân tích hàng loạt, 3 tài liệu bàn
giao, Peer Evaluation — tiến độ ở mục [Trạng thái — Sprint 3](#trạng-thái--sprint-3-77-task-core-44-task-nếu-kịp). Sprint 2: Web UI, Chat API, AI
(`gpt-4o-mini`) có Guardrails và fallback về rule, Sanitizer đầu vào và đầu ra, Evaluation Runner. Sprint 1 là rule
baseline, vẫn là mốc đối chiếu và đường lui khi AI lỗi.

---

## Dành cho người chấm

Ba cách thử sản phẩm, độc lập với nhau. Lệnh viết cho **PowerShell trên Windows** và đã chạy thử ngày 2026-10-07
(macOS / Linux: dùng `./mvnw` thay cho `.\build.ps1`, đặt `JAVA_HOME` trỏ tới JDK 21).

**Chuẩn bị (một lần)**

- Cài JDK 21 và Docker Desktop. `build.ps1` tự tìm JDK 21; JDK đặt ở chỗ khác thì đặt `$env:JAVA_HOME_21` trước khi chạy.
- Đặt thư mục data mẫu **cạnh repo, tên `ai20k_sample`** — mọi lệnh dùng đường dẫn `../ai20k_sample`:

  ```
  <thư mục bất kỳ>/
  ├── call-analysis-assistant/   ← repo
  └── ai20k_sample/              ← data mẫu: fail/, success/, for_test/
  ```

- Key OpenAI là tuỳ chọn: chép `.env.example` thành `.env` và điền `OPENAI_API_KEY`. Không có key thì mọi report lùi
  về rule (degraded) nhưng vẫn đi đủ luồng.
- Bật Elasticsearch và nạp signaling của data mẫu (phải ra **1 059 document**):

  ```powershell
  docker compose up -d elasticsearch
  .\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=../ai20k_sample"
  ```

**1. Thử trên web** — chạy lệnh dưới, mở http://localhost:8081/, rồi làm theo 6 scenario của Final Demo trong
[`docs/demo-sprint-3.md`](docs/demo-sprint-3.md) (câu hỏi gõ gì, kéo thả file nào, phải thấy gì; kết quả chạy thật ghi
dưới từng scenario — 2026-10-12, riêng scenario 2 đổi sang `B5F19381` ngày 2026-10-15):

```powershell
.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web"
```

**2. Chạy bộ case `held-out`** — mục [Chạy bộ case `held-out`](#chạy-bộ-case-held-out) bên dưới.

**3. Chạy test** — `.\build.ps1 test` → **600 test, 0 lỗi**. 6 test tích hợp cần Docker; không có Docker thì tự bỏ qua.

**Lỗi thường gặp**

| Hiện tượng | Nguyên nhân | Cách xử lý |
|---|---|---|
| Mọi report ra `UNKNOWN`, giới hạn dữ liệu ghi *"Không truy vấn được signaling"* | Elasticsearch chưa bật hoặc chưa nạp | `docker compose up -d elasticsearch`, chạy lại lệnh nạp |
| `Unknown lifecycle phase ".run.profiles=web"` | PowerShell cắt tham số `-D…` tại dấu chấm | Đặt cả `"-D…"` trong ngoặc kép như lệnh mẫu |
| Web không mở được, cổng 8081 đang bị chiếm | Chương trình khác dùng cổng 8081 | Thêm `"-Dspring-boot.run.arguments=--server.port=8089"`, mở http://localhost:8089/ |
| Mở web từ máy khác không được | Server chỉ nghe ở `127.0.0.1` (Chat API không có đăng nhập, mỗi request tốn tiền AI) | Thêm `"-Dspring-boot.run.arguments=--server.address=0.0.0.0"`; khi đó ai trong mạng cũng gọi được |
| Banner vàng *"Report dựng từ rule vì AI không dùng được"* | Chưa có key, AI lỗi, hoặc OpenAI trả 429 — banner ghi rõ lý do, ví dụ *"giới hạn tốc độ gọi"* hay *"hết hạn mức"* | Đúng thiết kế (fallback). Giới hạn tốc độ: thử lại sau ít phút; hết hạn mức: nạp thêm; chưa có key: điền key |
| Report hoặc câu từ chối có dòng *"Câu hỏi có ký tự bị hỏng mã hoá"* | curl / dòng lệnh Windows làm hỏng chữ có dấu trước khi gửi | Gõ trên Web UI, gõ không dấu, hoặc gửi từ file UTF-8: `-F "message=<cau-hoi.txt"` |

---

## Yêu cầu môi trường

| | Phiên bản | Ghi chú |
|---|---|---|
| JDK | **21** | |
| Maven | không cần cài | Dùng `./mvnw` kèm sẵn trong repo (Windows: `.\build.ps1`) |
| Docker | bất kỳ bản hỗ trợ Compose v2 | Cho Elasticsearch + Kibana local |
| RAM trống | ~4 GB | Elasticsearch cấu hình 2 GB heap |

---

## Chạy thử nhanh

```bash
# 1. Khởi động Elasticsearch (lần đầu tải ~600 MB)
docker compose up -d elasticsearch

# 2. Đợi tới khi status là green hoặc yellow
curl http://localhost:9200/_cluster/health

# 3. Nạp data mẫu vào Elasticsearch
./mvnw spring-boot:run -Dspring-boot.run.arguments="--import-signaling=../ai20k_sample"

# 4. Phân tích một cuộc gọi và in report
./mvnw spring-boot:run -Dspring-boot.run.arguments="--analyze=../ai20k_sample/fail/2D9057AA-C496-48B2-946A-98FA2896D086"

# 5. Chạy toàn bộ 20 cuộc gọi và đối chiếu ground truth
./mvnw spring-boot:run -Dspring-boot.run.arguments="--analyze-all=../ai20k_sample"

# 6. Web UI + Chat API tại http://localhost:8081/
./mvnw spring-boot:run "-Dspring-boot.run.profiles=web"

# 7. Evaluation Runner trên benchmark dev (lần đầu: tạo file biến thể, xem dưới)
python scripts/benchmark-variants/make_variants.py ../ai20k_sample
./mvnw spring-boot:run -Dspring-boot.run.arguments="--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample"
```

**File biến thể F02.** MVP 6.3 đòi tập `dev` có ca "tên file sai loại", mà data mẫu không có sẵn. Script trên chép
`caller_endcall.log` của `DE7DD314` thành `_variants/F02-sai-loai/caller_webrtc.log` — nội dung giống hệt, chỉ đặt sai
tên — để kiểm hệ thống nhận loại file theo nội dung (case-016). Script cũng tạo `_variants/S06-nhay-cam/`: log của
`2D9057AA` có chèn số điện thoại, email, Bearer JWT, API key **giả** vào lời nhắn lỗi, cho case-019 (scenario 6 — dữ liệu
nhạy cảm; data mẫu đã xử lý PII nên không có sẵn). File nằm trong thư mục data mẫu, ngoài repo, vì data mẫu chứa IP thật.
Chỉ cần chạy một lần; bộ `held-out` của Mentor không cần bước này.

**Key AI.** Chép `.env.example` thành `.env` và điền `OPENAI_API_KEY` (cùng `CALL_ANALYSIS_PSEUDONYM_KEY`, khoá
HMAC cho mã giả). Không có key thì app vẫn chạy: mọi lời gọi AI lùi về rule, report đánh dấu degraded. Muốn chạy
không tốn tiền dù đã có key, thêm `--ai.openai.api-key=` vào tham số.

Kết quả import đúng: **1 059 document / 20 cuộc gọi**. Importer gửi theo lô 500 document.

> Các lệnh `--analyze*`, `--metrics*`, `--timeline`, `--parse-*` lấy signaling từ
> Elasticsearch, nên phải bật ES trước. Nếu quên, `--analyze-all` sẽ ra `UNKNOWN` cho mọi cuộc
> (report ghi rõ *"Không truy vấn được signaling"*); `--parse-*` vẫn parse log đính kèm và báo thiếu signaling. `--evaluate` kiểm
> signaling trước khi chạy và cảnh báo ngay ở đầu báo cáo. Web UI và `--evaluate` cũng lấy signaling từ ES.

---

## Lệnh

### Build và test

```bash
./mvnw clean compile
./mvnw test          # unit test không cần Elasticsearch; 6 test tích hợp ES chạy qua
                     # Testcontainers khi có Docker, không có Docker thì tự bỏ qua
```

### Elasticsearch + Kibana

```bash
docker compose up -d elasticsearch    # chỉ Elasticsearch (nhẹ nhất)
docker compose up -d                  # thêm Kibana tại http://localhost:5601
docker compose down                   # dừng, GIỮ dữ liệu
docker compose down -v                # dừng và XOÁ dữ liệu
```


### Nạp data mẫu

```bash
# Nạp bình thường — idempotent, chạy lại không sinh bản trùng
./mvnw spring-boot:run -Dspring-boot.run.arguments="--import-signaling=<thu-muc-data>"

# Xoá index rồi nạp lại từ đầu
./mvnw spring-boot:run -Dspring-boot.run.arguments="--import-signaling=<thu-muc-data> --recreate-index"
```

Importer quét đệ quy mọi file `signaling.json` dưới thư mục được chỉ định.

### Toàn bộ lệnh

| Lệnh | Làm gì |
|---|---|
| `--import-signaling=<thư-mục>` | Nạp signaling vào Elasticsearch (idempotent) |
| `--recreate-index` | Xoá index rồi nạp lại từ đầu |
| `--fetch-call=<Call-ID>` | Đọc signaling của một cuộc gọi từ Elasticsearch |
| `--parse-call=<thư-mục>` | Parse log của một cuộc gọi, in thống kê theo loại event |
| `--parse-all=<thư-mục-gốc>` | Parse toàn bộ, đo tỉ lệ parse và số cảnh báo |
| `--timeline=<thư-mục>` | Dựng timeline: correlate leg, dedupe, đo lệch đồng hồ |
| `--metrics=<thư-mục>` | In bộ chỉ số của một cuộc gọi |
| `--metrics-all=<thư-mục-gốc>` | Bảng chỉ số của toàn bộ cuộc gọi |
| `--analyze=<thư-mục>` | **Phân tích đầy đủ, in report theo mẫu mục 4.5** |
| `--analyze-all=<thư-mục-gốc>` | Bảng verdict toàn bộ, kèm kiểm tra schema |
| `--parse-request=<câu hỏi>` | Thử Request Parser: intent, trọng tâm, Call-ID |
| `--ai-context=<thư-mục>` · `--ai-context-all=<thư-mục-gốc> --out=<thư-mục-ra>` | In đúng dữ liệu (đã làm sạch) sẽ gửi AI; không gọi AI |
| `--ai-analyze=<thư-mục>` · `--ai-analyze-all=<thư-mục-gốc>` | Request Parser → pipeline → AI thật → Guardrails (2 lời gọi API mỗi cuộc) |
| `--sanitize-dir=<thư-mục> --out=<thư-mục-ra>` | Làm sạch toàn bộ log thô để kiểm độc lập bằng `scripts/verify-sanitizer` |
| `--evaluate=<file case> --logs=<thư-mục log> [--repeat=n] [--out=<thư-mục>]` | **Evaluation Runner**: chạy bộ case YAML (mẫu MVP mục 6.3) qua đúng luồng Chat API, chấm metric mục 6.5, ghi `.md` + `.json` vào `target/evaluation/` |
| `--rescore=<file .json> [--cases=<file case>] [--out=<thư mục>]` | Chấm lại một lượt `--evaluate` từ file `.json` của nó theo đáp án hiện tại của file case — không gọi AI, không cần Elasticsearch |
| `--batch-analyze [--logs=<thư-mục log>]… [--out=<thư mục>]` | **Phân tích hàng loạt** (S3-T10): mọi Call-ID trong Elasticsearch, chỉ rule; thống kê phân bố verdict / loại lỗi tách theo nguồn dữ liệu, ghi `.md` + `.json` vào `target/batch/` và index `call-analysis-results` cho dashboard. `--logs` lặp được, tuỳ chọn: thư mục trùng Call-ID được dùng làm log máy |
| profile `web` | Bật Web UI và `POST /api/analyze` (multipart: `message` + `files`) |

Trên PowerShell dùng `.\build.ps1` thay cho `./mvnw` và đặt tham số `-D…` trong nháy kép, ví dụ
`.\build.ps1 spring-boot:run "-Dspring-boot.run.profiles=web"`.

### Chạy bộ case `held-out`

Tập `held-out` do Mentor chuẩn bị, giữ riêng và tự chạy (MVP 6.3). **Dự án không có và chưa từng xem tập này**; mọi
số đo trong repo là trên tập `dev`. Mục này hướng dẫn chạy Evaluation Runner (MVP 6.1 T9) trên một bộ case bất kỳ —
runner gửi từng câu hỏi qua **đúng luồng của Web UI**, rồi chấm các metric MVP 6.5.

Các bước dưới đây đã chạy thử ngày 2026-10-07 trên Windows (PowerShell), dùng **2 cuộc gọi lấy từ data mẫu** xếp theo
bố cục bên dưới — chỉ để kiểm các lệnh chạy đúng, không phải tập `held-out`. Đường dẫn `D:\held-out` trong các lệnh là ví
dụ, thay bằng nơi đặt bộ case thật.

**Cần cài:** Git · JDK 21 (Spring Boot 3.5 không chạy trên JDK 24 trở lên) · Docker Desktop (để chạy Elasticsearch).

#### Bước 1 — Lấy code, build, bật Elasticsearch

```powershell
git clone https://github.com/hason0510/call-analysis-assistant.git
cd call-analysis-assistant
.\build.ps1 -q clean compile
docker compose up -d elasticsearch
curl.exe -s http://localhost:9200/_cluster/health      # đợi "status":"green" hoặc "yellow"
```

`build.ps1` tìm JDK 21 lần lượt ở: biến môi trường `JAVA_HOME_21`, `%USERPROFILE%\.jdks\jbr-21.0.10`,
`C:\Program Files\Java\jdk-21`, `C:\Program Files\Eclipse Adoptium\jdk-21`, và chỉ dùng nó trong phiên chạy, không đổi
cấu hình máy. JDK ở chỗ khác thì đặt `$env:JAVA_HOME_21 = "<đường dẫn JDK 21>"` trước khi chạy.

#### Bước 2 — Xếp log và viết file case

Thư mục log: **mỗi cuộc gọi một thư mục con đặt tên đúng Call-ID**, ở cấp nào cũng được — `held-out/<Call-ID>/` hay
`held-out/fail/<Call-ID>/` đều tìm thấy. Có `signaling.json` trong thư mục đó thì bước 3 nạp được signaling.

File case theo đúng mẫu MVP 6.3; nhiều case trong một file, ngăn cách bằng `---`. Ví dụ dưới dùng một cuộc gọi của
data mẫu chỉ để minh hoạ định dạng:

```yaml
case_id: case-001
call_id: 2D9057AA-C496-48B2-946A-98FA2896D086
files: [callee_endcall.log, callee_webrtc.log]   # tên file trần, tìm trong thư mục <call_id>
questions:
- "Phân tích cuộc gọi này giúp mình"
- text: "Vì sao bên nhận không nghe được?"       # tuỳ chọn: kèm nhãn để chấm Intent Accuracy
  expected_intent: ANALYZE_WITH_FOCUS
expected_verdict: FAIL
expected_quality_flag: false
expected_issue_category: ICE_FAILURE
expected_evidence: null
split: held-out
```

- `call_id` là thứ runner dùng để biết log thuộc cuộc nào: WebRTC log không ghi Call-ID. Call-ID có trong câu hỏi thì
  câu hỏi được ưu tiên.
- Trường đáp án nào để trống hoặc `null` thì metric tương ứng ghi `N/A` kèm lý do, không tính là sai.
- Câu hỏi viết dạng chuỗi trơn như mẫu MVP là đủ; dạng `{text, expected_intent}` là phần dự án thêm để chấm intent.
- Muốn thử ca "thiếu file" thì **không liệt kê** file đó. File có trong `files` mà không có trên đĩa là lỗi của case:
  case đó bị bỏ qua và nêu ở mục "Case không chạy được" của báo cáo; các case khác vẫn chạy.
- Giá trị enum nên viết như mẫu (`FAIL`, `ICE_FAILURE`, `ANALYZE_CALL`…). Viết thường, một file không đặt trong `[ ]`,
  hay cờ `"true"` trong ngoặc kép vẫn được chấp nhận; giá trị ngoài taxonomy thì case bị loại và nêu tên.

#### Bước 3 — Nạp signaling vào Elasticsearch

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--import-signaling=D:\held-out"
```

Đọc mọi `signaling.json` dưới thư mục đó. Chạy lại không sinh bản trùng. **Bỏ bước này thì gần như mọi case ra
`UNKNOWN`**, vì hệ thống không kết luận khi thiếu signaling. Runner kiểm trước khi chạy và in
`Signaling: có cho x/y Call-ID` ở đầu output và đầu báo cáo.

#### Bước 4 — Chạy runner

Không dùng AI (không cần key, không tốn tiền; kết luận lấy từ rule, mọi lần chạy ghi là degraded):

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--evaluate=D:\held-out\cases.yaml --logs=D:\held-out --repeat=5 --ai.openai.api-key="
```

Có dùng AI (`gpt-4o-mini`): tạo file `.env` ở thư mục gốc của repo, chép từ `.env.example` và điền `OPENAI_API_KEY`,
rồi chạy lệnh trên **bỏ** `--ai.openai.api-key=`. Chi phí đo được (lượt 2026-10-14) ≈ 0,001 USD và ≈ 4-6 s cho mỗi lần chạy một câu hỏi.
Ví dụ 20 case × 4 câu × 5 lần = 400 lần chạy ≈ 0,4 USD, khoảng 40 phút.

`--repeat=5` theo cách đo Consistency của MVP 6.5 (5 lần × các cách hỏi). Thêm `--out=<thư-mục>` để đổi nơi ghi kết quả.

#### Bước 5 — Đọc kết quả

Mặc định ở `target/evaluation/`:

| File | Nội dung |
|---|---|
| `evaluation-<thời điểm>.md` | Bảng metric MVP 6.5 (giá trị, số lần, target, cách đo), bảng AI vs Rule theo case, vi phạm Guardrails, case không chạy được |
| `evaluation-<thời điểm>.json` | Từng lần chạy: câu hỏi đã làm sạch, intent, kết luận, nguồn kết luận, lý do fallback, số token, latency — để soát lại |

Báo cáo **không chứa** log hay giá trị nhạy cảm gốc; Security Leakage chỉ lưu số đếm.

Đầu báo cáo có dòng **`Prompt AI`**: 12 ký tự đầu SHA-256 của hai system prompt và hai JSON Schema của AI. Hai lượt
chạy cùng dòng này thì AI nhận cùng chỉ dẫn; khác thì số liệu của hai lượt không so trực tiếp được.

**Chấm lại không tốn tiền.** Bổ sung đáp án vào file case (ví dụ `expected_issue_category`) rồi chạy:

```powershell
.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--rescore=target\evaluation\evaluation-<thời điểm>.json"
```

Câu trả lời của hệ thống giữ nguyên như lúc chạy; chỉ đáp án lấy từ file case hiện tại (mặc định đúng file ghi trong
`.json`, đổi bằng `--cases=`). Câu hỏi phải giữ đúng thứ tự vì lần chạy được ghép theo `case_id` + số thứ tự câu; lần
chạy nào không ghép được thì nêu ở mục "Case không chạy được". Kết quả ghi cạnh file gốc, tên `…-rescored-<thời điểm>`.

Trên macOS / Linux: đặt `JAVA_HOME` trỏ tới JDK 21 rồi dùng `./mvnw` thay cho `.\build.ps1`, cùng tham số
(phần này chưa chạy thử).

### Đọc report: evidence trỏ về đâu

Mỗi dòng evidence có dạng `[EVnn][nguồn]`:

| Nguồn | Ví dụ | Nghĩa |
|---|---|---|
| Log đính kèm | `[EV02][caller_endcall.log:11]` | Dòng 11 của file `caller_endcall.log` |
| Signaling | `[EV09][signaling#87]` | Sự kiện signaling **thứ 87** (đếm từ 1) trong kết quả truy vấn Elasticsearch |

Signaling không đến từ file nào nên không có "số dòng". Số sau `#` là vị trí của sự kiện
trong mảng `events` của bản export (`ordinal` = 86 trong index `signaling-events`), **không phải**
số dòng của file `signaling.json` trong thư mục mẫu.

Với kết luận `FAIL` hay cờ chất lượng, evidence luôn gồm cả **dòng căn cứ của chính kết luận**:
dòng ACK mang mã lỗi của server, dòng TURN báo lỗi, dòng hết giờ chờ candidate, mẫu stats mất gói
nặng nhất. Mã lỗi trong câu tóm tắt được đọc nguyên văn từ log, không tự điền.

---

## Cấu trúc

```text
io.hason.callanalysis
├── cli/               ADAPTER VÀO — 13 lệnh dòng lệnh
├── controller/        ADAPTER VÀO — Chat API cho Web UI, chỉ chạy ở profile web (S2-T2)
├── service/           ĐIỀU PHỐI
│   ├── CallLogNormalizationService, AnalyzeCallService      pipeline Sprint 1
│   ├── ChatAnalysisService, RequestParserService, AiVerdictService   luồng Chat API (S2)
│   ├── EvaluationService                                    Evaluation Runner (S2-T9)
│   ├── BatchAnalysisService                                 phân tích hàng loạt (S3-T10)
│   └── port/SignalingSource, AiAnalyzer, IntentClassifier,  cổng ra
│            RequestRecordSink (S3-T9), ApplicationLogTap (S3-T6),
│            CallCatalog, BatchResultSink (S3-T10)
├── domain/            LÕI THUẦN — không Spring, không I/O
│   ├── event/             Canonical Event Model (S1-T2)
│   ├── parse/             2 parser (end call log, WebRTC log) + FileTypeDetector (S1-T3)
│   ├── signaling/         Chuẩn hoá signaling từ ES + suy ra leg (S1-T3)
│   ├── validation/        File Validator (S2-T3)
│   ├── timeline/          Correlate, dedupe, lệch đồng hồ (S1-T4)
│   ├── taxonomy/          6 issue category (S1-T5)
│   ├── metrics/           Bộ chỉ số Core và "Nếu kịp" (S1-T6, T11)
│   ├── evidence/          Chọn và đánh ID evidence (S1-T7)
│   ├── rule/              Tín hiệu thô + rule verdict (S1-T7)
│   ├── request/           Request Parser: intent, trọng tâm, Call-ID (S2-T4)
│   ├── ai/                Context gửi AI, đầu ra AI (S2-T5); mã chỉ số (S3-T1), RetryPolicy (S3-T8)
│   ├── confidence/        ConfidencePolicy theo bảng MVP 7.2 (S3-T3)
│   ├── guardrail/         Guardrails G01-G05 (S2-T6)
│   ├── security/          Danh mục + Sanitizer dữ liệu nhạy cảm (S1-T9, S2-T7)
│   ├── report/            Model report, builder, renderer mẫu 4.5 (S1-T8, S2-T2)
│   ├── evaluation/        Chấm benchmark theo metric mục 6.5 (S2-T9), --rescore (S3)
│   ├── batch/             Thống kê phân tích hàng loạt theo nguồn dữ liệu (S3-T10)
│   └── observability/     Bản ghi request ghi vào ES cho Kibana (S3-T9)
└── infrastructure/    ADAPTER RA
    ├── es/                Elasticsearch: signaling, bản ghi request (S3-T9), danh sách Call-ID + kết quả hàng loạt (S3-T10)
    ├── ai/                OpenAI (Structured Outputs)
    ├── file/              Đọc file log từ đĩa
    ├── evaluation/        Đọc file case YAML, tìm file log của case
    ├── taxonomy/          Nạp taxonomy.yaml
    ├── security/          Nạp danh mục, dựng Sanitizer
    ├── logging/           Lọc log qua Sanitizer; log JSON đã làm sạch (S3-T5)
    └── report/            Kiểm tra report theo JSON Schema
```

Cấu hình để ở dạng **dữ liệu**, không hard-code trong Java; Sprint 2 nạp thẳng chúng vào prompt, Sanitizer và
Guardrails:

| File | Dùng cho |
|---|---|
| `resources/taxonomy.yaml` | 6 issue category, kèm trạng thái đã kiểm chứng hay chưa |
| `resources/sensitive-data-inventory.yaml` | Phân loại dữ liệu nhạy cảm, chính sách xử lý cho Sanitizer |
| `resources/schema/report-v1.schema.json` | Cấu trúc report; report sai schema bị dựng lại từ rule |
| `resources/schema/ai-analysis.schema.json` · `request-intent.schema.json` | Structured output của AI phân tích và AI phân loại câu hỏi |
| `resources/prompts/*.system.txt` | Prompt của hai lời gọi AI |
| `resources/es/signaling-mapping.json` · `analysis-requests-mapping.json` · `call-analysis-results-mapping.json` | Mapping Elasticsearch, `dynamic: strict` (signaling, bản ghi request T9, kết quả hàng loạt T10) |
| `benchmark/dev-cases.yaml` · `intent-check.yaml` | Bộ case benchmark `dev` (20 case, 69 câu) và bộ câu kiểm phân loại |
| `scripts/benchmark-variants/make_variants.py` | Tạo file biến thể cho case-016 (F02, tên file sai loại) và case-019 (dữ liệu nhạy cảm giả), đặt ngoài repo |
| `scripts/error-analysis/analyze_runs.py` | Phân tích lỗi trên các lượt benchmark (S3-T1) |
| `scripts/kibana/setup_views.py` · `setup_dashboard.py` | Dựng data view, saved search (S3-T9), dashboard kết quả (S3-T11) và dashboard phân tích hàng loạt (S3-T10) trên Kibana |

Kiến trúc theo `Controller → Service → Domain → Infrastructure`, triển khai kiểu
Hexagonal (Ports & Adapters).

**Luật bắt buộc:** `domain` không import `infrastructure`, không có annotation Spring,
không đọc file, không gọi mạng. Nhờ vậy toàn bộ test chạy trong mili-giây mà không cần Docker —
điều kiện cần để đạt yêu cầu về tính nhất quán của kết quả phân tích.

Lý do đằng sau các quyết định thiết kế: xem [`docs/design-decisions.md`](docs/design-decisions.md).

---

## Trạng thái — Sprint 1 hoàn thành

| Task | Trạng thái | Nằm ở |
|---|---|---|
| T1 — Elasticsearch local + import | ✅ | `docker-compose.yml`, `infrastructure/es/` |
| T2 — Canonical Event Model | ✅ | `domain/event/` |
| T3 — Log Normalizer | ✅ | `domain/parse/`, `domain/signaling/` |
| T4 — Timeline Builder | ✅ | `domain/timeline/` |
| T5 — Verdict & Issue Taxonomy | ✅ | `resources/taxonomy.yaml`, `domain/taxonomy/` |
| T6 — Call Metrics Calculator | ✅ | `domain/metrics/` |
| T7 — Evidence Engine + Rule Verdict | ✅ | `domain/evidence/`, `domain/rule/` |
| T8 — Report Schema v1 | ✅ | `resources/schema/report-v1.schema.json`, `domain/report/` |
| T9 — Sensitive Data Inventory | ✅ | `resources/sensitive-data-inventory.yaml`, `domain/security/` |
| T10 — AI Provider Proposal | ✅ | [`docs/ai-provider-proposal.md`](docs/ai-provider-proposal.md) — được duyệt 2026-10-05: OpenAI `gpt-4o-mini` |
| T11 — Chỉ số mở rộng (Nếu kịp) | ✅ | `domain/metrics/`: latency API nội bộ, số WARN / ERROR theo service, ISP / ASN, khoảng trống PAIR_PING |

### Kết quả đo được — Sprint 1

| | |
|---|---|
| Verdict Accuracy | **13/13** trên toàn bộ data có nhãn (6 fail + 7 success) |
| Parse | 29 238 dòng → 28 522 event, **1 cảnh báo** (bản export signaling của `DE7DD314` bị cắt 200/201), 0 file không nhận diện được |
| Chỉ số khớp giá trị tính tay | **9 cuộc gọi** (yêu cầu tối thiểu 5); 280/280 giá trị trên 20 cuộc khớp script độc lập `scripts/verify-metrics/run.sh` |
| Report hợp lệ theo schema v1 | **20/20** |
| Test | **253** cuối Sprint 1: 250 unit test (không cần Elasticsearch) + 3 test tích hợp ES thật qua Testcontainers (import lặp 2 lần không sinh bản trùng, chia lô bulk) |

## Trạng thái — Sprint 2: 11/11 task xong

| Task | Trạng thái | Nằm ở |
|---|---|---|
| T1 — Web UI tối giản | ✅ | `resources/static/` |
| T2 — Chat API + Orchestration + Report Renderer | ✅ | `controller/`, `service/ChatAnalysisService`, `domain/report/ReportRenderer` |
| T3 — File Validator | ✅ | `domain/validation/` |
| T4 — Request Parser | ✅ | `service/RequestParserService`, `domain/request/` |
| T5 — AI Analysis Engine | ✅ | `service/port/AiAnalyzer`, `infrastructure/ai/`, `resources/prompts/` |
| T6 — Guardrails | ✅ | `domain/guardrail/` |
| T7 — Sensitive Data Detector & Sanitizer | ✅ | `domain/security/`, `infrastructure/security/`, `infrastructure/logging/` |
| T8 — Fallback | ✅ | `service/AiVerdictService` |
| T9 — Evaluation Runner | ✅ | `cli/EvaluateCommand`, `service/EvaluationService`, `domain/evaluation/` |
| T10 — Benchmark `dev` | ✅ | `benchmark/dev-cases.yaml` |
| T11 — So sánh cách dựng context (Nếu kịp) | ✅ | Làm trong Sprint 3 theo ví dụ mentor gửi 2026-10-15: `ai.context-format=text` (`AiContextText`) — kết quả ở mục Sprint 3 bên dưới |

Đối chiếu từng task với code, test và số đo: [`docs/minh-chung-sprint-2.md`](docs/minh-chung-sprint-2.md). Test cuối
Sprint 2: 390.

### Kết quả đo được — Sprint 2

Benchmark `dev` (`benchmark/dev-cases.yaml` lúc đó: 18 case, 63 câu hỏi), mỗi câu chạy 5 lần = 315 lần chạy,
`gpt-4o-mini`, 2026-10-06. Cách tính từng metric: [`docs/evaluation-metrics.md`](docs/evaluation-metrics.md).

| Metric (MVP mục 6.5) | Kết quả | Target |
|---|---|---|
| Verdict Accuracy | **100%** (280/280) trên `dev` | ≥ 85% — đo chính thức trên `held-out` do Mentor chạy, chưa có kết quả |
| Metric Correctness | **100%** (280/280) | 100% |
| Security Leakage (input / output) | **0%** (0/7 005 giá trị gốc) / **0** | 0 |
| Issue Category Accuracy | N/A — ground truth chưa có nhãn category | ≥ 70% |
| Consistency | **100%**, 17/17 case giống hệt qua 5 lần × 3-5 cách hỏi (case-003 toàn câu ngoài phạm vi, không có report để so) | ≥ 95% |
| Template Compliance | **100%** | 100% |
| Intent Accuracy | **100%** (290/290 câu có nhãn) | ≥ 90% |
| Unsupported Claim Rate | report cuối **0%**; đầu ra thô của AI trước Guardrails 0-3% tuỳ lượt | 0% |
| Pipeline Success Rate | **100%** (280/280) | ≥ 95% |
| Latency | P50 **3,8 s** · P95 **8,4 s** | — |
| Chi phí AI | ≈ 0,20 USD / 315 lần chạy (~3 000 token vào, ~175 token ra mỗi lần phân tích) | — |

## Trạng thái — Sprint 3: 7/7 task Core, 4/4 task Nếu kịp

Cập nhật 2026-10-20. Đối chiếu từng task với code, test và số đo:
[`docs/minh-chung-sprint-3.md`](docs/minh-chung-sprint-3.md) (`mvn test` 2026-10-20: 600 test, 0 lỗi). Lý do từng thay
đổi: [`docs/design-decisions.md`](docs/design-decisions.md) mục 32–49.

| Task | Trạng thái | Nằm ở |
|---|---|---|
| T1 — Improve Accuracy & Consistency | 🟡 | AI nhắc chỉ số bằng mã, code điền giá trị (`MetricCodes`); evidence mang cờ `ruleBasis`; phân tích lỗi `scripts/error-analysis/`. Issue Category Accuracy chờ nhãn của mentor |
| T2 — Explainability & Suggestion | ✅ | Bộ đề xuất chuẩn của rule luôn giữ, AI chỉ bổ sung (`aiSuggestions`); dòng Tóm tắt ca FAIL do code viết (`ReportBuilder`) |
| T3 — Confidence Design | ✅ | `ConfidencePolicy` theo bảng MVP 7.2, report ghi lý do |
| T4 — Reliability Hardening | ✅ | Timeout AI / ES / trình duyệt, 429 tách lý do, `GarbledText`, ES tắt vẫn ra report, sanitizer 9,4 s → 1,0 s |
| T5 — Observability | ✅ | Log JSON đã làm sạch (profile `web`), dòng AI call đúng 7 trường MVP 6.2 (`AiCallLog`) |
| T6 — Security Evaluation Report | ✅ | [`docs/bao-cao-bao-mat.md`](docs/bao-cao-bao-mat.md) — S01-S08 qua, leakage 0 ở AI input / output / application log |
| T7 — Peer Evaluation | ✅ | [`docs/peer-evaluation-report.md`](docs/peer-evaluation-report.md) — chạy repo của Trần Anh Tú (commit `a5010a6`) theo Runbook của bạn ấy trên cả `ai20k_sample` và `ai20k_new_dataset`, 2026-10-19: Runbook đủ nhưng phải tự xử lý 3 chỗ, 5 issue kèm bước tái hiện, 3 điểm học được; benchmark `dev` tái hiện đúng số bạn ấy công bố. Chiều ngược lại: bạn ấy review repo này (Runbook "đủ", 2 issue) — đã sửa cả hai ngày 2026-10-20 (Final Evaluation Report mục 7) |
| T8 — Retry & Error Classification (Nếu kịp) | ✅ | `RetryPolicy`: chỉ thử lại lỗi tạm thời, tối đa 2 lần gọi, ngân sách 45 s cho cả hai |
| T9 — Kibana View (Nếu kịp) | ✅ | Mỗi request một bản ghi vào index `analysis-requests` (chỉ mã / số / cờ), `scripts/kibana/setup_views.py` |
| T10 — Batch Analysis (Nếu kịp) | ✅ | `--batch-analyze`: mọi Call-ID trong ES qua đúng pipeline, chỉ rule; thống kê tách "chỉ signaling" / "signaling + log máy"; index `call-analysis-results` + dashboard "Phân tích hàng loạt — toàn kho" |
| T11 — Result Dashboard (Nếu kịp) | ✅ | `scripts/kibana/setup_dashboard.py` — Runbook mục 5 |

**Tài liệu bàn giao (MVP 7.4):** [Runbook](docs/runbook.md) ✅ · [Architecture & Security
Design](docs/architecture-security-design.md) ✅ · [Final Evaluation Report & Known
Limitations](docs/final-evaluation-report.md) ✅ (còn chờ kết quả `held-out` của mentor) · [Kịch bản Final
Demo](docs/demo-sprint-3.md) ✅ (chạy thật 2026-10-12) · [Peer Evaluation
Report](docs/peer-evaluation-report.md) ✅.

**Dataset mới của mentor** (`ai20k_new_dataset`, 7 cuộc, chưa có nhãn): chạy được cả 7 cuộc sau khi sửa 5 lỗi tool —
signaling có 2 định dạng export, callee nhiều thiết bị, chỉ số media không ghi chiều, cuộc rớt giữa chừng, thêm rule
`NETWORK_DELAY_JITTER`. Kết luận trên data mẫu không đổi (13/13). Chi tiết: Final Evaluation Report mục 5.

**Theo nhận xét Sprint 2 của mentor (nhận 2026-10-15):**

| Mentor đề xuất | Trạng thái |
|---|---|
| Retry có kiểm soát cho lời gọi OpenAI | ✅ Sprint 3 T8 |
| Profile web chỉ nghe `127.0.0.1`; giới hạn số file mỗi request | ✅ `server.address: 127.0.0.1`; tối đa 10 file (`call-analysis.files.max-files-per-request`), quá thì từ chối trước khi đọc file và gọi AI |
| Giao diện không ghi đè file trùng tên | ✅ cùng tên mà khác file thì giữ cả hai và cảnh báo |
| IP ví dụ theo RFC 5737; dọn `.gitignore` | ✅ IP trong tài liệu, inventory và test đổi sang `192.0.2.x` / `198.51.100.x` / `203.0.113.x` (IPv6: `2001:db8::`); `.gitignore` liệt kê từng file riêng thay cho chặn `*.md` |
| Sửa regex G05 cho số liền đơn vị (`250ms`, `45s`), đo lại Unsupported Claim | ✅ `Guardrails` + test; đo lại `dev` ×5 (15/10): **0/310** — không còn là cận dưới |
| Cổng fail-closed trước khi gửi AI | ✅ `OutboundGate` trong `OpenAiChatClient`, cả hai lời gọi AI; chạy không key trên 27 cuộc: 0 lần chặn nhầm |
| Ngưỡng packet loss / jitter, bảng độ nhạy | ✅ mất gói CẢ CUỘC từ 5 % mới gắn cờ (trước: một mẫu > 5 % / MOS < 3,5 / hasMediaPoor); jitter trung bình 30 ms đã khớp sẵn. Bảng độ nhạy: `scripts/quality-thresholds/sensitivity.py`. `dev` không đổi; `271D1FAF` và 3 cuộc dataset mới hết cờ, kịch bản demo scenario 2 chuyển sang `B5F19381` |
| T11 Sprint 2 (structured vs semi-structured) | ✅ `ai.context-format=text` (`AiContextText`), hai lượt `dev` ×5: accuracy / consistency như nhau (100%), văn bản ít hơn 7,5 % token, JSON cho evidence tốt hơn (trích đủ căn cứ 96 so với 77/115) → giữ JSON. Final Evaluation Report mục 3b |
| Quay demo đủ 6 kịch bản trên trình duyệt | ⬜ |
| Peer Evaluation | ✅ [`docs/peer-evaluation-report.md`](docs/peer-evaluation-report.md) (2026-10-19) |

### Kết quả đo được — Sprint 3

Benchmark `dev` (20 case, 69 câu hỏi), mỗi câu chạy 5 lần = 345 lần chạy, `gpt-4o-mini`, 2026-10-14, ≈ 0,32 USD.

| Metric (MVP mục 6.5) | Sprint 2 | Cuối Sprint 3 | Target |
|---|---|---|---|
| Verdict Accuracy | 100% (280/280) | **100%** (310/310) | ≥ 85% — đo chính thức trên `held-out` do Mentor chạy, chưa có kết quả |
| Metric Correctness | 100% | **100%** (310/310) | 100% |
| Security Leakage (input / output) | 0/7 005 · 0 | **0/8 255 · 0** (gồm application log) | 0 |
| Issue Category Accuracy | N/A | N/A — ground truth chưa có nhãn category | ≥ 70% |
| Consistency | 100% | **100%**, 19/19 case giống hệt qua 5 lần | ≥ 95% |
| Template Compliance | 100% | **100%** | 100% |
| Intent Accuracy | 100% (290/290) | **100%** (320/320) | ≥ 90% |
| Unsupported Claim — AI trước Guardrails / report cuối | 2,9% / 0% | **0% (0/309) / 0%** | 0% |
| Pipeline Success Rate | 100% | **100%** (310/310; 1 lần OpenAI quá giờ, lùi về rule) | ≥ 95% |
| Latency P50 / P95 | 3,8 s / 8,4 s | 3,6 s / 14,9 s — P95 do OpenAI chậm lúc đo, phần không phải AI dưới 1 s. Lượt chạy lại cùng prompt sau đó một giờ: 3,0 s / 4,5 s, 0 fallback (Final Evaluation Report mục 2) | — |

**Đo lại sau góp ý của mentor** (2026-10-15, cùng prompt, có G05 mới, cổng fail-closed, ngưỡng mất gói cả cuộc): mọi
metric như cột "Cuối Sprint 3", Unsupported Claim 0/310 với G05 mới, 0 fallback, P50 / P95 3,1 s / 4,3 s. Chi tiết và so
sánh T11: Final Evaluation Report mục 3b.

**Chạy lại trước khi nộp** (MVP 7.1, 2026-10-19, bản code cuối): S01-S08, G01-G05, F01-F04 kèm enum lạ / thiếu trường /
input quá lớn — 67/67 test qua; `dev` ×5 (345 lần chạy, ≈ 0,32 USD) — mọi metric như cột "Cuối Sprint 3", Unsupported
Claim 0/310, 0 fallback, P50 / P95 2,95 s / 4,04 s. Bảng so với Sprint 2: Final Evaluation Report mục 9.

**Phân tích hàng loạt** (T10, 2026-10-19, 27 Call-ID trong ES, chỉ rule, 0 USD). T10 làm sau lượt chạy lại trên và không
sửa dòng nào của luồng phân tích, nên số đo trên vẫn là của bản nộp. Final Evaluation Report mục 10.

| Nguồn dữ liệu | SUCCESS | FAIL | UNKNOWN | Loại lỗi |
|---|---|---|---|---|
| Chỉ signaling (không `--logs`) | 0 | 11 | 16 | 11 `SIGNALING_FAILURE` |
| Signaling + log máy (`--logs` hai dataset) | 13 (2 có cờ chất lượng) | 14 | 0 | 6 TURN · 5 SIGNALING · 3 ICE · 1 PACKET_LOSS · 1 DELAY_JITTER |

Chỉ có signaling thì không cuộc nào được kết luận SUCCESS: signaling không cho biết âm thanh có tới không (`2D9057AA`
signaling hoàn hảo mà 0 byte âm thanh), nên UNKNOWN ở nhóm này là "thiếu log máy". Loại lỗi ở nhóm này chỉ là triệu chứng
phía server: 6 cuộc lỗi TURN hiện ra thành `SIGNALING_FAILURE`. Báo cáo và dashboard tách hai nhóm và ghi rõ điều này.

---

## Known Limitations

Cập nhật cuối Sprint 3 (2026-10-20). Những mục đã sửa trong Sprint 3 không còn ở đây. Bản kèm số đo của lượt benchmark
cuối nằm ở [`docs/final-evaluation-report.md`](docs/final-evaluation-report.md) mục 7.

**Chưa làm hoặc còn chờ mentor**

- Sprint 3 T10 (Batch Analysis) chỉ dùng signaling (đúng chữ "Call-ID trong ES local") thì gần như vô dụng: trên 13 cuộc
  có nhãn 5 đúng, 8 `UNKNOWN`, và lỗi TURN hiện thành `SIGNALING_FAILURE`. Có `--logs` thì 13/13. Đã làm cả hai chế
  độ và tách nhóm trong báo cáo; chế độ mentor muốn dùng vẫn chờ trả lời (câu 17).
- Issue Category Accuracy chưa đo được: ground truth chỉ có fail / success. Runner và `--rescore` đã sẵn để chấm khi có
  nhãn.
- Kết quả `held-out`: chờ mentor chạy Evaluation Runner và gửi lại.
- Benchmark `dev` thiếu loại ca "SUCCESS chất lượng kém": không có cuộc gọi chất lượng kém có nhãn. Hai ca khác của MVP
  6.3 chỉ phủ một phần: FAIL thiết lập chỉ có kiểu server từ chối (mã 428, case-004 / 007), không có kiểu signaling
  timeout / "không tìm thấy session"; "bình thường có WARN" không có case riêng mà dựa vào việc mọi cuộc SUCCESS đều có
  5-13 event WARN.

**Dữ liệu và chỉ số**

- Trên data mẫu, chỉ số chất lượng (MOS, packet loss, jitter, RTT) chỉ có giá trị thật ở **8 leg / 5 cuộc gọi**. 6 cuộc
  gọi không có end call log (`5E0800AE`, `6A7CE985`, `F3D7914B`, `7B56D7AD`, `E9D6C112`, `AA9791CE`) nên các chỉ số này
  là `N/A`. Leg chưa từng nhận gói audio nào thì app ghi 0 vào MOS/loss/RTT; report hiển thị `N/A` kèm lý do thay vì in
  số 0 đó (MVP mục 4.3).
- MOS, RTT, jitter trong bảng là giá trị **lúc kết thúc** cuộc gọi: bản ghi tổng kết của app trùng đúng mẫu stats cuối
  (8/8 leg có media), và log không có bộ đếm để tính lại cho cả cuộc. Report ghi rõ điều này ở mục Giới hạn dữ liệu.
  Packet loss thì tính được cho **cả cuộc** từ hai bộ đếm cộng dồn `audio.packetsLost` / `audio.packetsReceived`, kèm
  mẫu cao nhất; `audio.packetLostPercent` chỉ là tỉ lệ trong khoảng giữa hai lần đo nên không dùng cho bảng.
- "Thời gian với tới callee" (`INVITE` → `TRYING`) chỉ đo được khi callee được đánh thức qua push. Callee đang online
  nhận `INVITE` qua WebSocket thì không gửi `TRYING`, nên chỉ số này là `N/A`.
- "Khoảng trống PAIR_PING lớn nhất" (chỉ số proxy) chỉ đo giữa hai lần ping liên tiếp, không thấy khoảng im lặng từ lần
  ping cuối tới lúc kết thúc.
- WebRTC log chỉ có mốc thời gian tương đối nên chưa đồng bộ được với timeline signaling. Trong mục Evidence, các dòng
  WebRTC (ICE) luôn xếp sau các dòng có giờ tuyệt đối, dù có thể xảy ra trước.

**Rule và taxonomy**

- Không có cuộc gọi nào có nhãn bị suy giảm chất lượng, nên `NETWORK_PACKET_LOSS` và `NETWORK_DELAY_JITTER` còn
  `UNVALIDATED` và mọi cờ chất lượng ở độ tin cậy LOW.
- Ngưỡng mất gói theo mentor (đổi 2026-10-15): Cờ `NETWORK_PACKET_LOSS` bật khi một leg mất gói CẢ CUỘC từ 5 %
  (packetsLost ÷ (packetsLost + packetsReceived)) — mentor: "packet loss < 5 %, jitter < 30 ms là ổn định". Trước đó cờ
  bật khi MỘT mẫu stats > 5 %, MOS < 3,5 hoặc hasMediaPoor = 1. Số mẫu vượt, MOS thấp nhất, hasMediaPoor vẫn in ở phần
  "Đối chiếu" nhưng không tự bật cờ: cuộc MOS rất thấp mà mất gói cả cuộc thấp (`FE4E4BDC`: MOS 1,00, một mẫu mất 100 %)
  nay là `SUCCESS` không cờ. Bảng độ nhạy: `scripts/quality-thresholds/sensitivity.py` (design-decisions mục 46).
- Mất gói cả cuộc là bình quân cả cuộc gọi: một đợt mất gói nặng nhưng ngắn trong cuộc gọi dài không bật cờ (đợt đó vẫn
  được nêu ở phần "Đối chiếu"). Đó là đánh đổi của ngưỡng "cả cuộc", chưa có data có nhãn để kiểm.
- `NETWORK_DELAY_JITTER` (cài 2026-10-14): ngưỡng lấy từ Microsoft Teams CQD — RTT hoặc jitter TRUNG BÌNH > 500 ms /
  30 ms trên luồng > 500 gói. Có nguồn công khai nhưng chưa kiểm trên data có nhãn. Cuộc ngắn (≤ 500 gói) không xét.
  Khi cả mất gói và độ trễ cùng vượt, rule xếp `NETWORK_PACKET_LOSS` (MVP mục 10 scenario 2).
- Không có ca `UNKNOWN` nào trong tập có nhãn (nhãn chỉ có `fail/` và `success/`), nên `UNKNOWN` cũng `UNVALIDATED`.
  Rule trả `UNKNOWN` khi không có dữ liệu signaling, không có log client nào, hoặc đạt `OK_ACK_OK` mà không thấy `BYE`
  (MVP mục 4.1). Trường hợp "evidence mâu thuẫn" của MVP mục 4.1 chưa cài.
- Thiếu log của một bên (chỉ một file đính kèm): kết luận vẫn ra `SUCCESS` / `FAIL` với độ tin cậy tối đa MEDIUM và nêu
  file thiếu, **không** ra `UNKNOWN` như scenario 5 của MVP mục 10 — vì ground truth của `5E0800AE` (chỉ có một file) là
  `SUCCESS`. Hai nguồn mâu thuẫn, chưa chốt. Ngoại lệ: server từ chối `INIT_CALL` kèm mã nguyên văn thì không hạ mức.
- **Cuộc gọi rớt giữa chừng** xếp `ICE_FAILURE` vì taxonomy MVP mục 4.2 không có loại riêng. Tín hiệu `network_lost`
  (end call log, `originator=remote`) chỉ dùng để giải thích: nó không chứng minh bên kia mất mạng, vì app báo nó ~7 s
  sau khi hết dữ liệu từ bên kia.
- **Gọi nhóm** (3 người trở lên) không hỗ trợ. Nhiều thiết bị của **một** người nhận thì có: chỉ số lấy từ máy bắt máy,
  máy kia nêu ở Giới hạn dữ liệu.
- `TURN_FAILURE` chia theo kiểu để chọn đề xuất: không tạo được socket (chưa request nào rời máy), lỗi ngay khi gửi trên
  thiết bị, gửi đi mà không có phản hồi nào. Mỗi kiểu có đúng một ca trong `fail/`. Kiểu thứ tư, server có phản hồi
  nhưng không cấp phát, **chưa có ca mẫu**; report tự ghi điều này vào Giới hạn dữ liệu khi gặp.
- Ba cuộc không tạo được socket TURN (`703100CF`, `0A6C2821`, `45AA3011`) đều có giao diện VPN `tun0`, nhưng cả ba
  **cùng một máy** (cùng `deviceId`), tức là chỉ một nguồn bằng chứng. Report nêu VPN như dữ kiện đi kèm, không viết
  thành nguyên nhân.
- Mã 428 `privacy_restricted` (server từ chối theo chính sách) được xếp tạm vào `SIGNALING_FAILURE` vì taxonomy của MVP
  mục 4.2 không có category riêng. Chưa chốt.

**Phần chữ AI viết**

- AI thấy kết luận của rule trong context, và AI lệch rule thì kết luận cuối là `UNKNOWN`. Vì vậy AI không đổi được kết
  luận của rule; "AI trùng rule 100%" trên benchmark chưa chứng minh AI tự suy luận đúng.
- Guardrails chỉ kiểm phần có cấu trúc (verdict, category, evidence ID, con số có nguồn), **không kiểm nghĩa câu chữ**.
  G05 chặn số không có trong input: lượt cuối 0/309 đầu ra thô vi phạm, nhưng hai lượt 2026-10-13 có 4 và 5 lần (AI tự
  trừ hai mốc giờ, đã sửa bằng prompt quy tắc 4b). Gặp vi phạm thì report lùi về rule (degraded). Từ 2026-10-15 G05
  bắt cả số viết liền đơn vị (`250ms`, `45s`); đo lại `dev` ×5: 0/310.
- Từ Sprint 3 T1a, AI nhắc chỉ số bằng mã và code điền tên + giá trị, nên không còn kiểu gọi sai tên con số như
  *"mất gói của caller là 11.3208%"* (`271D1FAF`, Sprint 2: đó là mẫu stats cao nhất). Lỗi diễn giải còn gặp trên
  dataset mới: gắn mã chỉ số vào câu không đúng nghĩa ("không nhận được âm thanh: {PACKET_LOSS_CALLER}"), ghi nhầm bên
  của `network_lost` (`FE4EF5E7`), gọi RTT lúc kết thúc là "RTT trung bình" (`FC3187B4`), phóng đại "mất gói đáng kể" khi
  cả cuộc 0,36% (`06BCA48C`). Dòng Tóm tắt ca FAIL đã chuyển sang code viết (`docs/design-decisions.md` mục 40).
- AI vẫn có lúc tự chép con số trùng giá trị một chỉ số thay vì dùng mã: 30 số ở 21/309 lần trả lời của lượt cuối (cận
  trên — số trùng ngẫu nhiên như "1" cũng tính). Hiện chỉ đo, không chặn.
- Evidence AI trích: mới kiểm có chứa dòng căn cứ của rule (94/115 lần có căn cứ ở lượt cuối 14/10, 96/115 ở lượt đo
  lại 15/10, 98/115 ở lượt chạy lại trước khi nộp 19/10), chưa kiểm evidence trích có **đúng** là căn cứ không. Ca
  SUCCESS không cờ không có dòng căn cứ của rule nên AI tự chọn.
- Câu hỏi giả định sai (ví dụ hỏi vì sao lỗi khi cuộc gọi không lỗi): AI không còn bịa nguyên nhân nhưng chưa luôn phủ
  nhận thẳng giả định (`case-020`, kiểm bằng đọc tay).
- Đề xuất: bộ đề xuất chuẩn của rule luôn giữ, AI chỉ bổ sung tối đa 3 câu ("Bổ sung (AI)"); câu AI chép nguyên văn bộ
  chuẩn bị bỏ, nhưng câu **diễn đạt lại** bộ chuẩn vẫn có thể lọt (không so ngữ nghĩa). Đề xuất cho
  `NETWORK_PACKET_LOSS` / `NETWORK_DELAY_JITTER` chưa kiểm chứng trên ca thật.

**Câu hỏi và phân loại intent**

- Câu hỏi rất cụt, không dấu, chỉ gồm giá trị đã che vẫn có thể bị xếp ngoài phạm vi: *"sdt … email … goi loi"* ra
  `OUT_OF_SCOPE` (1/23 câu của `benchmark/intent-check.yaml`, 2026-10-07). Một lần sai chưa đủ căn cứ để sửa prompt.
  Câu liệt kê có số điện thoại / token / IP viết đủ ý thì phân loại đúng.
- Câu hỏi dài quá 4 000 ký tự (`call-analysis.request.max-question-chars`) bị Chat API từ chối với `QUESTION_TOO_LONG`
  trước lời gọi AI (2026-10-19; câu dài nhất trong benchmark là 139 ký tự). Giới hạn chỉ ở Chat API: Evaluation Runner
  và CLI không chặn, câu cực dài ở đó vẫn tới AI và lùi về rule với `INPUT_TOO_LARGE` khi vượt cửa sổ ngữ cảnh. Phản hồi
  AI bị giới hạn 2 000 token (dài nhất từng đo: 224); vượt thì lùi về rule.
- Câu hỏi bị hỏng mã hoá trước khi tới server (gửi bằng công cụ không dùng UTF-8, ví dụ curl trong Git Bash trên
  Windows) không khôi phục được. Hệ thống phát hiện và báo (ký tự U+FFFD, `?` giữa chữ cái, UTF-8 đọc như Windows-1252):
  report thêm một dòng ở "Giới hạn dữ liệu"; nếu câu bị xếp ngoài phạm vi thì câu từ chối kèm lời nhắc gửi lại. Câu rất
  ngắn chỉ hỏng một chỗ có thể lọt (ngưỡng hai chỗ). Trình duyệt luôn gửi UTF-8 nên web UI không gặp.
- Khi AI phân loại không dùng được, intent do bộ từ khoá dự phòng quyết định và kém AI. Trên 87 câu có nhãn (`dev` +
  `intent-check`) bộ từ khoá đúng 72 (2026-10-12). Còn từ chối 3 câu trong phạm vi: *"Cuộc này ổn không?"*, *"ben nao tat
  may"*, và một câu có địa chỉ email (luật "viết email" thắng). Còn nhận 5 câu ngoài phạm vi có nhắc tới cuộc gọi hoặc log
  (*"Viết caption Facebook về cuộc gọi này"*, *"dich log nay sang tieng anh"*…): các câu này ra report của cuộc gọi đính
  kèm chứ không làm việc ngoài phạm vi. Có AI thì đúng hết.

**Bảo mật**

- Chat API không có đăng nhập. Server chỉ nghe ở `127.0.0.1`, nên chỉ người dùng trên máy chạy server gọi được; mở ra
  mạng LAN (`--server.address=0.0.0.0`) thì ai trong mạng cũng gọi được và tiêu tiền OpenAI.
- Cổng fail-closed (`OutboundGate`, 2026-10-15) quét lại đúng chuỗi sắp gửi AI bằng bộ nhận diện độc lập với Sanitizer,
  nhưng chỉ nhận ra IP, bí mật (JWT, Bearer, API key, `token=…`), SĐT di động Việt Nam và email. Định danh gốc
  (User-ID, Device-ID, session), mật khẩu ICE, fingerprint DTLS, hostname nội bộ mà Sanitizer bỏ sót thì cổng không chặn.
- IP công cộng thật đã được thay khỏi các file hiện tại (2026-10-15), nhưng vẫn còn trong lịch sử git của các commit cũ.
- Signaling nạp vào Elasticsearch local ở dạng gốc, chưa qua Sanitizer (trường nhạy cảm: `appUserId`; thêm `isp`, `asn`;
  không có IP). MVP 6.2 đặt "dữ liệu ghi vào ES local" dưới mục *Output*; dự án hiểu đó là **kết quả phân tích** ghi vào
  ES. Cách hiểu này chưa được xác nhận. Kết quả phân tích ghi vào ES từ Sprint 3 T9 (index `analysis-requests`) chỉ gồm
  mã, số và cờ, `call_id` đã mã giả — không có chữ tự do để lộ; index `call-analysis-results` của T10 cùng nguyên tắc.
  Nếu phải che cả data nạp, cần mã giả hoá `appUserId` lúc import và sửa cách suy ra leg (end call log vẫn mang
  `appUserId` gốc).
- Bí mật dạng `tên=giá trị` / `tên: giá trị` (mục DROP): trong log che **tới hết dòng** (để `Authorization: Basic dXNl…`
  không lộ phần sau khoảng trắng); trong **câu hỏi** người dùng gõ chỉ che một từ, hoặc `Basic` / `Bearer` … + một từ,
  để giữ phần yêu cầu viết tiếp. Cái giá: bí mật có khoảng trắng gõ thẳng vào câu hỏi (`password=my secret pass`) lộ phần
  sau từ đầu tiên.
- Phép đo Security Leakage của runner: gom IPv4, IPv6 unicast toàn cầu, bí mật, SĐT di động Việt Nam, email, định danh —
  trong log đính kèm, signaling và câu hỏi; quét chuỗi gửi AI, phản hồi và application log của từng lần chạy. Log của
  tầng HTTP (chỉ có ở profile `web`) không đi qua runner.

**Vận hành**

- Mỗi request của Chat API nhận tối đa 10 file. Request bị từ chối vì quá số file không có bản ghi trong index
  `analysis-requests` (bị chặn trước khi phân tích), chỉ có một dòng log `TOO_MANY_FILES`.
- Signaling bắt buộc lấy từ Elasticsearch. ES tắt thì `--analyze*` và Web UI vẫn chạy và trả `UNKNOWN` kèm lý do;
  `--parse-*` vẫn parse log đính kèm và báo thiếu signaling.
- File Validator loại file quá lớn (mặc định 20 MB, cấu hình `call-analysis.files.max-size-per-file`), rỗng, hỏng, không
  nhận diện được, hoặc end call log mang Call-ID khác. WebRTC log không mang Call-ID: lượt tải lên chỉ có end call log
  của cuộc khác thì WebRTC log đi kèm cũng bị loại (2026-10-20). Còn lại một ca không kiểm được: **chỉ** đính kèm WebRTC
  log và gõ Call-ID trong câu hỏi — gõ nhầm Call-ID thì kết luận là của cuộc khác. Report ghi cảnh báo ở "Giới hạn dữ
  liệu" nhưng không loại file, vì đây cũng là cách dùng hợp lệ (design-decisions mục 49).
- File log đọc UTF-8; có BOM thì theo BOM (bỏ BOM UTF-8, đọc UTF-16 LE / BE). UTF-16 **không** BOM vẫn bị coi là file
  hỏng — thông báo gợi ý lưu lại dạng UTF-8.
- Độ trễ phụ thuộc OpenAI: lượt 14/10 P95 14,9 s, chậm nhất 47,7 s; lượt chạy lại 19/10 P95 4,04 s, chậm nhất 11,3 s.
  Mỗi lời gọi AI quá giờ sau 30 s, thử lại trong ngân sách 45 s; trình duyệt chờ 150 s. Số đo độ trễ trên máy cá nhân chỉ dùng để so xu hướng.
- `--ai-context` in context với trọng tâm câu hỏi để trống, nên với câu có trọng tâm thì thiếu dòng "Trọng tâm câu hỏi"
  so với thứ thật sự gửi AI.

---

## Dữ liệu

Data mẫu **không nằm trong repo** và không được commit — dù đã xử lý PII, nó vẫn chứa
địa chỉ IP công cộng thật và log production. Đặt thư mục data cạnh repo và trỏ đường dẫn
khi chạy lệnh import.
