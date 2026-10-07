# Minh chứng Sprint 2 — MVP mục 6.1 đến 6.6

Đối chiếu từng yêu cầu ở MVP mục 6 với **file code, file test và số đo được** trong repo.
Số liệu chạy ngày **2026-10-06** (benchmark `dev` 315 lần chạy với `gpt-4o-mini`); test chạy lại 2026-10-07: 390 test.
Đường dẫn code tính từ `src/main/java/io/hason/callanalysis/`, test từ `src/test/java/io/hason/callanalysis/`.

## Tóm tắt

| Mục MVP | Nội dung | Trạng thái |
|---|---|---|
| 6.1 | 11 task (T1-T10 Core, T11 Nếu kịp) | 10/10 Core ✅ · T11 ⬜ chưa làm — đề bài "semi-structured context" chưa chốt |
| 6.2 | Sanitizer đầu vào và đầu ra | ✅ — benchmark: 0/7 005 giá trị gốc tới AI, 0 trong phản hồi |
| 6.3 | Benchmark `dev`, `held-out`, định dạng case | ✅ `dev` và định dạng case — thiếu loại ca "SUCCESS chất lượng kém" (data không có). `held-out` do Mentor giữ và chạy; runner đã sẵn sàng |
| 6.4 | Test S01-S08, G01-G05, F01-F04 | ✅ đủ 17 ca, có test riêng |
| 6.5 | Evaluation Metrics | ✅ đo đủ, trừ Issue Category Accuracy (ground truth chưa có nhãn category) |
| 6.6 | Deliverables | ✅ — demo đầu cuối: `docs/demo-sprint-2.md`, kết quả `docs/ket-qua-demo-sprint-2.md` |

---

## 6.1. Task

