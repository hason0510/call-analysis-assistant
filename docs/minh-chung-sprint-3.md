# Minh chứng Sprint 3 — MVP mục 7

Đối chiếu từng yêu cầu ở MVP mục 7 (7.1 task, 7.2 confidence, 7.3 peer evaluation, 7.4 deliverable) với **file code,
file test và số đo được** trong repo. Đường dẫn code tính từ `src/main/java/io/hason/callanalysis/`, test từ
`src/test/java/io/hason/callanalysis/`; số trong ngoặc sau tên test là số test của lớp đó (đếm theo báo cáo Surefire,
test tham số hoá tính từng bộ tham số).

| | |
|---|---|
| Code | Sprint 3 trên `main`, tác giả `hason123`: `bf0114b` (2026-10-14, hardening, đo lường, dataset mới — 125 file, +7 174 / −368 dòng), `4156a20` (T11), `a3b4995` (nhận xét Sprint 2 của mentor), `05ad4d0` (rà soát theo MVP), `10962e7` (T10), commit sửa theo Peer Evaluation (2026-10-20), cùng các commit tài liệu. Cả Sprint 3 (`bf0114b^..HEAD`): 180 file, +11 310 / −797 dòng, trong đó 52 file test (+3 133 dòng) |
| Test | `mvn test` 2026-10-20: **600 test, 0 lỗi** (Docker bật, 6 test Testcontainers cũng chạy; 565 trước T10, 590 trước khi sửa theo Peer Evaluation). Ở `bf0114b`: 533. Cuối Sprint 2: 390 |
| Benchmark | `dev` 20 case, 69 câu × 5 lần = 345 lần chạy, `gpt-4o-mini`. Lượt cuối Sprint 3: 2026-10-14 — `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md`. Lượt chạy lại trước khi nộp trên bản code cuối (MVP 7.1): 2026-10-19 — `benchmark/results/truoc-nop-2026-10-19/evaluation-20261019-162735.md`, cùng dấu vân tay prompt |
| Lý do từng thay đổi | `docs/design-decisions.md` mục 32–49 |

## Tự kiểm lại

```bash
git log --oneline bf0114b^..HEAD                         # các commit Sprint 3
git show --stat bf0114b                                   # danh sách file của commit Sprint 3 lớn nhất
git show bf0114b -- src/main/java/io/hason/callanalysis/domain/confidence/   # xem code một task (ví dụ T3)
export JAVA_HOME="/c/Users/Admin/.jdks/jbr-21.0.10"
mvn test                                                  # 600 test
mvn test -Dtest=ConfidencePolicyTest                      # chạy riêng test của một task
# Chấm lại lượt benchmark trước khi nộp theo đáp án hiện tại — không gọi AI, không cần ES, không tốn tiền
mvn spring-boot:run -Dspring-boot.run.arguments="--rescore=benchmark/results/truoc-nop-2026-10-19/evaluation-20261019-162735.json"
```

Thư mục `benchmark/results/` không commit (`.gitignore`) — số được chép vào `docs/final-evaluation-report.md`. Khi cần
chứng minh số đo, gửi kèm file `.md` + `.json` của lượt chạy.

## Tóm tắt

| Mục MVP | Nội dung | Trạng thái |
|---|---|---|
| 7.1 | 7 task Core (T1–T7) | 7/7 ✅ (Issue Category Accuracy N/A: ground truth không có nhãn) |
| 7.1 | 4 task Nếu kịp (T8–T11) | 4/4 — T10 làm 2026-10-19 (hai chế độ: chỉ signaling và kèm log máy) |
| 7.2 | Confidence Design theo bảng | ✅ `ConfidencePolicy`, test từng dòng bảng |
| 7.3 | Peer Evaluation | ✅ `docs/peer-evaluation-report.md` |
| 7.4 | 3 tài liệu bàn giao + Final Demo | ✅ — Final Evaluation Report mục 6 dành cho kết quả `held-out` do mentor chạy |
| — | Dataset mới của mentor (`ai20k_new_dataset`) | 5 lỗi tool tìm ra và đã sửa, có test; data mẫu vẫn 13/13 |

---

## 7.1. Task

