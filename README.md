# AI Call Analysis Assistant

Trợ lý phân tích cuộc gọi: nhận câu hỏi tiếng Việt kèm log cuộc gọi, trả về report chuẩn hoá
gồm kết luận (`SUCCESS` / `FAIL` / `UNKNOWN`), evidence trích dẫn được về dòng log gốc,
chỉ số cuộc gọi, đề xuất và giới hạn dữ liệu.

Dự án OJT AI 20K — 6 tuần, 3 sprint. **Đã xong Sprint 2:** Web UI, Chat API, AI (`gpt-4o-mini`) có Guardrails và fallback về rule, Sanitizer đầu vào và đầu ra, Evaluation Runner. Sprint 1 là rule baseline, vẫn là mốc đối chiếu và đường lui khi AI lỗi.

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

# 6. Web UI + Chat API tại http://localhost:8080/
./mvnw spring-boot:run "-Dspring-boot.run.profiles=web"

# 7. Evaluation Runner trên benchmark dev (lần đầu: tạo file biến thể F02, xem dưới)
python scripts/benchmark-variants/make_variants.py ../ai20k_sample
./mvnw spring-boot:run -Dspring-boot.run.arguments="--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample"
```

**File biến thể F02.** MVP 6.3 đòi tập `dev` có ca "tên file sai loại", mà data mẫu không có sẵn. Script trên chép
`caller_endcall.log` của `DE7DD314` thành `_variants/F02-sai-loai/caller_webrtc.log` — nội dung giống hệt, chỉ đặt sai
tên — để kiểm hệ thống nhận loại file theo nội dung (case-016). File nằm trong thư mục data mẫu, ngoài repo, vì data
mẫu chứa IP thật. Chỉ cần chạy một lần; bộ `held-out` của Mentor không cần bước này.

**Key AI.** Chép `.env.example` thành `.env` và điền `OPENAI_API_KEY` (cùng `CALL_ANALYSIS_PSEUDONYM_KEY`, khoá
HMAC cho mã giả). Không có key thì app vẫn chạy: mọi lời gọi AI lùi về rule, report đánh dấu degraded. Muốn chạy
không tốn tiền dù đã có key, thêm `--ai.openai.api-key=` vào tham số.

Kết quả import đúng: **1 059 document / 20 cuộc gọi**. Importer gửi theo lô 500 document.

> Các lệnh `--analyze*`, `--metrics*`, `--timeline`, `--parse-*` lấy signaling từ
> Elasticsearch, nên phải bật ES trước. Nếu quên, `--analyze-all` sẽ ra `UNKNOWN` cho mọi cuộc
> (report ghi rõ *"Không truy vấn được signaling"*); riêng `--parse-*` dừng với lỗi. `--evaluate` kiểm
> signaling trước khi chạy và cảnh báo ngay ở đầu báo cáo. Web UI và `--evaluate` cũng lấy signaling từ ES.

---

## Lệnh

### Build và test

```bash
./mvnw clean compile
./mvnw test          # unit test không cần Elasticsearch; 3 test tích hợp ES chạy qua
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
- Giá trị enum phải viết hoa đúng tên: `FAIL`, `ICE_FAILURE`, `ANALYZE_CALL`…

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
rồi chạy lệnh trên **bỏ** `--ai.openai.api-key=`. Chi phí đo được ≈ 0,0007 USD và ≈ 4 s cho mỗi lần chạy một câu hỏi.
Ví dụ 20 case × 4 câu × 5 lần = 400 lần chạy ≈ 0,3 USD, khoảng 25-30 phút.

`--repeat=5` theo cách đo Consistency của MVP 6.5 (5 lần × các cách hỏi). Thêm `--out=<thư-mục>` để đổi nơi ghi kết quả.

#### Bước 5 — Đọc kết quả

Mặc định ở `target/evaluation/`:

| File | Nội dung |
|---|---|
| `evaluation-<thời điểm>.md` | Bảng metric MVP 6.5 (giá trị, số lần, target, cách đo), bảng AI vs Rule theo case, vi phạm Guardrails, case không chạy được |
| `evaluation-<thời điểm>.json` | Từng lần chạy: câu hỏi đã làm sạch, intent, kết luận, nguồn kết luận, lý do fallback, số token, latency — để soát lại |

