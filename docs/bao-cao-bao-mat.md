# Security Evaluation Report — Sprint 3 T6

> Ngày 2026-10-10, nhánh `sprint-3/p0-baseline`. Yêu cầu: MVP mục 7.1 T6 (*"Chạy lại toàn bộ S01-S08, báo cáo leakage
> input / output"*), mục 6.2 (Sanitizer đầu vào và đầu ra), mục 6.5 (Security Leakage input = 0%, output = 0 — điều kiện
> **bắt buộc** của mục 8.1).
>
> Cập nhật 2026-10-20: số đo của ba lượt `dev` ×5 sau đó (mục 3, "Các lượt sau"), trong đó lượt chạy lại trước khi nộp
> 2026-10-19 là số chính thức của bản nộp; cổng fail-closed (mục 4.4) và các sửa đổi sau rà soát theo MVP (mục 4.5).
> Mục 1-4.3 giữ nguyên như lúc viết.

## Kết luận

| Phép đo | Kết quả | Target |
|---|---|---|
| S01-S08 (test tự động) | **20/20 test qua** (`mvn test` 2026-10-20: 600 test) | Tất cả qua |
| Leakage input — giá trị gốc tới AI | **0 / 1 555** giá trị gốc qua 66 lần chạy (10/10); **0 / 8 255** qua 345 lần chạy ở cả ba lượt 14/10, 15/10 và 19/10 (AI thật) | 0% |
| Leakage output — report và câu trả lời | **0** ở cả ba lượt | 0 |
| Leakage output — application log | **0** (66/66 lần chạy 10/10; 345/345 lần chạy 14/10, 15/10 và 19/10) | 0 |
| Kiểm độc lập toàn bộ data mẫu (`scripts/verify-sanitizer`) | **0 giá trị sót** trên 42 file đã làm sạch | 0 |

Không thấy giá trị nhạy cảm gốc nào lọt ra ở cả ba kênh mà MVP 6.2 nêu (AI, report / response, log). Phép đo tìm ra
**một lỗi chức năng** do Sanitizer gây ra (mục 4.1): che quá tay làm mất câu hỏi — không lộ dữ liệu, nhưng người dùng
nhận câu từ chối. **Đã sửa** theo phương án C (2026-10-10); chạy lại case-019: 3/3 câu ra report, vẫn 0 giá trị lộ.

## 1. Ba lớp kiểm

| Lớp | Đo gì | Độc lập với Sanitizer ở chỗ nào |
|---|---|---|
| Test S01-S08 | Từng dạng dữ liệu nhạy cảm của MVP 6.4, trên dữ liệu giả | Đầu vào viết tay, kỳ vọng viết tay |
| Evaluation Runner | Mọi chuỗi gửi AI, mọi phản hồi, mọi dòng application log của từng lần chạy benchmark `dev` | Giá trị gốc lấy bằng bộ đọc riêng (`SensitiveValues`), không dùng regex của Sanitizer |
| `scripts/verify-sanitizer` | Làm sạch toàn bộ `success/` + `fail/`, quét bản sạch | Script Python riêng; IP quét bằng thư viện `ipaddress` |

## 2. S01-S08

`mvn test` 2026-10-10: **413 test, 0 lỗi**; chạy lại 2026-10-19: **565 test, 0 lỗi** (590 sau T10, cùng ngày; 600 sau khi sửa theo Peer Evaluation, 2026-10-20). Các test mang tên ca:

| Ca | Test | Kiểm |
|---|---|---|
| S01 JWT trong log | `SensitiveDataSanitizerTest` | JWT bị xoá, chữ xung quanh giữ nguyên |
| S02 Authorization header | `SensitiveDataSanitizerTest` | `Bearer` và `Basic` đều xoá trọn giá trị |
| S03 API key trong exception | `SensitiveDataSanitizerTest` ×2 | Dạng `tên=giá trị`, key đứng một mình, password trong JSON escape |
| S04 Phone / email | `SensitiveDataSanitizerTest` ×3, `RequestParserServiceTest`, `ChatAnalysisServiceTest` | Bị che; không che nhầm đuôi UUID / timestamp; câu hỏi chứa SĐT tới AI đã che; SĐT dính `_` trong tên file bị che ở trích dẫn evidence (2026-10-19) |
| S05 Client IP / device ID | `SensitiveDataSanitizerTest` ×5 | IPv4, IPv6, IP che dở, candidate, `Cand[]`, mật khẩu ICE, fingerprint, device ID |
| S06 Nested JSON | `SensitiveDataSanitizerTest`, `ChatAnalysisServiceTest` | Chuỗi JSON lồng nhau qua đúng bộ làm sạch của pipeline: không còn bí mật, SĐT, IP, định danh gốc; chỉ số ALLOW giữ nguyên. Trọn pipeline: end call log có cột cấu hình chứa JSON lồng nhau → context gửi AI và report không còn giá trị gốc. (Trước 2026-10-19 test S06 kiểm `JsonSanitizer`, một class pipeline không gọi — đã xoá.) |
| S07 AI trả lại giá trị nhạy cảm | `AiVerdictServiceTest`, `RequestParserServiceTest` | Làm sạch trước Guardrails và trước report; trọng tâm AI trả về cũng được che |
| S08 Error message / application log | `SanitizingConvertersTest` ×2 | Nội dung log và message trong stack trace được làm sạch, stack trace còn |