| Task | MVP yêu cầu | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|---|
| **T1 Improve Accuracy & Consistency** | Phân tích lỗi trên `dev`; cải tiến prompt / evidence / rule | (a) AI nhắc chỉ số bằng mã: `domain/ai/MetricCodes` (file mới), `AiContextBuilder`, `domain/guardrail/Guardrails` (G05 mã lạ); đo: `domain/evaluation/MetricMentions`. (b) Evidence mang cờ căn cứ: `domain/evidence/Evidence.ruleBasis`, `EvidenceEngine`, `domain/rule/SignalExtractor.basis`; đo: `domain/evaluation/EvidenceCitation`. (c) `scripts/error-analysis/analyze_runs.py` → `docs/phan-tich-loi-dev.md`. Phụ trợ đo: `infrastructure/ai/AiPromptFingerprint`, `cli/RescoreCommand`, `domain/evaluation/RunAlignment` | `MetricCodesTest` (4), `MetricMentionsTest` (2), `GuardrailsTest::g05ChecksMetricCodes`, `AiContextBuilderTest::metricCodesMatchWhatAiSaw`, `::ruleBasisReachesAi`, `EvidenceEngineTest::ruleBasisIsMarked`, `CallLogNormalizationServiceTest::iceFailedTransitionIsBasis`, `ChatAnalysisServiceTest::metricCodesAreExpandedInReport`, `EvaluationScorerTest::metricCodeShare`, `::evidenceCitationMetrics`, `AiPromptFingerprintTest` (3), `RunAlignmentTest` (4), `EvaluationRunJsonTest` (2) | Unsupported Claim thô 2,9% (Sprint 2) → **0/309**; AI nhắc chỉ số qua mã 90,9%; evidence AI trích trung bình 5,2 → 3,1 / 13,1, giống nhau giữa các lần lặp 95,5%. Phân tích lỗi: 8 lượt, 1 332 lần chạy. Kết quả trước / sau: `benchmark/results/t1a-sprint-3/`, `t1b-truoc/`, `t1b-sau/`. **Còn thiếu:** Issue Category Accuracy — ground truth chưa có nhãn category |
| **T2 Explainability & Suggestion** | Bộ đề xuất chuẩn theo issue category; phân tích trả lời: chuyện gì xảy ra, vì sao, evidence nào, điều tra gì tiếp | `domain/report/ReportBuilder` (đề xuất chuẩn luôn giữ; dòng Tóm tắt ca FAIL do code viết), `domain/rule/SignalingCause` (file mới, 6 nguyên nhân con của `SIGNALING_FAILURE`), `ReportDecision`, `resources/taxonomy.yaml`, `schema/report-v1.schema.json` (`aiSuggestions`), `prompts/analyze-call.system.txt` | `ReportDecisionTest::aiSuggestionsAreOnlyAdditions`, `::noAiSuggestionsWithoutUsableAi`, `RuleVerdictEngineTest::signalingCauseDrivesSuggestions`, `::failVerdictHasNoCauseBasis`, `AiContextBuilderTest::standardSuggestionsReachAi`, `ReportBuilderTest` (10) | `--analyze` 22 report: kết luận, cờ, độ tin cậy giữ nguyên; `dev` ×1 có AI: Verdict / Consistency / Intent 100% — `benchmark/results/sau-t2/` |
| **T3 Confidence Design** | HIGH / MEDIUM / LOW tất định theo bảng 7.2 | `domain/confidence/` (5 file mới: `ConfidencePolicy`, `ConfidenceBasis`, `ConfidenceAssessment`, `DataCoverage`, `EvidenceStrength`); `RuleVerdictEngine` chỉ khai báo `ConfidenceBasis`; report thêm `confidenceReasons` | `ConfidencePolicyTest` (14) — xem mục 7.2 | 22 report `--analyze` giữ nguyên mức; `dev` có AI: mức của 59/59 câu trùng lượt trước — `benchmark/results/sau-t3-t4/` |
| **T4 Reliability Hardening** | Timeout AI / ES, lỗi thân thiện trên UI | `resources/static/app.js` (`AbortController`, banner "Quá thời gian chờ"); `infrastructure/ai/OpenAiChatClient` (429 → `QUOTA_EXCEEDED` / `RATE_LIMITED`); `domain/request/GarbledText` (file mới); `service/CallLogNormalizationService` (`--parse-*` khi ES tắt); `domain/security/SensitiveDataSanitizer.policyForKey` (tra bảng thay vì duyệt inventory) | `OpenAiChatClientTest::quotaExceeded`, `::rateLimited`, `GarbledTextTest` (15), `RequestParserServiceTest::garbledQuestionIsFlagged`, `ChatAnalysisServiceTest::garbledQuestionIsReported`, `CallLogNormalizationServiceTest::parseSurvivesSignalingOutage`, `SensitiveDataSanitizerTest::policyLookupMatchesLinearScan` | Sanitizer `DE7DD314`: 9 405 ms → 977 ms một request, 10 request đồng thời 12,5 s → 2,2 s, report trùng từng byte; `--parse-call` với ES ở cổng chết ra đủ 1 140 event; file 20 MB − 1 KB ra report sau 6,9 s. P95 có AI 8,1 s → 4,5 s (`sau-t3-t4/`) |
| **T5 Observability** | Structured log đã làm sạch: `request_id`, latency, kết quả, fallback, lỗi; dòng AI call đúng 7 trường MVP 6.2 | `infrastructure/logging/SanitizedJsonLogFormatter`, `SanitizingKeyValueConverter` (file mới); `infrastructure/ai/AiCallLog` (file mới); `resources/logback-spring.xml`, `application-web.yml`; `service/ChatAnalysisService` (latency từng bước) | `AiCallLogTest` (5) — `allowedFieldsAreMvpList` khẳng định đúng tập 7 khoá; `SanitizedJsonLogFormatterTest` (2), `SanitizingConvertersTest` (3), `ChatAnalysisServiceTest::requestLogHasStepLatencies` | Web không khoá AI: 9/9 dòng JSON hợp lệ; `call_id` dạng `CALL_xxxxxx` (HMAC). Benchmark: leakage application log 0 trên 345/345 lần chạy |
| **T6 Security Evaluation Report** | Báo cáo bảo mật | `docs/bao-cao-bao-mat.md`; phép đo mới: `domain/evaluation/SensitiveValues` (IPv6, bí mật, giá trị trong câu hỏi), `service/port/ApplicationLogTap` + `infrastructure/logging/LogbackLogTap` (quét log từng lần chạy) | `SensitiveValuesTest` (8), `LogbackLogTapTest` (3), `EvaluationScorerTest::leakageMetrics`, `::logNotCapturedIsSaidSo`, `SensitiveDataSanitizerTest::questionKeepsRequestAfterSecret`, các test S01–S08 (Sprint 2) | Leakage 0/8 255 đầu vào AI, 0 phản hồi, 0 application log (lượt cuối); `scripts/verify-sanitizer`: 42 file, 0 sót — `benchmark/results/bao-mat-sprint-3/` |
| **T7 Peer Evaluation** | Chạy sản phẩm bạn cùng nhóm theo Runbook của họ, báo cáo ≥ 2 issue | `docs/peer-evaluation-report.md` (tài liệu, không có code) | — | Chạy repo của Trần Anh Tú (commit `a5010a6`) theo Runbook của bạn ấy trên cả `ai20k_sample` và `ai20k_new_dataset`, 2026-10-19. Dựng lại từ đầu theo Runbook, `mvn test` 328/328 + `npm test` 15/15, `dev` ×5 (461 lần chạy, ≈ 0,3 USD) khớp đúng số bạn ấy công bố. 3 vấn đề Runbook, 5 issue có bước tái hiện, 3 điểm học được |
| **T8 Retry & Error Classification** (Nếu kịp) | Controlled retry, phân loại lỗi | `domain/ai/RetryPolicy` (file mới; chỉ `TIMEOUT` / `PROVIDER_ERROR`, `max-attempts: 2` = tối đa 2 lần gọi, ngân sách 45 s — `application.yml`); `OpenAiChatClient` (thêm `AUTH_FAILED`, `INPUT_TOO_LARGE`, `REQUEST_REJECTED`); `AiCallLog` dòng `RETRY` | `RetryPolicyTest` (14), `OpenAiChatClientTest` (8): `transientErrorIsRetried`, `retriesAreBounded`, `permanentErrorsAreNotRetried`, `timeoutIsRetriedWithinBudget`, `noRetryBeyondBudget`, `httpErrorsAreClassified`; `AiCallLogTest::retryLine` | Cổng chết → 1 lần thử lại rồi fallback; API thật với khoá giả → `AUTH_FAILED`; câu 1 MB → `INPUT_TOO_LARGE`. Lượt cuối: OpenAI trả 3 lỗi nhà cung cấp, thử lại xử lý được |
| **T9 Kibana View** (Nếu kịp) | Ghi log và kết quả vào ES local; Kibana view request / latency / fallback / lỗi | `domain/observability/RequestRecord`, `service/port/RequestRecordSink`, `infrastructure/es/ElasticsearchRequestRecordSink` (file mới); `resources/es/analysis-requests-mapping.json`; `scripts/kibana/setup_views.py` (data view + 4 saved search) | `ElasticsearchRequestRecordSinkTest` (2) — `noNullsNoFreeText`; `ChatAnalysisServiceTest::everyRequestLeavesOneRecord`, `::unexpectedErrorIsRecorded` | 6 request → 6 tài liệu trong index `analysis-requests`; saved search "fallback" hiện đúng trên Kibana |
| **T10 Batch Analysis** (Nếu kịp) | Phân tích hàng loạt các Call-ID trong ES local, thống kê phân bố verdict / issue category | `cli/BatchAnalyzeCommand`, `service/BatchAnalysisService`, `domain/batch/` (`SourceGroup`, `BatchResult`, `BatchSummary`, `BatchReportRenderer`), `service/port/CallCatalog` + `BatchResultSink`, `infrastructure/es/ElasticsearchCallCatalog` + `ElasticsearchBatchResultSink`, `infrastructure/file/CallLogDirectory`, `resources/es/call-analysis-results-mapping.json`; dashboard thứ hai trong `scripts/kibana/setup_dashboard.py` (file mới trừ script) | `BatchSummaryTest` (4), `BatchReportRendererTest` (5), `BatchAnalysisServiceTest` (6), `CallLogDirectoryTest` (6), `ElasticsearchBatchResultSinkTest` (2), `SignalingImporterIntegrationTest::catalogListsEveryCallIdAcrossPages`, `::batchResultsReplacePreviousRun` | 27 Call-ID trong ES. Chỉ signaling: 0 SUCCESS · 11 FAIL · 16 UNKNOWN (thiếu log máy), loại lỗi chỉ là triệu chứng. Kèm `--logs`: 13 SUCCESS · 14 FAIL · 0 UNKNOWN, 13/13 cuộc có nhãn đúng. Dashboard "Phân tích hàng loạt — toàn kho" hiển thị đúng. Final Evaluation Report mục 10 |
| **T11 Result Dashboard** (Nếu kịp) | Dashboard: phân bố verdict, UNKNOWN rate, fallback rate, latency | `scripts/kibana/setup_dashboard.py` (dùng index của T9) | Chưa có test tự động (script Python gọi API Kibana) | Cách chạy và link dashboard: `docs/runbook.md` mục 5 |

