# AI Call Analysis Assistant

Trợ lý phân tích cuộc gọi: nhận câu hỏi tiếng Việt kèm log cuộc gọi, trả về report chuẩn hoá
gồm kết luận (`SUCCESS` / `FAIL` / `UNKNOWN`), evidence trích dẫn được về dòng log gốc,
chỉ số cuộc gọi, đề xuất và giới hạn dữ liệu.

Dự án OJT AI 20K — 6 tuần, 3 sprint. **Sprint 1 hiện tại: rule baseline, chưa có AI, chưa có Web UI.**

---

## Yêu cầu môi trường

| | Phiên bản | Ghi chú |
|---|---|---|
| JDK | **21** | Xem cảnh báo bên dưới |
| Maven | không cần cài | Dùng `./mvnw` kèm sẵn trong repo (Windows: `.\build.ps1`) |
| Docker | bất kỳ bản hỗ trợ Compose v2 | Cho Elasticsearch + Kibana local |
| RAM trống | ~4 GB | Elasticsearch cấu hình 2 GB heap |

> ### ⚠️ Bắt buộc dùng JDK 21
>
> Spring Boot 3.5 **không hỗ trợ JDK 24 trở lên**. Nếu máy để JDK mới hơn làm mặc định,
> build sẽ lỗi với thông báo khó hiểu. Kiểm tra bằng `java -version`, nếu không phải 21 thì set:
>
> ```bash
> export JAVA_HOME=/duong/dan/toi/jdk-21        # Linux / macOS / Git Bash
> ```
> ```powershell
> $env:JAVA_HOME = "C:\Program Files\Java\jdk-21"   # PowerShell
> ```
>
> Trên Windows có thể dùng `.\build.ps1` thay cho `mvnw`: script tự tìm JDK 21 và chỉ đặt
> `JAVA_HOME` trong phiên chạy đó, không đổi biến môi trường của máy. Ví dụ
> `.\build.ps1 test`, `.\build.ps1 -q spring-boot:run "-Dspring-boot.run.arguments=--analyze-all=../ai20k_sample"`.

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
```

Kết quả import đúng: **1 059 document / 20 cuộc gọi**.

> Các lệnh `--analyze*`, `--metrics*`, `--timeline`, `--parse-*` lấy signaling từ
> Elasticsearch, nên phải bật ES trước. Nếu quên, `--analyze-all` sẽ ra `UNKNOWN` cho mọi cuộc
> (report ghi rõ *"Không truy vấn được signaling"*); riêng `--parse-*` dừng với lỗi.

---

## Lệnh

### Build và test

```bash
./mvnw clean compile
./mvnw test          # không cần Elasticsearch — toàn bộ test chạy trên lớp thuần
```

### Elasticsearch + Kibana

```bash
docker compose up -d elasticsearch    # chỉ Elasticsearch (nhẹ nhất)
docker compose up -d                  # thêm Kibana tại http://localhost:5601
docker compose down                   # dừng, GIỮ dữ liệu
docker compose down -v                # dừng và XOÁ dữ liệu
```

Máy dưới 16 GB RAM: sửa `ES_JAVA_OPTS` trong `docker-compose.yml` xuống `-Xms1g -Xmx1g`.

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

Kịch bản demo cho mentor: [`docs/demo-sprint-1.md`](docs/demo-sprint-1.md).
Kết quả đã chạy: [`docs/ket-qua-demo-sprint-1.md`](docs/ket-qua-demo-sprint-1.md) (5 cuộc minh hoạ)
và [`docs/ket-qua-for-test.md`](docs/ket-qua-for-test.md) (7 cuộc của tập `for_test/`).

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
├── cli/               ADAPTER VÀO — 6 lệnh dòng lệnh
├── service/           ĐIỀU PHỐI
│   ├── CallLogNormalizationService, AnalyzeCallService
│   └── port/SignalingSource          cổng ra
├── domain/            LÕI THUẦN — không Spring, không I/O
│   ├── event/             Canonical Event Model (T2)
│   ├── parse/             2 parser (end call log, WebRTC log) + FileTypeDetector (T3)
│   ├── signaling/         Chuẩn hoá signaling từ ES + suy ra leg (T3)
│   ├── timeline/          Correlate, dedupe, lệch đồng hồ (T4)
│   ├── taxonomy/          6 issue category (T5)
│   ├── metrics/           Bộ chỉ số Core và "Nếu kịp" (T6, T11)
│   ├── evidence/          Chọn và đánh ID evidence (T7)
│   ├── rule/              Tín hiệu thô + rule verdict (T7)
│   ├── report/            Model report + builder (T8)
│   └── security/          Phân loại dữ liệu nhạy cảm (T9)
└── infrastructure/    ADAPTER RA
    ├── es/                Elasticsearch
    ├── file/              Đọc file log từ đĩa
    ├── taxonomy/          Nạp taxonomy.yaml
    ├── security/          Nạp sensitive-data-inventory.yaml
    └── report/            Kiểm tra report theo JSON Schema
```