| Task | MVP yêu cầu | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|---|
| **T1 Web UI tối giản** | Ô câu hỏi, đính kèm nhiều file, hiển thị report, không đăng nhập | `resources/static/index.html`, `app.js`, `app.css`; bật bằng profile `web` (`resources/application-web.yml`) | `controller/AnalyzeControllerTest` (layout report lấy từ renderer) — JS chưa có test tự động | Thử trên server thật bằng Playwright 2026-10-05: report đủ mục, banner degraded / cần kiểm tra / ngoài phạm vi / lỗi đầu vào, màn 390 px, chế độ tối |
| **T2 Chat API + Orchestration + Report Renderer** | Nhận message + file (multipart); điều phối pipeline; render theo template từ JSON đã validate | `controller/AnalyzeController` (`POST /api/analyze`, `GET /api/report-layout`), `service/ChatAnalysisService`, `domain/report/ReportRenderer`, `ReportDecision`, `infrastructure/report/ReportSchemaValidator` | `ChatAnalysisServiceTest` (8), `AnalyzeControllerTest` (6), `ReportRendererTest` (7), `ReportDecisionTest` (6) | 2026-10-05, AI thật: `2D9057AA` → HTTP 200 sau 6,6 s, FAIL · ICE_FAILURE · MEDIUM, nguồn AI. Template Compliance 100% trên benchmark |
| **T3 File Validator** | Nhận loại file theo nội dung; kiểm cùng Call-ID; giới hạn kích thước; báo file thiếu | `domain/validation/FileValidator`, `FileValidation`, `AttachedFile`; gọi từ `service/CallLogNormalizationService`; `infrastructure/file/CallFolderReader` (không nạp file quá cỡ) | `FileValidatorTest` (14), `CallFolderReaderTest`, các test F0x ở `CallLogNormalizationServiceTest` | `--analyze-all` sau khi có T3: verdict 13/13, schema 20/20 không đổi; thêm 5 file lỗi vào `EE129C8F` → cả 5 bị loại đúng lý do |
| **T4 Request Parser** | Phân loại intent (4.4), trích focus, trích Call-ID | `service/RequestParserService`, `domain/request/` (`CallIdExtractor`, `KeywordIntentClassifier`, `SafeQuestion`, `IntentClassification`, `ParsedRequest`), `infrastructure/ai/OpenAiIntentClassifier`, `resources/prompts/parse-request.system.txt`, `schema/request-intent.schema.json` | `RequestParserServiceTest`, `KeywordIntentClassifierTest`, `RequestModelTest`, `OpenAiIntentClassifierTest` | Intent Accuracy 100% (290/290) trên benchmark; bộ câu mới `benchmark/intent-check.yaml`: prompt cũ đẩy nhầm 5/8 câu trong phạm vi ra ngoài, prompt mới 0/8 |
| **T5 AI Analysis Engine** | Input intent/focus + timeline + evidence + chỉ số + taxonomy; structured output 8 trường | `service/port/AiAnalyzer`, `infrastructure/ai/OpenAiAnalyzer`, `OpenAiChatClient`, `domain/ai/AiContextBuilder`, `AiContext`, `AiAnalysis`, `resources/prompts/analyze-call.system.txt`, `schema/ai-analysis.schema.json` | `AiContextBuilderTest` (10), `OpenAiAnalyzerTest` | 13 cuộc có nhãn: AI 13/13 khớp ground truth, 0 fallback; benchmark: ~3 000 token vào, ~175 ra mỗi lần |
| **T6 Guardrails** | verdict / category thuộc taxonomy; evidenceId tồn tại; không số ngoài chỉ số; lệch rule → UNKNOWN / gắn cờ; invalid → reject / fallback | `domain/guardrail/Guardrails`, `GuardrailResult`; gọi từ `service/AiVerdictService` | `GuardrailsTest` (11) | Benchmark: 0 số bịa tới người dùng; G05 chặn 0-3% đầu ra thô của AI tuỳ lượt |
| **T7 Sensitive Data Detector & Sanitizer** | Input và output, xem 6.2 | `domain/security/SensitiveDataSanitizer`, `Pseudonymizer`, `SensitiveField`; `infrastructure/security/` (`SensitiveDataInventoryLoader`, `SanitizerConfig`, `SanitizerHolder`, `JsonSanitizer`); `infrastructure/logging/Sanitizing*Converter`; `resources/sensitive-data-inventory.yaml`, `logback-spring.xml` | `SensitiveDataSanitizerTest` (16), `SensitiveDataInventoryTest`, `SanitizingConvertersTest`, các test S0x ở mục 6.4 | Xem 6.2 |
| **T8 Fallback** | AI timeout, provider unavailable, invalid response → report từ rule, đánh dấu degraded | `service/AiVerdictService` (mọi đường lỗi), `ChatAnalysisService` (report có phần AI sai schema → dựng lại từ rule) | `AiVerdictServiceTest`, `ChatAnalysisServiceTest::aiFailureIsDegraded`, `EvaluationServiceTest::aiUnavailableIsDegraded` | Chạy không có key: mọi report degraded `NOT_CONFIGURED`, Pipeline Success vẫn 100% |
| **T9 Evaluation Runner** | Chạy benchmark lặp lại được; đo metric 6.5; chạy lặp đo consistency; nhận bộ case từ file | `cli/EvaluateCommand`, `service/EvaluationService`, `domain/evaluation/`, `infrastructure/evaluation/BenchmarkCaseLoader`, `CaseFileResolver` | `EvaluationScorerTest` (13), `EvaluationServiceTest` (8), `BenchmarkCaseLoaderTest` (8), `CaseFileResolverTest` (5), `TemplateComplianceTest`, `SensitiveValuesTest` | `--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample --repeat=5`: 315 lần chạy, ghi `.md` + `.json` |
| **T10 Benchmark `dev`** | Xem 6.3 | `benchmark/dev-cases.yaml`, `scripts/benchmark-variants/make_variants.py` | `BenchmarkCaseLoaderTest::repoDevCases` (kiểm chính file benchmark) | 18 case, 63 câu |
| T11 So sánh cách dựng context | Nếu kịp | — | — | ⬜ Runner đã ghi token từng lần chạy; khái niệm "semi-structured context" chưa chốt |

---

## 6.2. Sensitive Data Sanitizer

### Đầu vào — trước khi gửi AI

| Bước MVP | Code |
|---|---|
| Normalized Data / Evidence / Metrics | `service/AnalyzeCallService.aiContext` lấy timeline, evidence, chỉ số đã chuẩn hoá — không lấy log thô |
| Sensitive Data Detector | `domain/security/SensitiveField` (nhận theo tên trường và theo giá trị) + regex trong `SensitiveDataSanitizer` |
| Policy Engine theo Sensitive Data Inventory | `resources/sensitive-data-inventory.yaml` → `SensitiveDataInventoryLoader` → `HandlingPolicy` của từng mục |
| Mask / Drop / Pseudonymize / Minimize | `SensitiveDataSanitizer` (một phiên nhớ định danh có cấu trúc trước rồi mới thay), `Pseudonymizer` (HMAC) |
| Safe AI Context (minimum necessary) | `domain/ai/AiContextBuilder` → `AiContext`; constructor package-private, chỉ builder tạo được. Câu hỏi đi qua `SafeQuestion` |