## Dataset mới của mentor (`ai20k_new_dataset`, 7 cuộc, chưa có nhãn)

| Lỗi tool tìm ra | Code sửa | Test |
|---|---|---|
| `signaling.json` có hai định dạng export, bản thô bị từ chối 3/7 file | `infrastructure/es/SignalingJsonReader` | `SignalingJsonReaderTest::rawExportYieldsSameDocumentsAsCompactFormat`, `::rawExportDerivesTruncationFromCounts` |
| Callee nhiều thiết bị — chỉ số lấy nhầm máy không bắt máy | `domain/timeline/CallTimeline` (`audioFile`), `EvidenceEngine`, `ReportBuilder` | `MetricsCalculatorTest::multiDeviceLegUsesDeviceWithAudio`, `EvidenceEngineTest::multiDeviceEvidenceIsTaggedPerDevice`, `ReportBuilderTest::multiDeviceLegIsExplained` |
| Chỉ số media không ghi chiều — AI dùng nhầm chiều | `domain/metrics/CallMetric.aiLabel` | `AiContextBuilderTest::aiMetricLabelCarriesDirectionButReportLabelDoesNot` |
| Cuộc rớt giữa chừng bị giải thích là "media không thiết lập được" | `RuleVerdictEngine`, `ReportBuilder` (Tóm tắt ca FAIL do code viết — design-decisions mục 40) | `RuleVerdictEngineTest::mediaLostMidCallKeepsVerdictButExplainsIt` |
| Độ trễ cao kéo dài không được gắn cờ (`FC3187B4`) | `domain/rule/LegQuality`, `RuleVerdictEngine` — rule `NETWORK_DELAY_JITTER` theo ngưỡng Teams CQD (mục 41) | `RuleVerdictEngineTest::sustainedHighRttFlagsDelayJitter`, `::delayThresholdBoundaries`, `::lossWinsOverDelay` |