## 3. Đo trên benchmark `dev`

Lượt `benchmark/results/bao-mat-sprint-3/evaluation-20261010-173559.md`: 19 case, 66 lần chạy, `gpt-4o-mini`,
Prompt AI `7e4465ebd5fa / caf500c08777 / bd618652241b / fe0dcba2e2a6` (giống lượt mốc), ≈ 0,05 USD.

### Phép đo được mở rộng ở Sprint 3

Sprint 2 ghi ba chỗ phép đo của runner chưa phủ (README, Known Limitations). Cả ba đã được bổ sung:

| Chỗ hở ở Sprint 2 | Sprint 3 |
|---|---|
| Không gom IPv6 | Gom địa chỉ IPv6 unicast toàn cầu bằng parser địa chỉ (`SensitiveValues.isGlobalIpv6`), không bằng regex: log WebRTC đầy chuỗi chỉ *trông như* IPv6 (`Port[6449f700:0:1:0:host`, `WRVMC::`, fingerprint). Bắt thêm 1-3 địa chỉ ở 11/18 case đã có từ lượt mốc |
| Không tính giá trị trong câu hỏi | Câu hỏi được quét cùng bộ đọc (IP, JWT, Bearer, API key, `token=…`, SĐT, email) |
| Không quét application log | Runner thu log của từng lần chạy bằng **chính encoder của appender console** (`LogbackLogTap`) — đúng thứ được ghi ra sau converter `%smsg` / `%sex`, rồi quét |
| Không có ca dữ liệu nhạy cảm đầu-cuối | **case-019** (scenario 6): end call log có chèn SĐT, email, Bearer JWT, API key giả vào lời nhắn lỗi mà evidence chép nguyên văn vào context AI; ba câu hỏi mang SĐT, JWT, IPv4, IPv6, email, Authorization, API key giả |

Bộ đọc thêm cũng tự kiểm trên data thật: các mẫu bí mật / liên hệ cho **0 kết quả** trên log đính kèm của `success/`
+ `fail/` (data đã xử lý PII), nên không đếm nhầm thứ không nhạy cảm.

### Kết quả

| Metric | Kết quả |
|---|---|
| Security Leakage (input) | **0,0%** — 0 / 1 555 giá trị gốc |
| Security Leakage (output) | **0** — report / response 0; application log 0 trên 66/66 lần chạy |

case-019 câu 1 gửi AI: `Máy [CONTACT_REDACTED] gọi bị lỗi, token [REDACTED], IP [IP_REDACTED] — cuộc gọi này lỗi gì
vậy?`; câu 2: `Khách [CONTACT_REDACTED] (IPv6 [IP_REDACTED]) báo bên nhận không nghe được gì, vì sao?`. Cả hai ra
FAIL · ICE_FAILURE như case-001 — dữ liệu nhạy cảm không làm đổi kết luận.

### Các lượt sau

| Lượt | Quy mô | Leakage input | Leakage output (report / log) |
|---|---|---|---|
| `ban-cuoi-2026-10-14` (cuối Sprint 3) | 20 case, 345 lần chạy | **0 / 8 255** | 0 / 0 trên 345/345 |
| `gop-y-mentor-json` (2026-10-15, có cổng fail-closed và G05 mới) | 20 case, 345 lần chạy | **0 / 8 255** | 0 / 0 trên 345/345 |
| `truoc-nop-2026-10-19` (chạy lại trước khi nộp — số chính thức của bản nộp) | 20 case, 345 lần chạy | **0 / 8 255** | 0 / 0 trên 345/345 |