Ví dụ trong MVP 6.2 và test tương ứng:

| MVP | Test |
|---|---|
| `Authorization: Bearer eyJhbGci...` → `Authorization: [REDACTED]` | `SensitiveDataSanitizerTest::s02AuthorizationHeaderIsDropped` |
| `phone=0987654321` → `phone=[PHONE_REDACTED]` | `SensitiveDataSanitizerTest::s04PhoneAndEmailAreMasked` |
| `user_id=123456` → `user_id=USER_a81f2c` | `SensitiveDataSanitizerTest::s06NestedJsonIsSanitizedStructurally` (định danh thành `USER_` + 6 ký tự hex) |

### Đầu ra — trước khi hiển thị hoặc ghi log

| MVP | Code | Test |
|---|---|---|
| AI response không chứa giá trị nhạy cảm gốc | `AiVerdictService.sanitizeOutput` (trước Guardrails), `RequestParserService.sanitizeOutput` (trọng tâm) | `AiVerdictServiceTest::s07AiResponseIsSanitized`, `RequestParserServiceTest::aiFocusIsSanitized` |
| Report, response web | `AnalyzeCallService.sanitize` (report), schema kiểm sau đó | `ChatAnalysisServiceTest::sensitiveValuesNeverLeak` |
| Application log | `logback-spring.xml` dùng `%smsg` / `%sex` → `SanitizingMessageConverter`, `SanitizingThrowableConverter` | `SanitizingConvertersTest::s08LogMessageIsSanitized`, `::s08ExceptionMessageIsSanitized` |
| Log AI chỉ gồm request_id, model, latency, result_status, token, fallback_reason | `OpenAiAnalyzer`, `OpenAiIntentClassifier`, `AiVerdictService`, `RequestParserService` log đúng các trường này; không log câu hỏi | ⚠️ chưa có test tự động (Sprint 3 T5) |
| Không log raw AI request / response | `OpenAiChatClient` không log; lỗi HTTP chỉ giữ mã trạng thái | — |

**Số đo:** Evaluation Runner gom giá trị nhạy cảm gốc bằng bộ đọc riêng (`domain/evaluation/SensitiveValues`, không dùng
regex của Sanitizer) rồi tra trong dữ liệu gửi AI và trong phản hồi: **0/7 005** và **0** trên 315 lần chạy. Kiểm độc
lập `scripts/verify-sanitizer` trên bản làm sạch 42 file log (`--sanitize-dir`): 0 giá trị gốc còn sót.

---

## 6.3. Benchmark

| Yêu cầu MVP | Hiện trạng |
|---|---|
| `dev`: Mentor cung cấp cuộc gọi + ground truth; câu hỏi tự viết thêm | 13 cuộc có nhãn (`fail/` 6 + `success/` 7); 63 câu tự viết, viết trước khi xem hệ thống trả lời |
| `held-out`: Mentor chạy Evaluation Runner cuối Sprint 2 và 3 | Dự án không có tập này. `--evaluate` nhận file case; README có mục "Chạy bộ case `held-out`" |
| Định dạng case YAML (case_id, call_id, files, questions, expected_*) | `BenchmarkCaseLoader` đọc nguyên văn mẫu (`BenchmarkCaseLoaderTest::mvpSampleVerbatim`); phần mở rộng đều tuỳ chọn |
| Mỗi cuộc gọi 3-5 cách hỏi | ✅ mọi case 3-5 câu (`BenchmarkCaseLoaderTest::repoDevCases` kiểm) |
| SUCCESS bình thường | ✅ case-002, 009-014 |
| SUCCESS chất lượng kém | ❌ data mẫu không có cuộc nào có nhãn — câu hỏi mở |
| FAIL thiết lập | ✅ case-004, 007 |
| FAIL media (ICE / TURN) | ✅ case-001 (ICE); 005, 006, 008 (TURN) |
| UNKNOWN do thiếu file | ✅ case-018 (chỉ có Call-ID) |
| Bình thường nhưng có WARN log | ✅ signaling của cả 7 cuộc success có 5-13 event `level=WARN` |
| Biến thể: thiếu file / sai tên / khác Call-ID | ✅ case-015 / 016 (+ `calleer_webrtc.log` thật ở case-013) / 017 |
| Tối thiểu 5 câu `OUT_OF_SCOPE` | ✅ 7 câu |
| Không dùng kết quả `held-out` để tinh chỉnh | Chưa có kết quả `held-out`; `for_test/` cũng không dùng để chỉnh (chỉ chạy để quan sát) |