Kết luận trên data mẫu sau các sửa trên: 13/13 cuộc có nhãn vẫn đúng. Report và ảnh Web UI của 7 cuộc:
`../image-report-final/<CALL-ID>/`. Kết luận từng cuộc: `docs/final-evaluation-report.md` mục 5.

---

## 7.2. Confidence Design

| Dòng bảng MVP 7.2 / yếu tố | Test (`ConfidencePolicyTest`) |
|---|---|
| HIGH: bằng chứng mạnh + AI khớp rule + dữ liệu đủ | `allThreeGiveHigh`, `agreementNeverRaises` |
| MEDIUM: bằng chứng chưa trọn / thiếu một nguồn | `partialEvidence`, `missingLeg`, `missingSourceWhenAllRequired`, `truncationOnlyHitsAbsenceBased`, `serverRejectionIgnoresMissingLegs` |
| LOW: bằng chứng yếu / AI lệch rule | `weakEvidence`, `aiDisagrees` |
| AI không dùng được (fallback) | `aiUnavailableCapsAtMedium` |
| Chỉ rule (CLI `--analyze`) | `ruleOnlyKeepsRuleAssessment` |

Mức tin cậy chỉ đặt ở `ConfidencePolicy`; nhánh rule khai báo `ConfidenceBasis`. Report ghi lý do (`confidenceReasons`,
bắt buộc trong `report-v1.schema.json`): *"Độ tin cậy: MEDIUM — thiếu log client của caller; bằng chứng rõ: …"*.