Báo cáo **không chứa** log hay giá trị nhạy cảm gốc; Security Leakage chỉ lưu số đếm.

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
├── cli/               ADAPTER VÀO — 11 lệnh dòng lệnh
├── controller/        ADAPTER VÀO — Chat API cho Web UI, chỉ chạy ở profile web (S2-T2)
├── service/           ĐIỀU PHỐI
│   ├── CallLogNormalizationService, AnalyzeCallService      pipeline Sprint 1
│   ├── ChatAnalysisService, RequestParserService, AiVerdictService   luồng Chat API (S2)
│   ├── EvaluationService                                    Evaluation Runner (S2-T9)
│   └── port/SignalingSource, AiAnalyzer, IntentClassifier   cổng ra
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
│   ├── ai/                Context gửi AI, đầu ra AI (S2-T5)
│   ├── guardrail/         Guardrails G01-G05 (S2-T6)
│   ├── security/          Danh mục + Sanitizer dữ liệu nhạy cảm (S1-T9, S2-T7)
│   ├── report/            Model report, builder, renderer mẫu 4.5 (S1-T8, S2-T2)
│   └── evaluation/        Chấm benchmark theo metric mục 6.5 (S2-T9)
└── infrastructure/    ADAPTER RA
    ├── es/                Elasticsearch
    ├── ai/                OpenAI (Structured Outputs)
    ├── file/              Đọc file log từ đĩa
    ├── evaluation/        Đọc file case YAML, tìm file log của case
    ├── taxonomy/          Nạp taxonomy.yaml
    ├── security/          Nạp danh mục, dựng Sanitizer
    ├── logging/           Lọc log qua Sanitizer
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
| `resources/es/signaling-mapping.json` | Mapping Elasticsearch, `dynamic: strict` |
| `benchmark/dev-cases.yaml` · `intent-check.yaml` | Bộ case benchmark `dev` (18 case, 63 câu) và bộ câu kiểm phân loại |
| `scripts/benchmark-variants/make_variants.py` | Tạo file biến thể "tên file sai loại" cho case-016 (ca F02), đặt ngoài repo |

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

### Kết quả đo được

| | |
|---|---|
| Verdict Accuracy | **13/13** trên toàn bộ data có nhãn (6 fail + 7 success) |
| Parse | 29 238 dòng → 28 522 event, **1 cảnh báo** (bản export signaling của `DE7DD314` bị cắt 200/201), 0 file không nhận diện được |
| Chỉ số khớp giá trị tính tay | **9 cuộc gọi** (yêu cầu tối thiểu 5); 280/280 giá trị trên 20 cuộc khớp script độc lập `scripts/verify-metrics/run.sh` |
| Report hợp lệ theo schema v1 | **20/20** |
| Test | **388** (2026-10-06): 385 unit test (không cần Elasticsearch hay AI thật) + 3 test tích hợp ES thật qua Testcontainers (import lặp 2 lần không sinh bản trùng, chia lô bulk) |

## Trạng thái — Sprint 2: 10/11 task xong

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
| T11 — So sánh cách dựng context (Nếu kịp) | ⬜ | Chưa làm — khái niệm "semi-structured context" chưa chốt |

Đối chiếu từng task với code, test và số đo: [`docs/minh-chung-sprint-2.md`](docs/minh-chung-sprint-2.md).

### Kết quả đo được — Sprint 2

Benchmark `dev` (`benchmark/dev-cases.yaml`: 18 case, 63 câu hỏi), mỗi câu chạy 5 lần = 315 lần chạy, `gpt-4o-mini`,
2026-10-06. Cách tính từng metric: [`docs/evaluation-metrics.md`](docs/evaluation-metrics.md).

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

### Known Limitations

**Dữ liệu và chỉ số**

- Chỉ số chất lượng (MOS, packet loss, jitter, RTT) chỉ có giá trị thật ở **8 leg / 5 cuộc
  gọi**. 6 cuộc gọi không có end call log (`5E0800AE`, `6A7CE985`, `F3D7914B`, `7B56D7AD`,
  `E9D6C112`, `AA9791CE`) nên các chỉ số này là `N/A`. Leg chưa từng nhận gói audio nào thì app
  ghi 0 vào MOS/loss/RTT; report hiển thị `N/A` kèm lý do thay vì in số 0 đó (MVP mục 4.3).
- MOS, RTT, jitter trong bảng là giá trị **lúc kết thúc** cuộc gọi: bản ghi tổng kết của app
  trùng đúng mẫu stats cuối (8/8 leg có media), và log không có bộ đếm để tính lại cho cả cuộc.
  Report ghi rõ điều này ở mục Giới hạn dữ liệu. Packet loss thì tính được cho **cả cuộc** từ
  hai bộ đếm cộng dồn `audio.packetsLost` / `audio.packetsReceived`, kèm mẫu cao nhất;
  `audio.packetLostPercent` chỉ là tỉ lệ trong khoảng giữa hai lần đo nên không dùng cho bảng.
- "Thời gian với tới callee" (`INVITE` → `TRYING`) chỉ đo được khi callee được đánh thức qua
  push. Callee đang online nhận `INVITE` qua WebSocket thì không gửi `TRYING`, nên chỉ số này
  là `N/A`.
