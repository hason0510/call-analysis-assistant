# AI Provider Proposal

> **Cần mentor duyệt trước khi bắt đầu Sprint 2** (MVP mục 1.4, 5.1 — T10).
> Người đề xuất: Bế Nguyễn Hà Sơn · Ngày: 2026-09-23

---

## 1. AI được dùng để làm gì

Phạm vi rất hẹp, và đó là điểm quan trọng nhất khi chọn nhà cung cấp.

Theo MVP mục 3.2, **phần nào tính được bằng code thì không giao cho AI**. Sprint 1 đã chứng minh điều này khả thi: toàn bộ chỉ số, tín hiệu verdict và evidence đều do code tính, đạt **Verdict Accuracy 13/13 trên data có nhãn** mà chưa cần AI.

AI chỉ làm đúng hai việc:

| Việc | Đầu vào | Đầu ra |
|---|---|---|
| Hiểu câu hỏi tiếng Việt | Câu người dùng gõ | `intent` + `focus` |
| Viết phân tích và đề xuất | Timeline + evidence + chỉ số + taxonomy (đã sanitize) | 8 field theo JSON schema |

**Không** giao cho AI: tính chỉ số, quyết định verdict cuối cùng, dựng bố cục report.

Hệ quả khi chọn provider: **năng lực suy luận không phải tiêu chí quyết định**. Thứ quyết định là **structured output** — mô hình có ép được đúng JSON schema không.

## 2. Ước lượng tải thật

Tính từ số liệu Sprint 1, không phải phỏng đoán.

**Kích thước context mỗi request** — sau khi lọc, một cuộc gọi đưa vào AI khoảng:

| Thành phần | Ước lượng |
|---|---|
| Evidence (tối đa 40 mục, đo thực tế 1-20) | ~1 500 token |
| Bộ 18 chỉ số | ~400 token |
| Taxonomy 6 category | ~1 200 token |
| Câu hỏi + system prompt | ~600 token |
| **Tổng input** | **~3 700 token** |
| Output (8 field, phân tích ngắn) | ~500 token |

Con số này nhỏ vì Sprint 1 đã lọc mạnh: một cuộc gọi sinh tới **28 000 sự kiện**, nhưng chỉ khoảng **20 evidence** được đưa vào.

**Số request của benchmark** — theo MVP mục 6.5, Consistency đo bằng *5 lần chạy × 3 cách hỏi*:

```
13 cuộc gọi dev × 3 cách hỏi × 5 lần chạy  = 195 request
+ 5 câu hỏi OUT_OF_SCOPE × 5 lần            =  25 request
                                    một vòng ≈ 220 request
```

Trong Sprint 2-3 dự kiến chạy lại khoảng 15-20 vòng khi tinh chỉnh prompt → **~4 000 request**, tương đương **~15 triệu token input** và **~2 triệu token output**.

## 3. Hai phương án

### Phương án A — Cloud API có structured output

**Dữ liệu rời khỏi máy:** timeline, evidence, chỉ số và taxonomy **sau khi sanitize**. Cụ thể là:
- Tên lệnh signaling, mốc thời gian tương đối, số đo chất lượng — đều `INTERNAL`, policy `ALLOW`
- **Không** gửi: IP, chuỗi ICE candidate, `ufrag`/`pwd`, `appUserId` gốc, tên service
- `appUserId` và `sessionId` được pseudonymize trước khi gửi (giữ tính nhất quán để correlate)

Rủi ro còn lại: dữ liệu production của team — dù đã xử lý — vẫn đi qua hạ tầng bên thứ ba. **Đây là điểm cần mentor quyết định, không phải điểm em tự quyết.**

**Chi phí:** với ~15 triệu token input trong cả dự án, ở mức giá phổ biến hiện nay của các mô hình cỡ nhỏ/trung, chi phí nằm trong khoảng vài đô la tới vài chục đô la. Không phải yếu tố ràng buộc.

**Latency:** thường 1-3 giây cho response cỡ 500 token. Đạt yêu cầu "đo P50/P95" của MVP mục 6.5 một cách thoải mái.

**Structured output:** các API hiện đại hỗ trợ ép JSON Schema ở phía server, tức mô hình **không thể** trả về JSON sai cấu trúc. Đây là ưu thế lớn nhất — nó biến phần lớn ca kiểm thử G02 ("AI response sai format") thành không thể xảy ra.

### Phương án B — Local model qua Ollama