## 7.3. Peer Evaluation

✅ [`docs/peer-evaluation-report.md`](peer-evaluation-report.md) — chạy repo của Trần Anh Tú (commit `a5010a6`) theo Runbook của bạn ấy trên cả `ai20k_sample` và `ai20k_new_dataset`, 2026-10-19. Đủ ba ý MVP 7.3:

| MVP 7.3 yêu cầu | Trong báo cáo |
|---|---|
| Runbook có đủ để tự dựng và chạy được không | Mục 1: đủ, nhưng phải tự xử lý 3 chỗ (giới hạn trên JDK; bước build chạy test trước khi import nên không có jar; cổng 8080 viết cứng) |
| ≥ 2 issue kèm bước tái hiện | Mục 2: 5 issue, mỗi issue đối chiếu với log gốc (chỉ số "No sessions found" ra 0 thay vì N/A; cờ mất gói theo mẫu đỉnh; signaling bị cắt bớt không vào Giới hạn dữ liệu; evidence WebRTC không trace được về dòng gốc; chạy chỉ rule ghi là `AI_UNAVAILABLE`), cộng mục "đã kiểm, không phải issue" |
| ≥ 1 điểm thiết kế học được | Mục 3: 3 điểm (kiểm Output Sanitizer bằng AI giả cố tình rò rỉ; đo AI độc lập với rule; `/api/health` + banner) |

Không sửa code của bạn ấy: clone chỉ đọc, `git status` sạch sau khi review; dữ liệu mẫu và key AI đã xoá khỏi bản clone.
Bạn ấy đã sửa cả 5 issue và 3 vấn đề Runbook; kiểm lại ở mục 6 của báo cáo.