- "Khoảng trống PAIR_PING lớn nhất" (chỉ số proxy) chỉ đo giữa hai lần ping liên tiếp, không
  thấy khoảng im lặng từ lần ping cuối tới lúc kết thúc.
- WebRTC log chỉ có mốc thời gian tương đối nên chưa đồng bộ được với timeline signaling.
  Trong mục Evidence, các dòng WebRTC (ICE) luôn xếp sau các dòng có giờ tuyệt đối, dù có thể
  xảy ra trước.

**Rule và taxonomy**

- Không có cuộc gọi nào có nhãn bị suy giảm chất lượng, nên `NETWORK_PACKET_LOSS` và
  `NETWORK_DELAY_JITTER` còn `UNVALIDATED`. Ngưỡng 5% / MOS 3,5 là phỏng đoán, đặt cao hơn mức
  đo được ở các cuộc thành công (844 mẫu stats của 6 leg: loss cao nhất 3,704%, MOS thấp nhất
  4,335).
- Rule gắn cờ chất lượng khi **một** mẫu stats vượt ngưỡng, chưa yêu cầu vượt kéo dài nhiều mẫu
  liên tiếp: không có dữ liệu để chọn số mẫu (không leg thành công nào có loss > 5 %; chuỗi dài
  nhất toàn data là 2 mẫu, ở `271D1FAF` thuộc `for_test/`). Vì vậy cờ còn nhạy với mất gói
  thoáng qua; report bù lại bằng cách nêu cả căn cứ phản bác (MOS thấp nhất, `hasMediaPoor`).
- `NETWORK_DELAY_JITTER` chưa có nhánh riêng trong rule: mọi suy giảm đều được xếp
  `NETWORK_PACKET_LOSS`. Điều kiện RTT / jitter trong `taxonomy.yaml` là thiết kế dự kiến, đã
  đánh dấu "chưa cài".
- Không có ca `UNKNOWN` nào trong tập có nhãn (nhãn chỉ có `fail/` và `success/`), nên
  `UNKNOWN` cũng `UNVALIDATED`. Rule trả `UNKNOWN` khi không có dữ liệu signaling, không có log client
  nào, hoặc không thấy `BYE` (chi tiết ở mục dưới). Trường hợp "evidence mâu thuẫn" của MVP mục 4.1 chưa cài.
- `TURN_FAILURE` chia theo kiểu để chọn đề xuất: không tạo được socket (chưa request nào rời
  máy), lỗi ngay khi gửi trên thiết bị, gửi đi mà không có phản hồi nào. Mỗi kiểu có đúng một
  ca trong `fail/`. Kiểu thứ tư, server có phản hồi nhưng không cấp phát, **chưa có ca mẫu**;
  report tự ghi điều này vào Giới hạn dữ liệu khi gặp.
- Ba cuộc không tạo được socket TURN (`703100CF`, `0A6C2821`, `45AA3011`) đều có giao diện VPN
  `tun0`, nhưng cả ba **cùng một máy** (cùng `deviceId`), tức là chỉ một nguồn bằng chứng. Report
  nêu VPN như dữ kiện đi kèm, không viết thành nguyên nhân.
- Mã 428 `privacy_restricted` (server từ chối theo chính sách) được xếp tạm vào
  `SIGNALING_FAILURE` vì taxonomy của MVP mục 4.2 không có category riêng. Chưa chốt.
- Cuộc gọi có signaling trông bình thường nhưng không kèm log phía client nào, hoặc đạt
  `OK_ACK_OK` mà không thấy `BYE`: trả `UNKNOWN` (theo góp ý review Sprint 1, MVP mục 4.1). Thiếu log
  client của một leg: độ tin cậy tối đa `MEDIUM` (MVP mục 7.2), trừ ca server từ chối `INIT_CALL`
  kèm mã nguyên văn.

**AI (Sprint 2)**

- AI thấy kết luận của rule trong context, và AI lệch rule thì kết luận cuối là `UNKNOWN`. Vì vậy AI không đổi
  được kết luận của rule; "AI trùng rule 100%" trên benchmark chưa chứng minh AI tự suy luận đúng.
- Con số trong chữ AI viết: Guardrails G05 chặn số không có trong input (report cuối 0% số bịa), nhưng đầu ra thô của
  AI vẫn có 0-3% lần viết số sai hoặc tự tính; lần đó report lùi về rule (degraded).
- G05 chỉ kiểm con số **có nguồn** trong input, không kiểm con số được **gán đúng nghĩa**. AI có thể lấy một số có thật
  rồi gọi sai tên: demo 2026-10-07 (`271D1FAF`) viết *"mất gói của caller là 11.3208%"* trong khi đó là mẫu stats cao nhất,
  mất gói cả cuộc là 1.83 %; lần chạy trước gọi MOS lúc kết thúc là "MOS thấp nhất". Kết luận và bảng chỉ số không bị ảnh
  hưởng (do code tính), chỉ câu chữ phần Phân tích. Sửa ở Sprint 3 T1(a): AI trích mã chỉ số, code điền tên và giá trị.