Cấu hình để ở dạng **dữ liệu**, không hard-code trong Java — vì Sprint 2 sẽ nạp thẳng
chúng vào prompt và Guardrails:

| File | Dùng cho |
|---|---|
| `resources/taxonomy.yaml` | 6 issue category, kèm trạng thái đã kiểm chứng hay chưa |
| `resources/sensitive-data-inventory.yaml` | Phân loại dữ liệu nhạy cảm (T9 → Sanitizer ở Sprint 2) |
| `resources/schema/report-v1.schema.json` | Cấu trúc report (T8 → Guardrails ở Sprint 2) |
| `resources/es/signaling-mapping.json` | Mapping Elasticsearch, `dynamic: strict` |

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
| T10 — AI Provider Proposal | ✅ | [`docs/ai-provider-proposal.md`](docs/ai-provider-proposal.md) — **chờ mentor duyệt** |
| T11 — Chỉ số mở rộng (Nếu kịp) | ✅ | `domain/metrics/`: latency API nội bộ, số WARN / ERROR theo service, ISP / ASN, khoảng trống PAIR_PING |

### Kết quả đo được

| | |
|---|---|
| Verdict Accuracy | **13/13** trên toàn bộ data có nhãn (6 fail + 7 success) |
| Parse | 29 238 dòng → 28 522 event, **1 cảnh báo** (bản export signaling của `DE7DD314` bị cắt 200/201), 0 file không nhận diện được |
| Chỉ số khớp giá trị tính tay | **9 cuộc gọi** (yêu cầu tối thiểu 5) |
| Report hợp lệ theo schema v1 | **20/20** |
| Unit test | **162**, chạy ~0,4 giây, **không cần Elasticsearch** |

### Known Limitations

**Dữ liệu và chỉ số**

- Chỉ số chất lượng (MOS, packet loss, jitter, RTT) chỉ có giá trị thật ở **7 leg / 5 cuộc
  gọi**. 6 cuộc gọi không có end call log (`5E0800AE`, `6A7CE985`, `F3D7914B`, `7B56D7AD`,
  `E9D6C112`, `AA9791CE`) nên các chỉ số này là `N/A`. Leg chưa từng nhận gói audio nào thì app
  ghi 0 vào MOS/loss/RTT; report hiển thị `N/A` kèm lý do thay vì in số 0 đó (MVP mục 4.3).
- Chỉ số chất lượng trong bảng là giá trị **lúc kết thúc** cuộc gọi. Suy giảm giữa cuộc chỉ
  hiện qua cờ chất lượng và evidence (mẫu stats nặng nhất), không hiện trong bảng chỉ số.
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
- Rule gắn cờ chất lượng khi **một** mẫu stats vượt ngưỡng, trong khi `taxonomy.yaml` mô tả
  điều kiện là "kéo dài trên nhiều mẫu liên tiếp". Chờ mentor chọn (ví dụ qua `271D1FAF`).
- `NETWORK_DELAY_JITTER` chưa có nhánh riêng trong rule: mọi suy giảm đều được xếp
  `NETWORK_PACKET_LOSS`, dù taxonomy đã khai báo điều kiện RTT.
- Mã 428 `privacy_restricted` (server từ chối theo chính sách) được xếp tạm vào
  `SIGNALING_FAILURE` vì taxonomy của MVP mục 4.2 không có category riêng. Chờ mentor.
- Cuộc gọi có signaling trông bình thường nhưng không kèm log phía client nào: hệ thống trả
  `SUCCESS` / `LOW` kèm ghi chú thiếu file, chứ không trả `UNKNOWN`. Signaling đủ để bắt lỗi thiết lập nhưng không bao giờ thấy lỗi
  media. Chờ mentor quyết (scenario 5 của MVP mục 10).

**Vận hành**

- Signaling bắt buộc lấy từ Elasticsearch. ES tắt thì `--analyze` vẫn chạy và trả `UNKNOWN`
  kèm lý do, còn `--parse-call` / `--parse-all` dừng với lỗi vì chưa bọc lỗi ES.
- Chưa giới hạn kích thước file đính kèm (ca kiểm thử F04). Thuộc File Validator ở Sprint 2.

---

## Dữ liệu

Data mẫu **không nằm trong repo** và không được commit — dù đã xử lý PII, nó vẫn chứa
địa chỉ IP công cộng thật và log production. Đặt thư mục data cạnh repo và trỏ đường dẫn
khi chạy lệnh import.