Chiều ngược lại — báo cáo của bạn ấy về repo này (commit `a905256`): Runbook "đủ", 2 issue, đã sửa — mục "Sửa theo Peer Evaluation" bên dưới.

## 7.4. Deliverables

| Deliverable | Ở đâu |
|---|---|
| Final End-to-End Assistant (Web UI + Rule + AI) | Profile `web` (`controller/`, `resources/static/`), cách chạy: README "Dành cho người chấm"; 6 scenario Final Demo trong `docs/demo-sprint-3.md` |
| Reliability Mechanisms + Observability | T4, T5, T8, T9, T11 ở mục 7.1 |
| Automated Tests + Evaluation Suite | `mvn test` 600 test; Evaluation Runner `--evaluate` / `--rescore`, bộ `benchmark/dev-cases.yaml`; S/G/F 67/67 (Final Evaluation Report mục 9) |
| Runbook | `docs/runbook.md` |
| Architecture & Security Design | `docs/architecture-security-design.md` |
| Final Evaluation Report & Known Limitations | `docs/final-evaluation-report.md` — ⏳ mục 6 chờ kết quả `held-out` của mentor |
| Security Evaluation Report | `docs/bao-cao-bao-mat.md` (T6) |
| Kịch bản Final Demo (6 scenario MVP mục 10) | `docs/demo-sprint-3.md` — kết quả chạy thật 2026-10-12 ghi dưới từng scenario |
| Peer Evaluation Report | `docs/peer-evaluation-report.md` (T7) |
| Mốc so sánh đầu Sprint 3 | `docs/moc-sprint-3.md` |

## Sửa theo nhận xét Sprint 2 của mentor (2026-10-15, sau commit `bf0114b`)

`mvn test` sau các sửa: **547 test, 0 lỗi** (Docker bật, 3 test Testcontainers cũng chạy). Lý do từng sửa:
`docs/design-decisions.md` mục 42–46.

| Mentor đề xuất | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|
| Profile web nghe `127.0.0.1` | `resources/application-web.yml` (`server.address`) | — | Server thật chỉ nghe `127.0.0.1:8081` |
| Giới hạn số file mỗi request | `controller/AnalyzeController` (`max-files-per-request`, mặc định 10), `static/app.js` | `AnalyzeControllerTest::tooManyFilesIsRejectedBeforeAnalysis` | Gửi 11 file → HTTP 400 `TOO_MANY_FILES`, không gọi AI |
| UI không ghi đè file trùng tên | `static/app.js` (`addFiles`, `sameFile`) | — (JS) | Thử bằng Playwright: hai file cùng tên đều giữ, có cảnh báo |
| IP ví dụ RFC 5737, dọn `.gitignore` | 11 file tài liệu / inventory / test; `.gitignore` | 547 test qua sau khi đổi IP | Quét lại: 0 IP công cộng trong file git theo dõi |
| G05 bắt số liền đơn vị | `domain/guardrail/Guardrails` (`UNIT`) | `GuardrailsTest::g05CatchesNumbersWrittenWithUnits`, `::g05AllowsKnownNumbersWithUnitsAndIgnoresHexFragments` | `dev` ×5: Unsupported Claim 0/310 với G05 mới |
| Cổng fail-closed trước AI | `domain/security/OutboundGate`, `infrastructure/ai/OpenAiChatClient` (`SENSITIVE_DATA_BLOCKED`) | `OutboundGateTest` (2), `OpenAiChatClientTest::sensitiveMessageIsNeverSent` | Chạy không key trên 27 cuộc: 0 lần chặn nhầm; `dev` ×5 có AI: 0 lần chặn |
| Ngưỡng mất gói / jitter, bảng độ nhạy | `domain/rule/LegQuality` (mất gói cả cuộc ≥ 5 %), `SignalExtractor`, `RuleVerdictEngine`; `taxonomy.yaml`; `scripts/quality-thresholds/sensitivity.py` | `RuleVerdictEngineTest::qualityFlagCarriesMeasuredBasis`, `::wholeCallLossThreshold`; `CallLogNormalizationServiceTest::qualityBasisIsTheCountersRecord`, `::transientLossDoesNotFlag` | `--analyze-all`: data mẫu 13/13; dataset mới chỉ `B5F19381` còn cờ mất gói |
| T11 Sprint 2 (structured vs semi-structured) | `domain/ai/AiContextText`, `OpenAiAnalyzer` (`ai.context-format`), runner ghi dòng "Context AI" | `AiContextTextTest` (3), `OpenAiAnalyzerTest::contextFormatSelectsTextOrJson` | Hai lượt `dev` ×5 — Final Evaluation Report mục 3b |
| Retry có kiểm soát | `domain/ai/RetryPolicy` (Sprint 3 T8, đã có ở `bf0114b`) | `RetryPolicyTest`, `OpenAiChatClientTest` | Mục 7.1, T8 |

