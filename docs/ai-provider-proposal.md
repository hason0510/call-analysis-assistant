# AI Provider Proposal

> **Cần mentor duyệt trước khi bắt đầu Sprint 2** (MVP mục 1.4, 5.1 — T10).
> Người đề xuất: Bế Nguyễn Hà Sơn · Ngày: 2026-09-23 · Sửa lại: 2026-09-25

---

## 1. AI được dùng để làm gì

Phạm vi rất hẹp, và đó là điểm quan trọng nhất khi chọn nhà cung cấp.

Theo MVP mục 3.2, **phần nào tính được bằng code thì không giao cho AI**. Sprint 1 đã chứng minh điều này khả thi: toàn bộ chỉ số, tín hiệu verdict và evidence đều do code tính, đạt **Verdict Accuracy 13/13 trên data có nhãn** mà chưa cần AI.

AI chỉ làm đúng hai việc:

| Việc | Đầu vào | Đầu ra |
|---|---|---|
| Hiểu câu hỏi tiếng Việt | Câu người dùng gõ | `intent` + `focus` |
| Viết phân tích và đề xuất | Timeline + evidence + chỉ số + taxonomy (đã sanitize) | Các field theo JSON schema |

**Không** giao cho AI: tính chỉ số, quyết định verdict cuối cùng, dựng bố cục report.

Hệ quả khi chọn provider: **năng lực suy luận không phải tiêu chí quyết định**. Thứ quyết định là **structured output** — mô hình có ép được đúng JSON schema không.

## 2. Quy mô đầu vào và số request

**Đo được từ Sprint 1:**

| | Số liệu |
|---|---|
| Dữ liệu gốc | Toàn bộ 20 cuộc gọi sinh 28 522 sự kiện |
| Evidence đưa vào AI mỗi cuộc | 3–22 dòng (đo trên 20 report) |
| Chỉ số mỗi cuộc | 25 |
| Taxonomy | 6 category |

AI chỉ nhận evidence, chỉ số và taxonomy đã chuẩn hoá, **không nhận raw log** (MVP mục 3.3). Nhờ vậy đầu vào mỗi request nhỏ hơn nhiều so với log gốc.

**Chưa đo được: số token mỗi request.** Số token phụ thuộc tokenizer của từng mô hình, và tiếng Việt có dấu thường tốn token hơn tiếng Anh. Em sẽ đo bằng số liệu `usage` mà API trả về ngay ở những request đầu tiên của Sprint 2.

**Số request của một vòng benchmark** — tính từ yêu cầu của MVP mục 6.3 và 6.5 (Consistency: 5 lần chạy × 3 cách hỏi):

```
13 cuộc gọi dev × 3 cách hỏi × 5 lần chạy  = 195 request
+ 5 câu hỏi OUT_OF_SCOPE × 5 lần            =  25 request
                                    một vòng ≈ 220 request
```

Số vòng phải chạy lại khi tinh chỉnh prompt thì chưa biết trước.

## 3. Hai phương án

### Phương án A — Cloud API có structured output

**Dữ liệu rời khỏi máy:** timeline, evidence, chỉ số và taxonomy **sau khi sanitize**. Cụ thể là:
- Tên lệnh signaling, mốc thời gian tương đối, số đo chất lượng — đều `INTERNAL`, policy `ALLOW`
- **Không** gửi: IP, chuỗi ICE candidate, `ufrag`/`pwd`, `appUserId` gốc, tên service
- `appUserId` và `sessionId` được pseudonymize trước khi gửi (giữ tính nhất quán để correlate)

Rủi ro còn lại: dữ liệu production của team — dù đã xử lý — vẫn đi qua hạ tầng bên thứ ba. **Đây là điểm cần mentor quyết định, không phải điểm em tự quyết.**

**Chi phí:** tính theo token, nên chưa ước lượng được con số cụ thể cho tới khi đo token ở Sprint 2. Với đầu vào đã lọc như mục 2, em kỳ vọng không phải yếu tố ràng buộc, nhưng sẽ báo lại số thật.

