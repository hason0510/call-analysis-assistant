# Architecture v1 — Sprint 1

> Kiến trúc sau Sprint 1. Toàn bộ pipeline chạy bằng rule, chưa có AI và chưa có Web UI —
> đúng phạm vi MVP mục 1.5.

---

## 1. Hệ thống làm gì

Nhận Call-ID cùng các file log người dùng đính kèm, trả về report chuẩn hoá gồm kết luận,
evidence trích dẫn được về dòng log gốc, chỉ số cuộc gọi, đề xuất và giới hạn dữ liệu.

Nguyên tắc chi phối toàn bộ thiết kế (MVP mục 3.2):

> **Phần nào tính được bằng code thì không giao cho AI.**

Sprint 1 chứng minh nguyên tắc này khả thi: chỉ bằng rule đã đạt **Verdict Accuracy 13/13**
trên toàn bộ data có nhãn.

---

## 2. Luồng xử lý

### 2.1. Sprint 1 — hiện tại

```text
     Call-ID  +  file log đính kèm
         │              │
         │              ▼
         │      ┌───────────────────┐
         │      │ FileTypeDetector  │  nhận diện theo NỘI DUNG,
         │      │                   │  không theo tên file
         │      └─────────┬─────────┘
         │                │
         │        ┌───────┴────────┬──────────────────┐
         │        ▼                ▼                  ▼
         │  ┌───────────┐   ┌─────────────┐   ┌──────────────┐
         │  │ EndCall   │   │ WebRtc      │   │ (bỏ qua      │
         │  │ LogParser │   │ LogParser   │   │  UNKNOWN)    │
         │  └─────┬─────┘   └──────┬──────┘   └──────────────┘
         │        │                │
         ▼        │                │
┌─────────────────┐│                │
│ Elasticsearch   ││                │
│ local           ││                │
│ signaling-events││                │
└────────┬────────┘│                │
         ▼         │                │
┌──────────────────┐│               │
│ Signaling        ││               │
│ Normalizer       ││               │
└────────┬─────────┘│               │
         │          │               │
         └──────────┴───────────────┘
                    │
                    ▼
          ┌───────────────────┐
          │  CanonicalEvent   │   một model chung cho cả ba nguồn
          └─────────┬─────────┘
                    ▼
          ┌───────────────────┐
          │  TimelineBuilder  │   correlate · dedupe · sort · đo lệch đồng hồ
          └─────────┬─────────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
┌──────────────┐ ┌────────┐ ┌──────────────┐
│ Metrics      │ │ Signal │ │ Evidence     │
│ Calculator   │ │Extract │ │ Engine       │
└──────┬───────┘ └───┬────┘ └──────┬───────┘
       │             ▼             │
       │     ┌───────────────┐     │
       │     │ RuleVerdict   │◄────┼──── taxonomy.yaml
       │     │ Engine        │     │
       │     └───────┬───────┘     │
       └─────────────┼─────────────┘
                     ▼
            ┌─────────────────┐
            │  ReportBuilder  │
            └────────┬────────┘
                     ▼
            ┌─────────────────────┐
            │ ReportSchema        │  chặn report sai cấu trúc
            │ Validator           │
            └────────┬────────────┘
                     ▼
                  Report
```

### 2.2. Sprint 2 sẽ chèn vào đâu

Bố cục trên **không đổi**. Ba khối mới nằm gọn giữa Evidence Engine và ReportBuilder,
cộng hai adapter ở hai đầu:

```text
  Web UI  ──►  Chat API  ──►  Request Parser ──┐
  (mới)        (mới)          (mới, dùng AI)   │
                                               ▼
        ... Evidence Engine ──►  Input Sanitizer  ──►  AI Analysis
                                      (mới)             (mới)
                                                          │
                     RuleVerdictEngine ──►  Guardrails ◄──┘
                     (đã có, làm mốc           (mới)
                      đối chiếu và fallback)      │
                                                  ▼
                                          Output Sanitizer  ──►  ReportBuilder
                                               (mới)              (đã có)
```