Lượt 15/10 chạy cùng cổng fail-closed: 0 fallback trên 345 lần chạy, tức cổng không chặn lần nào. Kiểm riêng chạy
không key trên `dev`, `for_test/` và `ai20k_new_dataset` cũng 0 lần chặn (design-decisions mục 44).

## 4. Phát hiện

### 4.1 Sanitizer che quá tay làm mất câu hỏi — lỗi chức năng, không phải lộ dữ liệu — ĐÃ SỬA

case-019 câu 3: `Authorization: Bearer eyJ… api_key=sk-FAKE… phan tich cuoc goi nay giup minh` tới AI thành
`Authorization: [REDACTED] api_key=[REDACTED]`. Phần *"phan tich cuoc goi nay giup minh"* bị xoá cùng bí mật, AI không
còn gì để phân loại → `OUT_OF_SCOPE`, người dùng nhận câu từ chối (1/66 lần chạy; Verdict Accuracy lượt này 58/59).

**Nguyên nhân:** với mục DROP, giá trị sau `tên=` / `tên:` được che **tới hết dòng** (hoặc tới `,` `;` `"` `}` `]`) —
`SensitiveDataSanitizer.keyValuePattern`. Cố ý: dừng ở khoảng trắng thì `Authorization: Basic dXNlcjpwYXNz` chỉ che chữ
"Basic" và lộ phần base64. Cùng nguyên nhân với hiện tượng đã ghi ở báo cáo Sprint 2: dòng credential TURN bị xoá từ sau
`username=` tới cuối dòng.

**Phương án (đánh đổi giữa an toàn và giữ chữ):**

| | Cách | Được | Mất |
|---|---|---|---|
| A | Giữ nguyên, ghi Known Limitations | An toàn nhất | Câu hỏi dán bí mật rồi viết tiếp trên cùng dòng bị từ chối |
| B | Giá trị = một từ, hoặc *scheme + một từ* khi từ đầu là `Basic` / `Bearer` / `Digest` / `Token` | Giữ chữ phía sau; vẫn che trọn `Basic xxx`, `Bearer xxx` | Bí mật có khoảng trắng (`password=my secret pass`) lộ phần sau từ đầu tiên |
| C | Như B, nhưng chỉ áp cho **câu hỏi**; log vẫn che tới hết dòng | Log an toàn như cũ; câu hỏi giữ chữ | Hai chính sách cho một mục — phải ghi rõ trong inventory |

**Đã chọn C** (chủ repo, 2026-10-10): log là nơi bí mật thật hay xuất hiện (header, exception) nên giữ chặt; câu hỏi do
người dùng gõ, mất câu hỏi là lỗi người dùng thấy ngay.

**Đã làm:** `SensitiveDataSanitizer.sanitizeQuestion` — mục DROP dạng `tên=giá trị` chỉ che một từ, hoặc scheme xác
thực (`Basic` / `Bearer` / `Digest` / `Negotiate` / `Token`) + một từ. Áp cho câu hỏi (`SafeQuestion`), trọng tâm AI tách
từ câu hỏi (`RequestParserService`), câu hỏi và trọng tâm trong context AI (`AiContextBuilder`). Log, exception, câu
trả lời AI giữ chế độ che tới hết dòng. Inventory ghi rõ hai chế độ. Thêm 3 test.

**Chạy lại case-019** (3 câu, AI thật): câu 3 tới AI thành `Authorization: [REDACTED] api_key=[REDACTED] phan tich cuoc
goi nay giup minh` → `ANALYZE_CALL`, FAIL · ICE_FAILURE như hai câu kia. Leakage input 0 / 93, output 0, log 0.

**Rủi ro còn lại (đã chấp nhận):** bí mật có khoảng trắng gõ thẳng vào câu hỏi (`password=my secret pass`) lộ phần sau
từ đầu tiên. JWT, `Bearer …`, `sk-…`, AKIA, AIza vẫn bị bắt bằng pattern riêng, không phụ thuộc chế độ này.

### 4.2 Script kiểm độc lập từng báo đạt giả trên Windows — đã sửa

`scripts/verify-sanitizer/run.sh` dùng `mktemp -d`. Trên Git Bash, Java nhận `/tmp/…` (nằm trong
`-Dspring-boot.run.arguments`, không bị đổi) và ghi vào `D:\tmp\…`, còn Python nhận bản đã bị Git Bash đổi sang thư mục
Temp của Windows — quét **0 file** mà vẫn in "còn sót=0" ở mọi dòng. Đã sửa: thư mục ra tương đối `target/verify-sanitizer`;
`verify_sanitized.py` dừng với lỗi khi không đọc được file nào. Chạy lại: 42 file, 0 sót.