Ground truth thiếu `expected_issue_category` và `expected_evidence` (MVP 1.4 ghi Mentor cung cấp cả hai) → để `null`, không
tự đặt nhãn; Issue Category Accuracy báo N/A.

---

## 6.4. Test cases bắt buộc

| Ca | Test |
|---|---|
| S01 JWT trong log | `SensitiveDataSanitizerTest::s01JwtIsDropped`, `SensitiveDataInventoryTest::jwtIsDetected`, `RequestParserServiceTest::questionIsSanitizedBeforeAi` |
| S02 Authorization header | `SensitiveDataSanitizerTest::s02AuthorizationHeaderIsDropped` |
| S03 API key trong exception | `SensitiveDataSanitizerTest::s03ApiKeyInExceptionIsDropped`, `::s03PasswordInEscapedJsonIsDropped` |
| S04 Phone / email | `SensitiveDataSanitizerTest::s04PhoneAndEmailAreMasked`, `::s04NoFalsePositiveOnUuidOrTimestamp`, `SensitiveDataInventoryTest::vietnamesePhoneNumberIsDetected` |
| S05 Client IP / device ID | `SensitiveDataSanitizerTest::s05IpAddressesAreMasked`, `::s05DeviceIdIsPseudonymised`, `::s05CandidateKeepsAnalyticalFields`, `::s05LibwebrtcCandidateCredentialsAreDropped`, `::s05SdpSecretsAreRemoved` |
| S06 Nested JSON | `SensitiveDataSanitizerTest::s06NestedJsonIsSanitizedStructurally` |
| S07 AI response chứa lại giá trị nhạy cảm | `AiVerdictServiceTest::s07AiResponseIsSanitized`, `RequestParserServiceTest::aiFocusIsSanitized` |
| S08 Error message / application log | `SanitizingConvertersTest::s08LogMessageIsSanitized`, `::s08ExceptionMessageIsSanitized` |
| G01 Evidence ID không tồn tại | `GuardrailsTest::g01UnknownEvidenceIdIsRejected` |
| G02 AI response sai format | `GuardrailsTest::g02NullAnswerIsRejected`, `::g02MalformedAnswerIsRejected`, `OpenAiAnalyzerTest::nonJsonContentIsInvalid` |
| G03 verdict / category ngoài taxonomy | `GuardrailsTest::g03OutOfTaxonomyIsRejected`, `::g03LowercaseIsNotAccepted`, `TaxonomyAndReportTest::verdictOutsideTaxonomyIsRejected` |
| G04 Verdict AI mâu thuẫn rule | `GuardrailsTest::g04VerdictMismatchBecomesUnknown`, `::g04CategoryMismatchKeepsRuleCategory`, `ReportDecisionTest::verdictMismatchIsCodeWritten`, `::categoryMismatchKeepsRuleText` |
| G05 Số liệu ngoài bộ chỉ số | `GuardrailsTest::g05InventedNumberIsRejected`, `::g05RejectsUnitConversion`, `::g05AllowsNumbersFromContextAndIgnoresIdentifiers`, `AiContextBuilderTest::groundingExcludesQuestion`, `::guardrailsUseTheContextThatWasSent` |
| F01 Thiếu file của một bên | `CallLogNormalizationServiceTest::missingLogOfOneLegIsReported` |
| F02 Tên file không khớp nội dung | `FileValidatorTest::f02TypeComesFromContentNotName`, `::f02UnrecognizedContentIsRejected`, `CallLogNormalizationServiceTest::unrecognizedFileIsReported` |
| F03 Các file thuộc Call-ID khác nhau | `FileValidatorTest::f03ForeignCallIdIsRejected`, `::f03CallIdResolvedFromFilesWhenNotGiven`, `::f03TieIsBrokenByFileName`, `CallLogNormalizationServiceTest::foreignEndCallLogIsExcludedFromTimeline`, `::attachedFileWithForeignCallIdIsReported` |
| F04 File hỏng / vượt giới hạn | `FileValidatorTest::f04OversizedFileIsRejectedWithoutContent`, `::f04LoadedButOversizedIsRejected`, `::f04BinaryFileIsCorrupt`, `::f04MostlyReplacementCharactersIsCorrupt`, `::f04EmptyFileIsRejected`, `CallFolderReaderTest::oversizedFileIsNotLoaded`, `AnalyzeControllerTest::oversizedFileIsRejectedAlone` (qua HTTP) |

