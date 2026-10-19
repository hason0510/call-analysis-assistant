package io.hason.callanalysis.domain.batch;

import io.hason.callanalysis.domain.taxonomy.ConfidenceLevel;
import io.hason.callanalysis.domain.taxonomy.IssueCategory;
import io.hason.callanalysis.domain.taxonomy.Verdict;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Thống kê phân bố verdict / issue category của một lượt phân tích hàng loạt (MVP 7.1 T10).
 *
 * Tính cho cả lượt VÀ riêng từng {@link SourceGroup}: gộp chung thì "40 % UNKNOWN" lẫn hai nghĩa — thiếu log máy, và
 * có đủ log mà vẫn không kết luận được. Map khởi tạo đủ mọi giá trị enum theo thứ tự khai báo (giá trị 0 vẫn có mặt)
 * nên thứ tự dòng trong báo cáo cố định giữa các lượt.
 *
 * @param all    cả lượt
 * @param groups từng nhóm nguồn dữ liệu, theo thứ tự khai báo của {@link SourceGroup}
 */
public record BatchSummary(Group all, Map<SourceGroup, Group> groups) {

    /**
     * @param categories chỉ đếm cuộc có loại lỗi thật: FAIL, và SUCCESS gắn cờ chất lượng kém (MVP 4.2). UNKNOWN không
     *                   có loại lỗi để đếm, SUCCESS bình thường cũng không.
     */
    public record Group(int calls,
                        Map<Verdict, Integer> verdicts,
                        Map<IssueCategory, Integer> categories,
                        Map<ConfidenceLevel, Integer> confidence,
                        int qualityFlagged) {

        public int verdict(Verdict v) {
            return verdicts.get(v);
        }

        /** Tỉ lệ UNKNOWN trên số cuộc của nhóm; 0 khi nhóm rỗng. */
        public double unknownRate() {
            return calls == 0 ? 0 : (double) verdict(Verdict.UNKNOWN) / calls;
        }

        static Group of(List<BatchResult> results) {
            Map<Verdict, Integer> verdicts = zeros(Verdict.class);
            Map<IssueCategory, Integer> categories = zeros(IssueCategory.class);
            Map<ConfidenceLevel, Integer> confidence = zeros(ConfidenceLevel.class);
            int flagged = 0;
            for (BatchResult r : results) {
                verdicts.merge(r.verdict(), 1, Integer::sum);
                confidence.merge(r.confidence(), 1, Integer::sum);
                if (r.qualityFlag()) {
                    flagged++;
                }
                if (hasIssue(r)) {
                    categories.merge(r.issueCategory(), 1, Integer::sum);
                }
            }
            return new Group(results.size(), Collections.unmodifiableMap(verdicts),
                    Collections.unmodifiableMap(categories), Collections.unmodifiableMap(confidence), flagged);
        }

        private static boolean hasIssue(BatchResult r) {
            return r.issueCategory() != null
                    && (r.verdict() == Verdict.FAIL || (r.verdict() == Verdict.SUCCESS && r.qualityFlag()));
        }
    }

    public static BatchSummary of(List<BatchResult> results) {
        Map<SourceGroup, Group> groups = new EnumMap<>(SourceGroup.class);
        for (SourceGroup sourceGroup : SourceGroup.values()) {
            groups.put(sourceGroup, Group.of(results.stream().filter(r -> r.sourceGroup() == sourceGroup).toList()));
        }
        return new BatchSummary(Group.of(results), Collections.unmodifiableMap(groups));
    }

    private static <E extends Enum<E>> Map<E, Integer> zeros(Class<E> type) {
        Map<E, Integer> map = new EnumMap<>(type);
        for (E e : type.getEnumConstants()) {
            map.put(e, 0);
        }
        return map;
    }
}