- Phần đề xuất của AI thay hẳn đề xuất của rule. Trên `for_test/`, rule cho đề xuất tốt hơn ở 4/7 cuộc
  (chạy để quan sát, không dùng để chỉnh). Demo cũng thấy lặp lại: với `703100CF` (chưa request nào rời máy), AI vẫn khuyên "kiểm tra
  cấu hình TURN server". Dự kiến sửa ở Sprint 3 T2(a): giữ đề xuất của rule, AI chỉ bổ sung.
- Câu hỏi rất cụt, không dấu, chỉ gồm giá trị đã che vẫn có thể bị xếp ngoài phạm vi: *"sdt … email … goi loi"* ra
  `OUT_OF_SCOPE` (1/23 câu của `benchmark/intent-check.yaml`, 2026-10-07). Một lần sai chưa đủ căn cứ để sửa prompt; theo
  dõi ở các lượt đo sau. Câu liệt kê có số điện thoại / token / IP viết đủ ý thì phân loại đúng.
- Câu hỏi bị hỏng mã hoá trước khi tới server (gửi bằng công cụ không dùng UTF-8, ví dụ curl trong Git Bash trên Windows)
  không được phát hiện: AI phân loại trên chữ hỏng và có thể trả `OUT_OF_SCOPE`. Trình duyệt luôn gửi UTF-8 nên web UI không
  gặp (`docs/ket-qua-demo-sprint-2.md` mục 3). Dự kiến Sprint 3 T4: báo lỗi khi câu hỏi chứa ký tự lỗi mã hoá.
- Thiếu log của một bên (chỉ một file đính kèm): kết luận vẫn ra `SUCCESS` / `FAIL` với độ tin cậy tối đa MEDIUM và nêu
  file thiếu, **không** ra `UNKNOWN` như scenario 5 của MVP mục 10 — vì ground truth của `5E0800AE` (chỉ có một file) là
  `SUCCESS`. Hai nguồn mâu thuẫn, chưa chốt.
- `--ai-context` in context với trọng tâm câu hỏi để trống, nên với câu có trọng tâm thì thiếu dòng "Trọng tâm câu hỏi" so
  với thứ thật sự gửi AI.
- Ground truth chỉ có fail/success, chưa có issue category, nên Issue Category Accuracy chưa đo được. Không có
  cuộc gọi chất lượng kém có nhãn, nên benchmark `dev` thiếu loại ca "SUCCESS chất lượng kém".
- T11 (Nếu kịp) chưa làm: khái niệm "semi-structured context" chưa chốt.

**Bảo mật (Sprint 2)**

- Signaling nạp vào Elasticsearch local ở dạng gốc, chưa qua Sanitizer (trường nhạy cảm: `appUserId`; thêm `isp`, `asn`;
  không có IP). MVP 6.2 đặt "dữ liệu ghi vào ES local" dưới mục *Output*; dự án hiểu đó là **kết quả phân tích** ghi vào ES —
  hiện hệ thống chưa ghi kết quả nào vào ES. Cách hiểu này chưa được xác nhận. Nếu phải che cả data nhập, cần mã giả hoá
  `appUserId` lúc import và sửa cách suy ra leg (end call log vẫn mang `appUserId` gốc). Từ Sprint 3, mọi kết quả ghi vào
  ES (T9-T11) phải qua Sanitizer.
- Phép đo Security Leakage của runner chỉ xét chuỗi gửi AI và phản hồi, chưa xét application log; và chưa gom IPv6 cùng
  giá trị nằm trong câu hỏi (`docs/evaluation-metrics.md`). Log được làm sạch bằng converter Logback và có test riêng (S08),
  nhưng không nằm trong số đo của runner.

**Vận hành**

- Signaling bắt buộc lấy từ Elasticsearch. ES tắt thì `--analyze` vẫn chạy và trả `UNKNOWN`
  kèm lý do, còn `--parse-call` / `--parse-all` dừng với lỗi vì chưa bọc lỗi ES.
- File Validator (Sprint 2 T3) loại file quá lớn (mặc định 20 MB, cấu hình
  `call-analysis.files.max-size-per-file`), rỗng, hỏng, không nhận diện được, hoặc end call log mang
  Call-ID khác. WebRTC log không mang Call-ID nên không kiểm được file WebRTC của cuộc gọi khác.

---

## Dữ liệu

Data mẫu **không nằm trong repo** và không được commit — dù đã xử lý PII, nó vẫn chứa
địa chỉ IP công cộng thật và log production. Đặt thư mục data cạnh repo và trỏ đường dẫn
khi chạy lệnh import.