Chưa làm: quay video demo 6 kịch bản trên trình duyệt. Peer Evaluation (T7) làm ngày 2026-10-19 — mục 7.3.

## Sửa sau rà soát theo MVP (2026-10-19)

Rà soát từng mục MVP với code tìm ra năm chỗ test vẫn xanh mà sai. `mvn test` sau các sửa: **565 test, 0 lỗi**. Lý do:
`docs/design-decisions.md` mục 47.

| Chỗ hở | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|
| Call-ID chữ thường (file case `held-out`, thư mục `ai20k_new_dataset`) làm mọi case `UNKNOWN` | `domain/request/CallIdExtractor.normalize`, `domain/validation/FileValidator`, `infrastructure/evaluation/BenchmarkCaseLoader`, `cli/FetchCallCommand` | `RequestModelTest::normalizesCallId`, `FileValidatorTest::f03CallIdComparisonIgnoresCase`, `::f03ResolvedCallIdIgnoresCase`, `BenchmarkCaseLoaderTest::callIdIsNormalized`, `ChatAnalysisServiceTest::lowercaseKnownCallIdIsNormalized` | `--analyze` thư mục tên viết thường `06bca48c-…`: 255 event signaling, không file nào bị loại |
| Trích dẫn `tên file:dòng` trong report chưa làm sạch; SĐT dính `_` không bị nhận | `service/AnalyzeCallService.sanitize`, `resources/sensitive-data-inventory.yaml` (`phone-and-email`) | `ChatAnalysisServiceTest::fileNameInCitationIsSanitized`, `SensitiveDataSanitizerTest::s04PhoneJoinedByUnderscoreIsMasked` | Quét lại data mẫu: số khớp SĐT trước / sau như nhau |
| "Số lần No sessions found" luôn `N/A` dù bản export thô có | `domain/signaling/SignalingMessages`, `RawSignalingRecord`, `SignalingNormalizer`, `domain/metrics/MetricsCalculator`; `infrastructure/es/SignalingJsonReader`, `SignalingDocument`, `SignalingIndex` (bổ sung mapping), `resources/es/signaling-mapping.json` | `SignalingMessagesTest`, `SignalingJsonReaderTest::noSessionsFoundBecomesTypedFlag`, `SignalingNormalizerTest::noSessionsFoundFlagBecomesAttribute`, `MetricsCalculatorTest::noSessionsFoundCountedPerTransmission`, `::noSessionsFoundZeroIsCounted`, `SignalingImporterIntegrationTest::existingIndexGetsNewFieldsInMapping` | Nạp lại `ai20k_new_dataset`: `06BCA48C` 3 lần, `4FD495C1` 4 lần (khớp đếm tay), bản gọn `N/A` |
| S06 kiểm `JsonSanitizer`, class pipeline không gọi | Xoá `infrastructure/security/JsonSanitizer` | `SensitiveDataSanitizerTest::s06NestedJsonIsSanitized`, `ChatAnalysisServiceTest::s06NestedJsonInLogNeverLeaks` | — |
| Thiếu trường / đầu vào quá lớn (MVP 7.1) | `infrastructure/ai/OpenAiAnalyzer.read` (theo `required` của schema), `domain/guardrail/Guardrails` (G02 `analysis`), `infrastructure/ai/OpenAiChatClient` (`max_completion_tokens`), `controller/AnalyzeController` (`QUESTION_TOO_LONG`), `static/app.js` | `OpenAiAnalyzerTest::missingFieldsAreInvalid`, `::wrongShapeIsInvalid`, `GuardrailsTest::g02BlankAnalysisIsRejected`, `AnalyzeControllerTest::tooLongQuestionIsRejectedBeforeAnalysis` | `--ai-analyze` `2D9057AA` với AI thật: 2/2 lời gọi `OK`, Guardrails qua |

