package io.hason.callanalysis.service;

import io.hason.callanalysis.domain.batch.BatchResult;
import io.hason.callanalysis.domain.batch.SourceGroup;
import io.hason.callanalysis.domain.security.Pseudonymizer;
import io.hason.callanalysis.domain.security.SensitiveDataSanitizer;
import io.hason.callanalysis.domain.signaling.SignalingFetch;
import io.hason.callanalysis.domain.taxonomy.Verdict;
import io.hason.callanalysis.domain.validation.AttachedFile;
import io.hason.callanalysis.infrastructure.security.SensitiveDataInventoryLoader;
import io.hason.callanalysis.infrastructure.taxonomy.TaxonomyLoader;
import io.hason.callanalysis.service.port.BatchResultSink;
import io.hason.callanalysis.service.port.SignalingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static io.hason.callanalysis.service.ChatPipelineFixture.CALL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phân tích hàng loạt (T10) bằng pipeline thật; chỉ thay cổng ra (signaling, danh sách Call-ID, nơi ghi). */
class BatchAnalysisServiceTest {

    private static final String OTHER = "BBBBBBBB-0000-0000-0000-000000000002";

    private static final SignalingSource HEALTHY = callId ->
            new SignalingFetch(callId, ChatPipelineFixture.healthySignaling(callId), false, 4, 4);

    private final SensitiveDataSanitizer sanitizer = new SensitiveDataSanitizer(
            new SensitiveDataInventoryLoader().load(), new Pseudonymizer(new byte[32]));

    /** Ghi lại lần ghi gần nhất. */
    private static final class RecordingSink implements BatchResultSink {
        final List<BatchResult> written = new ArrayList<>();
        Instant ranAt;

        @Override
        public void replaceAll(Instant ranAt, List<BatchResult> results) {
            this.ranAt = ranAt;
            written.clear();
            written.addAll(results);
        }
    }

    private BatchAnalysisService service(List<String> callIds, BatchResultSink sink) {
        AnalyzeCallService analyze = new AnalyzeCallService(
                new CallLogNormalizationService(HEALTHY, DataSize.ofMegabytes(20)), new TaxonomyLoader(), sanitizer);
        return new BatchAnalysisService(() -> callIds, analyze, sink, sanitizer);
    }

    /** Log máy chỉ có cho {@link ChatPipelineFixture#CALL_ID}. */
    private static final Function<String, List<AttachedFile>> LOGS = callId -> CALL_ID.equals(callId)
            ? List.of(new AttachedFile("caller_endcall.log", 100, ChatPipelineFixture.endCallLog("caller")))
            : List.of();

    @Test
    @DisplayName("Call-ID theo danh sách của nguồn signaling; cuộc có log máy và cuộc chỉ signaling được tách nhóm")
    void sourceGroupPerCall() {
        BatchAnalysisService.Batch batch = service(List.of(CALL_ID, OTHER), new RecordingSink()).run(LOGS);

        assertThat(batch.results()).extracting(BatchResult::callId).containsExactly(CALL_ID, OTHER);
        assertThat(batch.results()).extracting(BatchResult::sourceGroup)
                .containsExactly(SourceGroup.WITH_CLIENT_LOGS, SourceGroup.SIGNALING_ONLY);
        assertThat(batch.summary().all().calls()).isEqualTo(2);
        assertThat(batch.errors()).isEmpty();
    }

    @Test
    @DisplayName("chỉ signaling, signaling hoàn hảo -> UNKNOWN chứ không SUCCESS (chưa kiểm được media)")
    void signalingOnlyIsNeverSuccess() {
        BatchAnalysisService.Batch batch = service(List.of(OTHER), new RecordingSink()).run(id -> List.of());

        assertThat(batch.results()).singleElement().extracting(BatchResult::verdict).isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    @DisplayName("nơi ghi nhận Call-ID mã giả, không Call-ID gốc; báo cáo vẫn giữ Call-ID gốc")
    void sinkGetsPseudonyms() {
        RecordingSink sink = new RecordingSink();
        BatchAnalysisService.Batch batch = service(List.of(CALL_ID, OTHER), sink).run(LOGS);

        assertThat(sink.written).extracting(BatchResult::callId)
                .allSatisfy(id -> assertThat(id).startsWith("CALL_"))
                .doesNotContain(CALL_ID, OTHER)
                .containsExactly(sanitizer.pseudonymizeCallId(CALL_ID), sanitizer.pseudonymizeCallId(OTHER));
        assertThat(sink.ranAt).isEqualTo(batch.ranAt());
        assertThat(batch.sinkStatus()).startsWith("đã ghi 2 bản ghi");
    }

    @Test
    @DisplayName("ghi Elasticsearch lỗi -> lượt chạy vẫn ra kết quả, trạng thái nói rõ")
    void sinkFailureDoesNotLoseResults() {
        BatchResultSink broken = (ranAt, results) -> {
            throw new UncheckedIOException(new java.io.IOException("ES down"));
        };
        BatchAnalysisService.Batch batch = service(List.of(CALL_ID), broken).run(LOGS);

        assertThat(batch.results()).hasSize(1);
        assertThat(batch.sinkStatus()).startsWith("KHÔNG ghi được (UncheckedIOException)");
    }

    @Test
    @DisplayName("một cuộc đọc log lỗi -> bỏ cuộc đó, ghi vào errors, các cuộc khác vẫn chạy")
    void oneBrokenCallDoesNotStopTheBatch() {
        Function<String, List<AttachedFile>> flaky = callId -> {
            if (CALL_ID.equals(callId)) {
                throw new UncheckedIOException(new java.io.IOException("disk"));
            }
            return List.of();
        };
        BatchAnalysisService.Batch batch = service(List.of(CALL_ID, OTHER), new RecordingSink()).run(flaky);

        assertThat(batch.results()).extracting(BatchResult::callId).containsExactly(OTHER);
        assertThat(batch.errors()).containsExactly(CALL_ID + ": UncheckedIOException");
        assertThat(batch.summary().all().calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("không đọc được danh sách Call-ID -> ném ra: không có gì để phân tích")
    void catalogFailurePropagates() {
        AnalyzeCallService analyze = new AnalyzeCallService(
                new CallLogNormalizationService(HEALTHY, DataSize.ofMegabytes(20)), new TaxonomyLoader(), sanitizer);
        BatchAnalysisService service = new BatchAnalysisService(() -> {
            throw new UncheckedIOException(new java.io.IOException("ES down"));
        }, analyze, BatchResultSink.NONE, sanitizer);

        assertThatThrownBy(() -> service.run(id -> List.of())).isInstanceOf(UncheckedIOException.class);
    }
}
