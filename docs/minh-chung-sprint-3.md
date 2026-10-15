# Minh chứng Sprint 3 — MVP mục 7

Đối chiếu từng yêu cầu ở MVP mục 7 (7.1 task, 7.2 confidence, 7.3 peer evaluation, 7.4 deliverable) với **file code,
file test và số đo được** trong repo. Đường dẫn code tính từ `src/main/java/io/hason/callanalysis/`, test từ
`src/test/java/io/hason/callanalysis/`; số trong ngoặc sau tên test là số test của lớp đó (đếm theo báo cáo Surefire,
test tham số hoá tính từng bộ tham số).

| | |
|---|---|
| Code | `main` @ `bf0114b` — *feat: Sprint 3 — hardening, đo lường và xử lý dataset mới*, tác giả `hason123`, 2026-10-14: 125 file, +7 174 / −368 dòng, trong đó 35 file test (+2 062 dòng) |
| Test | `mvn test` chạy lại 2026-10-15: **533 test, 0 lỗi**, 3 test Testcontainers bỏ qua (máy tắt Docker). Cuối Sprint 2: 390 |
| Benchmark | `dev` 20 case, 69 câu × 5 lần = 345 lần chạy, `gpt-4o-mini`, 2026-10-14 — `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md` |
| Lý do từng thay đổi | `docs/design-decisions.md` mục 32–41 |

## Tự kiểm lại

```bash
git show --stat bf0114b                                   # danh sách file Sprint 3
git show bf0114b -- src/main/java/io/hason/callanalysis/domain/confidence/   # xem code một task (ví dụ T3)
export JAVA_HOME="/c/Users/Admin/.jdks/jbr-21.0.10"
mvn test                                                  # 533 test
mvn test -Dtest=ConfidencePolicyTest                      # chạy riêng test của một task
# Chấm lại lượt benchmark cuối theo đáp án hiện tại — không gọi AI, không cần ES, không tốn tiền
mvn spring-boot:run -Dspring-boot.run.arguments="--rescore=benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.json"
```

Thư mục `benchmark/results/` không commit (`.gitignore`) — số được chép vào `docs/final-evaluation-report.md`. Khi cần
chứng minh số đo, gửi kèm file `.md` + `.json` của lượt chạy.

## Tóm tắt

| Mục MVP | Nội dung | Trạng thái |
|---|---|---|
| 7.1 | 7 task Core (T1–T7) | 6/7 — T1 🟡 (Issue Category Accuracy chờ nhãn), T7 ⬜ (cần repo của bạn cùng nhóm) |
| 7.1 | 4 task Nếu kịp (T8–T11) | 3/4 — T10 ⬜ (chờ mentor chốt đầu vào batch) |
| 7.2 | Confidence Design theo bảng | ✅ `ConfidencePolicy`, test từng dòng bảng |
| 7.3 | Peer Evaluation | ⬜ |
| 7.4 | 3 tài liệu bàn giao + Final Demo | ✅ — Final Evaluation Report còn chờ kết quả `held-out` của mentor |
| — | Dataset mới của mentor (`ai20k_new_dataset`) | 5 lỗi tool tìm ra và đã sửa, có test; data mẫu vẫn 13/13 |

---

## 7.1. Task