Lệnh: `mvn test` → **390 test, 0 lỗi, 0 bỏ qua** (2026-10-07, Docker bật nên 3 test Testcontainers cũng chạy).

---

## 6.5. Evaluation Metrics

Benchmark `dev`, mỗi câu 5 lần (315 lần chạy), `gpt-4o-mini`, 2026-10-06. Cách tính từng metric và chỗ nào là cách hiểu
của dự án: `docs/evaluation-metrics.md`. Code chấm: `domain/evaluation/EvaluationScorer`.

| Metric | Mức | Kết quả | Target |
|---|---|---|---|
| Verdict Accuracy | Bắt buộc | **100%** (280/280) trên `dev`; số chính thức đo trên `held-out` do Mentor chạy — chưa có | ≥ 85% |
| Metric Correctness | Bắt buộc | **100%** (280/280); tính tay Sprint 1: 36/36 + 280/280 | 100% |
| Security Leakage (input) | Bắt buộc | **0%** (0/7 005 giá trị gốc) | 0% |
| Security Leakage (output) | Bắt buộc | **0** | 0 |
| Issue Category Accuracy | Báo cáo | N/A — ground truth chưa có nhãn category | ≥ 70% |
| Consistency | Báo cáo | **100%**, 17/17 case giống hệt qua 5 lần × 3-5 cách hỏi (case-003 toàn câu ngoài phạm vi, không có report để so) | ≥ 95% |
| Template Compliance | Báo cáo | **100%** | 100% |
| Intent Accuracy | Báo cáo | **100%** (290/290 câu có nhãn) | ≥ 90% |
| Unsupported Claim Rate | Báo cáo | report cuối **0%**; đầu ra thô của AI trước Guardrails 0-3% tuỳ lượt | 0% |
| Pipeline Success Rate | Báo cáo | **100%** (280/280) | ≥ 95% |
| Latency | Báo cáo | P50 3,8 s · P95 8,4 s | — |

Báo cáo so sánh AI vs Rule (`dev`): bảng trong báo cáo của runner và ở `docs/evaluation-metrics.md` — 17/17 case rule,
AI và kết luận cuối trùng nhau và trùng ground truth ở mọi lần chạy.

---

## 6.6. Deliverables

| Deliverable | Ở đâu |
|---|---|
| Web UI tối giản | `resources/static/` (T1) |
| Chat API + Orchestration + Report Renderer | `controller/`, `service/ChatAnalysisService`, `domain/report/ReportRenderer` (T2) |
| File Validator, Request Parser | `domain/validation/`, `service/RequestParserService`, `domain/request/` (T3, T4) |
| AI Analysis Engine + Provider Abstraction + Prompt Templates + Structured Output Schema | `infrastructure/ai/`, `service/port/AiAnalyzer`, `resources/prompts/`, `resources/schema/ai-analysis.schema.json` (T5) |
| Guardrails | `domain/guardrail/` (T6) |
| Sensitive Data Detector & Sanitizer (input + output) | `domain/security/`, `infrastructure/security/`, `infrastructure/logging/` (T7) |
| Fallback Mechanism | `service/AiVerdictService` (T8) |
| Benchmark `dev`, Evaluation Runner, AI vs Rule Report (`dev`) | `benchmark/dev-cases.yaml`, `cli/EvaluateCommand` + `domain/evaluation/`, `docs/evaluation-metrics.md` (T9, T10) |
| Test cases S01-S08, G01-G05, F01-F04 | Mục 6.4 ở trên |
| Integration Tests | `infrastructure/es/SignalingImporterIntegrationTest` (Elasticsearch thật qua Testcontainers), `controller/AnalyzeControllerTest` (multipart thật qua MockMvc), `service/ChatAnalysisServiceTest` (cả luồng với AI giả) |
| End-to-End Demo trên web UI | Kịch bản `docs/demo-sprint-2.md`; kết quả chạy `docs/ket-qua-demo-sprint-2.md` |

## Tài liệu kèm theo

- `docs/evaluation-metrics.md` — cách tính metric, kết quả từng lượt đo.
- `docs/design-decisions.md` — quyết định thiết kế, có phần Sprint 2.
- `docs/demo-sprint-2.md`, `docs/ket-qua-demo-sprint-2.md` — kịch bản và kết quả demo trên web.