Hai thứ Sprint 1 đã chuẩn bị sẵn cho Sprint 2:

- **`RuleVerdict`** làm mốc đối chiếu với verdict của AI, và làm đường lui khi AI lỗi.
- **`ReportSchemaValidator`** hiện dùng để tự kiểm; Sprint 2 dùng chính nó làm lớp
  Guardrails cho đầu ra của AI. Schema đã đặt `additionalProperties: false` nên AI
  không thể tự thêm trường.

---

## 3. Phân tầng

Theo `Controller → Service → Domain → Infrastructure` (checklist mentor), triển khai
kiểu Hexagonal (Ports & Adapters).

```text
io.hason.callanalysis
│
├── cli/                    ADAPTER VÀO   (Sprint 2 thêm api/ cho web)
│   ├── ImportSignalingCommand    --import-signaling
│   ├── FetchCallCommand          --fetch-call
│   ├── ParseCallCommand          --parse-call / --parse-all
│   ├── TimelineCommand           --timeline
│   ├── MetricsCommand            --metrics / --metrics-all
│   └── AnalyzeCommand            --analyze / --analyze-all
│
├── service/                ĐIỀU PHỐI
│   ├── CallLogNormalizationService
│   ├── AnalyzeCallService
│   └── port/
│       └── SignalingSource       ← cổng ra (Sprint 2 thêm AiAnalyzer)
│
├── domain/                 LÕI THUẦN — không Spring, không I/O
│   ├── event/       CanonicalEvent, EventTime, SourceRef, Leg, EventType…
│   ├── parse/       FileTypeDetector, EndCallLogParser, WebRtcLogParser
│   ├── signaling/   RawSignalingRecord, SignalingNormalizer, LegAssignment
│   ├── timeline/    TimelineBuilder, LegCorrelator, ClockOffsetEstimator
│   ├── metrics/     MetricsCalculator, MetricValue
│   ├── taxonomy/    IssueCategory, Verdict, IssueDefinition
│   ├── rule/        SignalExtractor, RuleVerdictEngine
│   ├── evidence/    EvidenceEngine
│   ├── report/      CallReport, ReportBuilder
│   └── security/    DataClassification, HandlingPolicy, SensitiveField
│
└── infrastructure/         ADAPTER RA
    ├── es/          ElasticsearchSignalingSource, SignalingImporter, SignalingIndex
    ├── file/        CallFolderReader
    ├── taxonomy/    TaxonomyLoader
    ├── security/    SensitiveDataInventoryLoader
    └── report/      ReportSchemaValidator
```

### Luật kiến trúc duy nhất

> `domain` **không** import `infrastructure`, **không** có annotation Spring,
> **không** đọc file, **không** gọi mạng.

Đây không phải quy ước cho đẹp — nó là điều kiện để đạt các tiêu chí chấm:

| Luật cho gì | Tiêu chí MVP |
|---|---|
| Parser nhận `List<String>` chứ không nhận `File` → test bằng chuỗi viết thẳng | "Unit test đầy đủ cho core logic" (5.1) |
| Pipeline không I/O → cùng input luôn ra cùng output | **Consistency ≥ 95%** (6.5) |
| `MetricsCalculator` test được không cần ES | "Chỉ số khớp 100% với tính tay" (5.1) |
| Đổi AI provider chỉ cần viết adapter mới | "AI Provider Abstraction" (6.6) |

Kết quả cụ thể: **162 test chạy trong ~0,4 giây, không cần Docker.**

### Adapter chỉ làm việc cơ học

`ElasticsearchSignalingSource` map document sang `RawSignalingRecord` rồi dừng.
Việc parse timestamp, suy ra leg, gán `EventType` thuộc `SignalingNormalizer` ở tầng domain.