## Sửa theo Peer Evaluation của bạn cùng nhóm (2026-10-20)

Báo cáo của Trần Anh Tú về repo này có 2 issue, cả hai tái hiện được trên server profile `web`. `mvn test` sau các sửa:
**600 test, 0 lỗi**. Lý do: `docs/design-decisions.md` mục 49.

| Issue | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|
| Câu hỏi ghi Call-ID cuộc khác: WebRTC log (không mang Call-ID) của cuộc đính kèm quyết định kết luận | `domain/validation/FileValidator` (`UNVERIFIABLE_CALL_ID`); `domain/timeline/TimelineNote` (`FOREIGN_CALL_FILE`), `service/CallLogNormalizationService`, `domain/rule/SignalExtractor`, `RuleSignals`, `RuleVerdictEngine`, `domain/report/ReportBuilder`; `service/ChatAnalysisService` (ca chỉ WebRTC log + Call-ID gõ tay) | `FileValidatorTest::f03WebRtcWithOnlyForeignEndCallLogIsQuarantined`, `::f03WebRtcIsKeptWhenAMatchingEndCallLogExists`, `::webRtcOnlyIsKeptWithoutContraryEvidence`; `ChatAnalysisServiceTest::mistypedCallIdDoesNotBorrowAnotherCallsWebRtcLog`, `::foreignFilesAreExplainedWhereTheUserLooksFirst`, `::callIdFromQuestionWithOnlyWebRtcIsFlagged`; `RuleVerdictEngineTest::foreignFilesOnlyIsUnknownWithTheRealReason` | Log `2D9057AA` + Call-ID `DE7DD314`: trước FAIL / ICE MEDIUM, nay `UNKNOWN` LOW; Tóm tắt, Đề xuất ("kiểm tra lại Call-ID") và hai dòng đầu Giới hạn dữ liệu nói log thuộc cuộc gọi khác |
| End call log có BOM UTF-8 / lưu UTF-16 bị coi là hỏng → `UNKNOWN` | `infrastructure/file/CallFolderReader.decode` (dùng chung cho file tải lên và đọc từ đĩa); `FileValidator` (thông báo gợi ý lưu UTF-8) | `CallFolderReaderTest::utf8BomIsStripped`, `::utf16WithBomIsDecoded`, `::diskReadMatchesUploadDecoding` | Cùng file của `2D9057AA` có BOM / UTF-16: trước `UNKNOWN`, nay FAIL / ICE như file gốc |
| Góp ý Runbook: dò JDK 21 trong `~/.jdks`; Python cho Kibana | `build.ps1`; `docs/runbook.md` | — | `build.ps1 -v` vẫn chọn đúng JDK 21 |

Không hồi quy: `--analyze-all` 20 cuộc giống từng dòng trước khi sửa; `dev` chỉ rule Verdict 62/62 (`case-017` F03 vẫn giữ
WebRTC log vì có end call log khớp).

## Kết quả đo

Bảng metric MVP 6.5 so Sprint 2 với cuối Sprint 3: README mục "Kết quả đo được — Sprint 3" và
`docs/final-evaluation-report.md` mục 2. Nguồn: `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md`
(dấu vân tay prompt `analyze-call.system.txt e8941db2acc1`).

Lượt chạy lại trước khi nộp (MVP 7.1, 2026-10-19, bản code cuối, cùng dấu vân tay prompt): S/G/F 67/67 test; `dev` ×5 —
310/310 verdict, Leakage 0/8 255 · 0, Unsupported Claim thô 0/310, 0 fallback, P50 / P95 2,95 / 4,04 s, ≈ 0,32 USD.
Nguồn: `benchmark/results/truoc-nop-2026-10-19/`, Final Evaluation Report mục 9. T10 làm sau lượt này nhưng không sửa
luồng phân tích, nên số đo vẫn là của bản nộp; kết quả T10: Final Evaluation Report mục 10.