**Latency:** chưa đo. Sẽ đo P50/P95 theo MVP mục 6.5.

**Structured output:** nhiều API hỗ trợ ép JSON Schema ở phía server, nên giảm mạnh rủi ro ca kiểm thử G02 ("AI response sai format"). Guardrails vẫn cần kiểm, vì ép schema không đảm bảo nội dung đúng.

### Phương án B — Local model qua Ollama

**Dữ liệu rời khỏi máy: không có gì.** Đây là ưu thế tuyệt đối và giải quyết dứt điểm mọi lo ngại về việc log production rời khỏi máy cá nhân.

**Chi phí:** 0 đồng.

**Tài nguyên máy (đo ngày 2026-09-25):** 15,7 GB RAM, trong đó Docker được cấp 7,6 GB và Elasticsearch chiếm 2 GB heap. Máy có GPU rời NVIDIA GeForce RTX 4050 Laptop, **6 GB VRAM** (`nvidia-smi`), nên mô hình cỡ nhỏ đã lượng tử hoá có thể chạy trên GPU thay vì CPU. Giới hạn 6 GB VRAM quyết định cỡ mô hình chạy được.

**Latency:** chưa đo. Em sẽ đo trên máy thật với mô hình vừa 6 GB VRAM trước khi chốt số lần chạy benchmark.

**Structured output:** Ollama có tham số `format` nhận JSON Schema, nhưng mức độ tuân thủ phụ thuộc mô hình. Guardrails có thể phải reject và retry nhiều hơn.

## 4. So sánh theo 4 tiêu chí MVP mục 5.1

| Tiêu chí | A — Cloud API | B — Local (Ollama) |
|---|---|---|
| **Dữ liệu gửi sang AI sau sanitize** | Evidence + chỉ số + taxonomy đã sanitize. Không có IP, credential, user ID gốc | **Không gì rời khỏi máy** |
| **Chi phí** | Theo token — sẽ đo ở Sprint 2 | 0 |
| **Latency** | Chưa đo | Chưa đo; chạy được trên GPU 6 GB VRAM, cỡ mô hình bị giới hạn |
| **Structured output** | Ép JSON Schema phía server | Có hỗ trợ, mức tuân thủ tuỳ mô hình |

## 5. Đề xuất

**Chọn phương án A (cloud API có structured output), với điều kiện mentor chấp thuận việc dữ liệu đã sanitize đi ra ngoài.**

Lý do chính: **structured output phía server giảm cả một nhóm lỗi.** MVP có riêng ca kiểm thử G02 cho "AI response sai format"; ràng buộc schema ở phía server xử lý phần lớn ca này thay vì phải retry. Ngoài ra 6 GB VRAM chỉ đủ cho mô hình cỡ nhỏ, mà mô hình nhỏ thường tuân thủ schema kém hơn — điều này em sẽ kiểm bằng thực nghiệm nếu chọn B.

Chi phí và latency của cả hai phương án em sẽ đo ở đầu Sprint 2 và báo lại, không dùng số ước lượng để quyết định.

**Nếu mentor không duyệt việc dữ liệu ra ngoài** — hoàn toàn hợp lý vì đây là log production — thì dùng phương án B. Khi đó em đo latency trên máy thật trước, rồi mới chốt số lần chạy cho benchmark.

## 6. Thiết kế không phụ thuộc lựa chọn

Sprint 2 em sẽ tách AI qua một interface, cùng khuôn mẫu với cổng `SignalingSource` đã dùng cho Elasticsearch ở Sprint 1:

```java
public interface AiAnalyzer {          // service/port/ — giống SignalingSource của Sprint 1
    AiAnalysis analyze(SafeContext context);
}
```

Tầng `domain` không biết provider nào tồn tại; adapter nằm ở `infrastructure/ai/`. Đây đúng là yêu cầu *"AI Provider Abstraction"* của MVP mục 6.6.

Nhờ vậy nếu mentor muốn thử cả hai để so sánh thực nghiệm, chi phí chỉ là viết thêm một adapter.