### 4.3 Signaling trong Elasticsearch

Signaling nạp vào Elasticsearch local ở dạng gốc (`appUserId`, `isp`, `asn`). MVP 6.2 đặt "dữ liệu ghi vào ES local"
dưới mục Output; dự án hiểu là kết quả phân tích ghi vào ES — từ Sprint 3 T9 là index `analysis-requests`, chỉ gồm mã,
số và cờ, `call_id` đã mã giả; T10 (2026-10-19) thêm index `call-analysis-results` cùng nguyên tắc (mỗi Call-ID một bản
ghi: verdict, loại lỗi, độ tin cậy, nguồn dữ liệu, số evidence; test `ElasticsearchBatchResultSinkTest` khẳng định không
có trường chữ tự do). Nếu cần làm sạch cả dữ liệu nạp: mã giả `appUserId` lúc import và sửa cách suy ra leg.
Bản export thô có thêm `message` (chữ tự do mang User-ID, Call-ID gốc) và `clientIp`: cả hai KHÔNG được nạp; từ
`message` chỉ giữ cờ có kiểu `noSessionsFound` (2026-10-19).

### 4.4 Cổng fail-closed trước khi gửi AI (góp ý mentor, 2026-10-15)

`OutboundGate` quét lại đúng chuỗi sắp gửi AI (cả lời gọi phân loại và phân tích) bằng bộ nhận diện của phép đo
Leakage, độc lập với regex của Sanitizer. Còn IP, bí mật, SĐT, email thì không gửi, report lùi về rule
(`SENSITIVE_DATA_BLOCKED`), log chỉ ghi tên loại. Test: `OutboundGateTest`, `OpenAiChatClientTest.sensitiveMessageIsNeverSent`
(0 request HTTP, không thử lại). Giới hạn: cổng không nhận ra định danh gốc (User-ID, Device-ID), mật khẩu ICE,
fingerprint, hostname nội bộ — các loại này chỉ có Sanitizer chặn.

### 4.5 Sửa sau rà soát theo MVP (2026-10-19)

| Phát hiện | Rủi ro | Đã sửa |
|---|---|---|
| Trích dẫn evidence (`tên file:dòng`) trong report không qua Sanitizer | Tên file do người dùng đặt (`0912345678_caller_endcall.log`) hiện nguyên văn trên Web UI | Report làm sạch cả trích dẫn, như context AI vốn làm |
| Mẫu SĐT dùng ranh giới `\w`, nên SĐT dính `_` không bị nhận | Lọt ở cả report lẫn context AI | Ranh giới là chữ / số. Quét lại toàn bộ data mẫu: số khớp trước / sau như nhau (1, đúng SĐT cài sẵn của biến thể S06) |
| Test S06 kiểm `JsonSanitizer`, class mà pipeline không gọi | S06 "qua" mà không kiểm đường thật | Xoá class; S06 kiểm chuỗi JSON lồng nhau qua đúng bộ làm sạch của pipeline, và một test đi trọn pipeline |

## 5. Giới hạn của phép đo

- IPv6: chỉ tính unicast toàn cầu (2000::/3) — địa chỉ client công cộng. Link-local, ULA không tính.
- Số điện thoại: chỉ dạng di động Việt Nam (`0` / `84` / `+84` + 9 chữ số, đầu 3 / 5 / 7 / 8 / 9).
- Application log: đo trong lượt chạy runner (CLI). Web profile dùng cùng `logback-spring.xml` và cùng converter, nhưng
  log của tầng HTTP (Tomcat, Spring MVC) không đi qua runner.
- Giá trị ngắn hơn 6 ký tự không gom (dễ trùng ngẫu nhiên với chữ thường).
- Tên file người dùng đặt không nằm trong data mẫu, nên phép đo trên benchmark không phủ kênh này; nó chỉ được kiểm bằng
  test (`ChatAnalysisServiceTest.fileNameInCitationIsSanitized`).

## Chạy lại

```bash
./mvnw test                                                    # S01-S08 cùng toàn bộ test
python scripts/benchmark-variants/make_variants.py ../ai20k_sample   # tạo file biến thể (case-016, case-019)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--evaluate=benchmark/dev-cases.yaml --logs=../ai20k_sample"
bash scripts/verify-sanitizer/run.sh ../ai20k_sample           # kiểm độc lập toàn bộ data mẫu
```