| Task | MVP yêu cầu | Code | Test | Bằng chứng chạy thật |
|---|---|---|---|---|
| **T1 Improve Accuracy & Consistency** 🟡 | Phân tích lỗi trên `dev`; cải tiến prompt / evidence / rule | (a) AI nhắc chỉ số bằng mã: `domain/ai/MetricCodes` (file mới), `AiContextBuilder`, `domain/guardrail/Guardrails` (G05 mã lạ); đo: `domain/evaluation/MetricMentions`. (b) Evidence mang cờ căn cứ: `domain/evidence/Evidence.ruleBasis`, `EvidenceEngine`, `domain/rule/SignalExtractor.basis`; đo: `domain/evaluation/EvidenceCitation`. (c) `scripts/error-analysis/analyze_runs.py` → `docs/phan-tich-loi-dev.md`. Phụ trợ đo: `infrastructure/ai/AiPromptFingerprint`, `cli/RescoreCommand`, `domain/evaluation/RunAlignment` | `MetricCodesTest` (4), `MetricMentionsTest` (2), `GuardrailsTest::g05ChecksMetricCodes`, `AiContextBuilderTest::metricCodesMatchWhatAiSaw`, `::ruleBasisReachesAi`, `EvidenceEngineTest::ruleBasisIsMarked`, `CallLogNormalizationServiceTest::iceFailedTransitionIsBasis`, `ChatAnalysisServiceTest::metricCodesAreExpandedInReport`, `EvaluationScorerTest::metricCodeShare`, `::evidenceCitationMetrics`, `AiPromptFingerprintTest` (3), `RunAlignmentTest` (4), `EvaluationRunJsonTest` (2) | Unsupported Claim thô 2,9% (Sprint 2) → **0/309**; AI nhắc chỉ số qua mã 90,9%; evidence AI trích trung bình 5,2 → 3,1 / 13,1, giống nhau giữa các lần lặp 95,5%. Phân tích lỗi: 8 lượt, 1 332 lần chạy. Kết quả trước / sau: `benchmark/results/t1a-sprint-3/`, `t1b-truoc/`, `t1b-sau/`. **Còn thiếu:** Issue Category Accuracy — ground truth chưa có nhãn category |
| **T2 Explainability & Suggestion** | Bộ đề xuất chuẩn theo issue category; phân tích trả lời: chuyện gì xảy ra, vì sao, evidence nào, điều tra gì tiếp | `domain/report/ReportBuilder` (đề xuất chuẩn luôn giữ; dòng Tóm tắt ca FAIL do code viết), `domain/rule/SignalingCause` (file mới, 6 nguyên nhân con của `SIGNALING_FAILURE`), `ReportDecision`, `resources/taxonomy.yaml`, `schema/report-v1.schema.json` (`aiSuggestions`), `prompts/analyze-call.system.txt` | `ReportDecisionTest::aiSuggestionsAreOnlyAdditions`, `::noAiSuggestionsWithoutUsableAi`, `RuleVerdictEngineTest::signalingCauseDrivesSuggestions`, `::failVerdictHasNoCauseBasis`, `AiContextBuilderTest::standardSuggestionsReachAi`, `ReportBuilderTest` (10) | `--analyze` 22 report: kết luận, cờ, độ tin cậy giữ nguyên; `dev` ×1 có AI: Verdict / Consistency / Intent 100% — `benchmark/results/sau-t2/` |
| **T3 Confidence Design** | HIGH / MEDIUM / LOW tất định theo bảng 7.2 | `domain/confidence/` (5 file mới: `ConfidencePolicy`, `ConfidenceBasis`, `ConfidenceAssessment`, `DataCoverage`, `EvidenceStrength`); `RuleVerdictEngine` chỉ khai báo `ConfidenceBasis`; report thêm `confidenceReasons` | `ConfidencePolicyTest` (14) — xem mục 7.2 | 22 report `--analyze` giữ nguyên mức; `dev` có AI: mức của 59/59 câu trùng lượt trước — `benchmark/results/sau-t3-t4/` |
| **T4 Reliability Hardening** | Timeout AI / ES, lỗi thân thiện trên UI | `resources/static/app.js` (`AbortController`, banner "Quá thời gian chờ"); `infrastructure/ai/OpenAiChatClient` (429 → `QUOTA_EXCEEDED` / `RATE_LIMITED`); `domain/request/GarbledText` (file mới); `service/CallLogNormalizationService` (`--parse-*` khi ES tắt); `domain/security/SensitiveDataSanitizer.policyForKey` (tra bảng thay vì duyệt inventory) | `OpenAiChatClientTest::quotaExceeded`, `::rateLimited`, `GarbledTextTest` (15), `RequestParserServiceTest::garbledQuestionIsFlagged`, `ChatAnalysisServiceTest::garbledQuestionIsReported`, `CallLogNormalizationServiceTest::parseSurvivesSignalingOutage`, `SensitiveDataSanitizerTest::policyLookupMatchesLinearScan` | Sanitizer `DE7DD314`: 9 405 ms → 977 ms một request, 10 request đồng thời 12,5 s → 2,2 s, report trùng từng byte; `--parse-call` với ES ở cổng chết ra đủ 1 140 event; file 20 MB − 1 KB ra report sau 6,9 s. P95 có AI 8,1 s → 4,5 s (`sau-t3-t4/`) |
| **T5 Observability** | Structured log đã làm sạch: `request_id`, latency, kết quả, fallback, lỗi; dòng AI call đúng 7 trường MVP 6.2 | `infrastructure/logging/SanitizedJsonLogFormatter`, `SanitizingKeyValueConverter` (file mới); `infrastructure/ai/AiCallLog` (file mới); `resources/logback-spring.xml`, `application-web.yml`; `service/ChatAnalysisService` (latency từng bước) | `AiCallLogTest` (5) — `allowedFieldsAreMvpList` khẳng định đúng tập 7 khoá; `SanitizedJsonLogFormatterTest` (2), `SanitizingConvertersTest` (3), `ChatAnalysisServiceTest::requestLogHasStepLatencies` | Web không khoá AI: 9/9 dòng JSON hợp lệ; `call_id` dạng `CALL_xxxxxx` (HMAC). Benchmark: leakage application log 0 trên 345/345 lần chạy |
| **T6 Security Evaluation Report** | Báo cáo bảo mật | `docs/bao-cao-bao-mat.md`; phép đo mới: `domain/evaluation/SensitiveValues` (IPv6, bí mật, giá trị trong câu hỏi), `service/port/ApplicationLogTap` + `infrastructure/logging/LogbackLogTap` (quét log từng lần chạy) | `SensitiveValuesTest` (8), `LogbackLogTapTest` (3), `EvaluationScorerTest::leakageMetrics`, `::logNotCapturedIsSaidSo`, `SensitiveDataSanitizerTest::questionKeepsRequestAfterSecret`, các test S01–S08 (Sprint 2) | Leakage 0/8 255 đầu vào AI, 0 phản hồi, 0 application log (lượt cuối); `scripts/verify-sanitizer`: 42 file, 0 sót — `benchmark/results/bao-mat-sprint-3/` |
| **T7 Peer Evaluation** ⬜ | Chạy sản phẩm bạn cùng nhóm theo Runbook của họ, báo cáo ≥ 2 issue | — | — | Chưa làm: cần repo + Runbook của bạn cùng nhóm |
| **T8 Retry & Error Classification** (Nếu kịp) | Controlled retry, phân loại lỗi | `domain/ai/RetryPolicy` (file mới; chỉ `TIMEOUT` / `PROVIDER_ERROR`, `max-attempts: 2` = tối đa 2 lần gọi, ngân sách 45 s — `application.yml`); `OpenAiChatClient` (thêm `AUTH_FAILED`, `INPUT_TOO_LARGE`, `REQUEST_REJECTED`); `AiCallLog` dòng `RETRY` | `RetryPolicyTest` (14), `OpenAiChatClientTest` (8): `transientErrorIsRetried`, `retriesAreBounded`, `permanentErrorsAreNotRetried`, `timeoutIsRetriedWithinBudget`, `noRetryBeyondBudget`, `httpErrorsAreClassified`; `AiCallLogTest::retryLine` | Cổng chết → 1 lần thử lại rồi fallback; API thật với khoá giả → `AUTH_FAILED`; câu 1 MB → `INPUT_TOO_LARGE`. Lượt cuối: OpenAI trả 3 lỗi nhà cung cấp, thử lại xử lý được |
| **T9 Kibana View** (Nếu kịp) | Ghi log và kết quả vào ES local; Kibana view request / latency / fallback / lỗi | `domain/observability/RequestRecord`, `service/port/RequestRecordSink`, `infrastructure/es/ElasticsearchRequestRecordSink` (file mới); `resources/es/analysis-requests-mapping.json`; `scripts/kibana/setup_views.py` (data view + 4 saved search) | `ElasticsearchRequestRecordSinkTest` (2) — `noNullsNoFreeText`; `ChatAnalysisServiceTest::everyRequestLeavesOneRecord`, `::unexpectedErrorIsRecorded` | 6 request → 6 tài liệu trong index `analysis-requests`; saved search "fallback" hiện đúng trên Kibana |
| **T10 Batch Analysis** (Nếu kịp) ⬜ | | — | — | Chưa làm: chờ mentor chốt đầu vào batch chỉ signaling hay kèm log máy |
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

⬜ Chưa làm — cần repo và Runbook của bạn cùng nhóm.

## 7.4. Deliverables

| Deliverable | Ở đâu |
|---|---|
| Runbook | `docs/runbook.md` |
| Architecture & Security Design | `docs/architecture-security-design.md` |
| Final Evaluation Report & Known Limitations | `docs/final-evaluation-report.md` — ⏳ mục 6 chờ kết quả `held-out` của mentor |
| Security Evaluation Report | `docs/bao-cao-bao-mat.md` (T6) |
| Kịch bản Final Demo (6 scenario MVP mục 10) | `docs/demo-sprint-3.md` — kết quả chạy thật 2026-10-12 ghi dưới từng scenario |
| Mốc so sánh đầu Sprint 3 | `docs/moc-sprint-3.md` |

## Kết quả đo

Bảng metric MVP 6.5 so Sprint 2 với cuối Sprint 3: README mục "Kết quả đo được — Sprint 3" và
`docs/final-evaluation-report.md` mục 2. Nguồn: `benchmark/results/ban-cuoi-2026-10-14/evaluation-20261014-102610.md`
(dấu vân tay prompt `analyze-call.system.txt e8941db2acc1`).