Nếu adapter trả thẳng `CanonicalEvent`, toàn bộ logic chuẩn hoá sẽ bị kẹt trong lớp cần
Elasticsearch thật mới test được.

---

## 4. Ba nguồn dữ liệu

| Nguồn | Vào hệ thống bằng | Định dạng thời gian | Đặc thù |
|---|---|---|---|
| `signaling.json` | **Elasticsearch local**, truy vấn theo Call-ID | ISO-8601 UTC, 9 chữ số nano | Không có trường `leg`, không có trường text |
| `*_endcall.log` | Người dùng đính kèm | epoch millis (đồng hồ client) | TSV **tự mang schema** ở 9 dòng `#H` đầu; số header thay đổi theo file |
| `*_webrtc.log` | Người dùng đính kèm | `[giây:mili]` **tương đối** | 2 format (iOS/Android); 6,2% số dòng (1 649) là dòng nối tiếp |

### Ba hệ thời gian không cùng gốc

WebRTC log **không có giờ tuyệt đối**. Vì vậy `CanonicalEvent.time` là sealed interface
`Absolute | Relative`, và timeline tách làm hai phần:

```text
CallTimeline
├── mainTrack       signaling + endcall — giờ tuyệt đối, đã sắp xếp
└── relativeTracks  mỗi file WebRTC một track — giờ tương đối
```

Trộn hai phần vào nhau sẽ tạo thứ tự giả. Việc không ghép được đi thẳng vào mục
"Giới hạn dữ liệu" của report.

**Lệch đồng hồ client/server** được đo bằng trung vị hiệu số giữa `send_cmd` của client và
sự kiện server cùng lệnh — nhưng chỉ **báo cáo, không viết lại timestamp**, vì giá trị đo
được là tổng của độ trễ mạng và lệch đồng hồ thật, không tách được từ log một chiều.

---

## 5. Lưu trữ

### Index `signaling-events`

```json
{ "mappings": { "dynamic": "strict", "properties": { … 16 trường … } } }
```

Ba lựa chọn đáng chú ý:

- **`dynamic: strict`** — ES báo lỗi ngay nếu tập `held-out` có trường lạ, thay vì lặng lẽ
  tạo hàng trăm trường rác.
- **`strict_date_optional_time_nanos`** — giữ đủ 9 chữ số thập phân. Format `date` mặc
  định sẽ làm mất độ chính xác.
- **`_id` tất định** `callId:ordinal` — import lại không sinh bản trùng, đúng yêu cầu
  "script import lặp lại được".

`callId` nằm ở cấp ngoài file JSON nên được chèn vào từng document lúc index. Metadata
`truncated` / `returned` / `total_matching` cũng đi kèm, vì cuộc gọi `DE7DD314` có
`truncated: true` và điều đó phải được nêu ở "Giới hạn dữ liệu".

### Cấu hình dạng dữ liệu, không hard-code

| File | Dùng cho | Vì sao không để trong Java |
|---|---|---|
| `taxonomy.yaml` | 6 issue category | Mentor review được mà không đọc code; Sprint 2 nạp thẳng vào prompt; đổi ngưỡng không cần build lại |
| `sensitive-data-inventory.yaml` | Phân loại dữ liệu nhạy cảm | Sprint 2 dùng làm Policy Engine cho Sanitizer |
| `report-v1.schema.json` | Cấu trúc report | Sprint 2 dùng làm Guardrails cho đầu ra AI |

---

## 6. Phân vai code và AI

| Thành phần | Sprint 1 | Sprint 2 |
|---|---|---|
| Chỉ số cuộc gọi | **Code** | Code — không đổi |
| Tín hiệu verdict | **Code** (`SignalExtractor`) | Code — không đổi |
| Verdict cuối cùng | **Code** (`RuleVerdictEngine`) | AI đề xuất, Guardrails đối chiếu với rule |
| Hiểu câu hỏi tiếng Việt | — | AI |
| Phân tích, đề xuất | Bộ đề xuất cố định theo category | AI |
| Bố cục report | **Code** (`ReportBuilder`) | Code — không đổi |
| Độ tin cậy | **Code**, logic tất định | Code — không để AI sinh số |