**Dữ liệu rời khỏi máy: không có gì.** Đây là ưu thế tuyệt đối và giải quyết dứt điểm mọi lo ngại về việc log production rời khỏi máy cá nhân.

**Chi phí:** 0 đồng.

**Latency:** đây là vấn đề. Máy cá nhân có 15,7 GB RAM, trong đó **Docker đã được cấp 8,17 GB** và Elasticsearch đang chiếm 2 GB heap. Phần còn lại cho mô hình khá chật. Một mô hình 7-8B lượng tử hoá cần khoảng 5-6 GB và chạy trên CPU sẽ mất **10-30 giây** cho 500 token output.

Nhân với 220 request một vòng benchmark → **40 phút đến 2 giờ mỗi vòng**. Với 15-20 vòng tinh chỉnh, đây là rào cản thực sự cho tiến độ.

**Structured output:** Ollama hỗ trợ tham số `format` nhận JSON Schema, nhưng mức độ tuân thủ phụ thuộc mô hình và yếu hơn ràng buộc phía server của cloud API. Guardrails sẽ phải reject và retry nhiều hơn, càng làm latency tệ thêm.

## 4. So sánh theo đúng 4 tiêu chí MVP mục 5.1

| Tiêu chí | A — Cloud API | B — Local (Ollama) |
|---|---|---|
| **Dữ liệu gửi sang AI sau sanitize** | Evidence + chỉ số + taxonomy đã sanitize. Không có IP, credential, user ID gốc | **Không gì rời khỏi máy** |
| **Chi phí** | Vài đô tới vài chục đô cho cả dự án | 0 |
| **Latency** | 1-3 giây/request → một vòng benchmark ~10 phút | 10-30 giây/request → một vòng **40 phút - 2 giờ** |
| **Structured output** | Ép JSON Schema phía server, **không thể sai format** | Có hỗ trợ nhưng tuân thủ yếu hơn, Guardrails phải retry nhiều |

## 5. Đề xuất

**Chọn phương án A (cloud API có structured output), với điều kiện mentor chấp thuận việc dữ liệu đã sanitize đi ra ngoài.**

Ba lý do, theo thứ tự quan trọng:

1. **Structured output phía server loại bỏ cả một nhóm lỗi.** MVP có riêng ca kiểm thử G02 cho "AI response sai format". Ràng buộc schema ở phía server khiến ca đó gần như không thể xảy ra, thay vì phải xử lý bằng retry.

2. **Latency quyết định được số vòng tinh chỉnh.** Sprint 3 T1 yêu cầu *"phân tích lỗi trên dev, cải tiến prompt/evidence/rule"* — đó là công việc lặp. Chênh lệch 10 phút so với 2 giờ mỗi vòng quyết định làm được bao nhiêu vòng trong 2 tuần.

3. **Chi phí không phải ràng buộc** ở quy mô này.

**Nếu mentor không duyệt việc dữ liệu ra ngoài** — hoàn toàn hợp lý vì đây là log production — thì phương án B vẫn khả thi, với hai điều chỉnh:
- Giảm Consistency xuống 3 lần chạy thay vì 5 (vẫn đủ phát hiện bất nhất, MVP cho phép vì đây là metric `Báo cáo`)
- Tắt Kibana khi chạy benchmark để nhường RAM cho mô hình

## 6. Thiết kế không phụ thuộc lựa chọn

Bất kể mentor duyệt phương án nào, code đã được chuẩn bị để đổi provider mà không sửa pipeline:

```java
public interface AiAnalyzer {          // service/port/ — giống SignalingSource của Sprint 1
    AiAnalysis analyze(SafeContext context);
}
```

Tầng `domain` không biết provider nào tồn tại; adapter nằm ở `infrastructure/ai/`. Đây đúng là yêu cầu *"AI Provider Abstraction"* của MVP mục 6.6, và là cùng một khuôn mẫu đã dùng cho `SignalingSource` ở Sprint 1.

Nhờ vậy nếu mentor muốn thử cả hai để so sánh thực nghiệm, chi phí chỉ là viết thêm một adapter.

## 7. Câu hỏi cần mentor trả lời

1. Dữ liệu log production đã sanitize có được phép đi qua API của bên thứ ba không? Nếu có thì có nhà cung cấp nào team đã phê duyệt sẵn không?
2. Nếu bắt buộc chạy local, em có được dùng máy nào mạnh hơn không, hay phải chạy trên máy cá nhân?
3. Team có sẵn API key dùng chung cho mục đích học tập không, hay em tự đăng ký?