---

## 7. Kết quả đo được

| | |
|---|---|
| Parse | 29 238 dòng → 28 522 event, **1 cảnh báo**, 0 file không nhận diện |
| Verdict Accuracy | **13/13 = 100%** trên data có nhãn |
| Chỉ số | **Khớp 100%** với tính tay độc lập trên 9 cuộc gọi |
| Report hợp lệ theo schema | **20/20** |
| Unit test | **162 test, ~0,4 giây, không cần Elasticsearch** |

Ca đáng chú ý nhất là `2D9057AA`: signaling trông bình thường (đạt `OK_ACK_OK`, kết thúc bằng
`BYE`, PAIR_PING phía callee đều tới sát lúc `BYE`) nhưng ground truth là FAIL. Hệ thống kết luận đúng `FAIL` + `ICE_FAILURE` nhờ
xét media trước khi kết luận SUCCESS — rule chỉ dựa vào signaling sẽ sai ngay trên tập dev.

---

## 8. Giới hạn đã biết

Danh sách đầy đủ, kèm lý do từng mục: `README.md`, mục *Known Limitations*. Dưới đây là các
giới hạn ảnh hưởng tới kiến trúc.

- **Data có nhãn phủ 3/6 issue category.** `NETWORK_PACKET_LOSS`, `NETWORK_DELAY_JITTER`
  không có ca mẫu nào; điều kiện phát hiện viết theo phỏng đoán và được đánh dấu
  `UNVALIDATED` trong `taxonomy.yaml`. `TURN_FAILURE` có 3 ca (các cuộc CANCEL trong `fail/`).
- **Chỉ số chất lượng chỉ có ở 7 leg / 5 cuộc gọi.** Bản ghi summary có ở cả 16 file end
  call log, nhưng 6 cuộc gọi không có end call log (trả `N/A`), và các leg chưa từng nhận gói
  audio nào thì app ghi MOS/loss/RTT = 0; report hiển thị `N/A` kèm lý do thay vì số 0 đó
  (MVP mục 4.3).
- **WebRTC log chưa đồng bộ được với timeline signaling** do dùng mốc thời gian tương đối.
- **Ngưỡng chất lượng chưa kiểm chứng.** Đường nền đo trên 844 mẫu của các cuộc gọi khoẻ
  mạnh: loss cao nhất 3,704%, MOS thấp nhất 4,335, RTT cao nhất 266 ms. Ngưỡng cảnh báo
  bắt buộc phải cao hơn các mức này, nhưng giá trị cụ thể là phỏng đoán.
- **`CANCEL` là app tự huỷ, ở mọi cuộc có end call log để kiểm**: hết 6 giây chờ ICE
  candidate (`_waitingCandidateTimer with error`, rồi `_emitFailed … code: 421`). Các cuộc
  CANCEL không có end call log (`7B56D7AD`, `E9D6C112`, `AA9791CE`) chỉ có cùng dấu vân tay
  trong WebRTC log. Nếu WebRTC log cho thấy TURN không cấp phát được thì xếp
  `TURN_FAILURE`; nếu TURN tốt mà vẫn hết giờ (`for_test/311A9B6A`: camera khởi tạo chậm)
  thì lỗi ở phía client.
- **Signaling không có số dòng.** Evidence signaling trích dạng `signaling#87` (sự kiện thứ 87
  trong kết quả truy vấn ES), không phải dòng của file `signaling.json`; log đính kèm vẫn là
  `tên file:số dòng`.
- **`--parse-call` / `--parse-all` dừng với lỗi khi ES tắt**, vì `normalize()` chưa bọc lỗi ES
  như `buildTimeline()`. `--analyze` không bị: nó trả `UNKNOWN` kèm lý do.
