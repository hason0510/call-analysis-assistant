package io.hason.callanalysis.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.hason.callanalysis.domain.ai.AiUnavailableException;
import io.hason.callanalysis.domain.ai.AiUnavailableException.Reason;
import io.hason.callanalysis.domain.ai.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mã HTTP lỗi của provider → lý do fallback, và thử lại có kiểm soát (T8). Server giả trên 127.0.0.1. */
class OpenAiChatClientTest {

    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";
    private static final RetryPolicy RETRY = new RetryPolicy(2, Duration.ofMillis(10), Duration.ofSeconds(5),
            Duration.ofMillis(100));

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Reason> retried = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Một phản hồi của server giả: mã HTTP, thân, và thời gian trễ trước khi trả. */
    private record Reply(int status, String body, long delayMs) {
        static Reply of(int status, String body) {
            return new Reply(status, body, 0);
        }
    }

    /** Server trả lần lượt các phản hồi cho trước; hết danh sách thì lặp lại phản hồi cuối. */
    private OpenAiChatClient client(RetryPolicy retry, Duration timeout, Reply... replies) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Nhiều luồng: phản hồi trễ của lần thử đầu không được chặn lần thử sau
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            Reply reply = replies[Math.min(requests.getAndIncrement(), replies.length - 1)];
            try {
                Thread.sleep(reply.delayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException clientGone) {
                // Client đã bỏ đi vì hết giờ chờ
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new OpenAiChatClient(mapper, baseUrl, "gpt-4o-mini", "sk-test", timeout, retry);
    }

    private OpenAiChatClient clientAnswering(int status, String body) throws IOException {
        return client(RetryPolicy.none(), Duration.ofSeconds(5), Reply.of(status, body));
    }

    private OpenAiChatClient.Prompt prompt() {
        return new OpenAiChatClient.Prompt("system", "user", "s", mapper.createObjectNode(),
                (reason, ms) -> retried.add(reason));
    }

    private void assertReason(OpenAiChatClient client, Reason expected) {
        assertThatThrownBy(() -> client.complete(prompt()))
                .isInstanceOfSatisfying(AiUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(expected));
    }

    @Test
    @DisplayName("tin nhắn còn dữ liệu nhạy cảm -> SENSITIVE_DATA_BLOCKED, không request nào rời máy, không thử lại")
    void sensitiveMessageIsNeverSent() throws IOException {
        OpenAiChatClient client = client(RETRY, Duration.ofSeconds(5), Reply.of(200, OK_BODY));
        OpenAiChatClient.Prompt leaking = new OpenAiChatClient.Prompt("system",
                "{\"evidence\":\"relay 203.0.113.98:3478\"}", "s", mapper.createObjectNode(),
                (reason, ms) -> retried.add(reason));

        assertThatThrownBy(() -> client.complete(leaking))
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.SENSITIVE_DATA_BLOCKED);
                    assertThat(e.getMessage()).contains("[ip]").doesNotContain("203.0.113.98");
                });
        assertThat(requests.get()).isZero();
        assertThat(retried).isEmpty();
        assertThat(client.complete(prompt()).content()).isEqualTo("{}");
    }

    @Test
    @DisplayName("429 insufficient_quota -> QUOTA_EXCEEDED: chờ không giải quyết được, phải nạp thêm")
    void quotaExceeded() throws IOException {
        assertReason(clientAnswering(429, "{\"error\":{\"message\":\"You exceeded your current quota\","
                + "\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"), Reason.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("429 rate_limit_exceeded / thân lỗi lạ -> RATE_LIMITED: thử lại sau ít phút")
    void rateLimited() throws IOException {
        assertReason(clientAnswering(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}"), Reason.RATE_LIMITED);
        stop();
        assertReason(clientAnswering(429, "Too Many Requests"), Reason.RATE_LIMITED);
    }

    @Test
    @DisplayName("T8 — 5xx là PROVIDER_ERROR; 401/403 AUTH_FAILED; 400 vượt cửa sổ ngữ cảnh INPUT_TOO_LARGE; 4xx khác REQUEST_REJECTED")
    void httpErrorsAreClassified() throws IOException {
        assertReason(clientAnswering(500, "{}"), Reason.PROVIDER_ERROR);
        stop();
        assertReason(clientAnswering(401, "{\"error\":{\"code\":\"invalid_api_key\"}}"), Reason.AUTH_FAILED);
        stop();
        assertReason(clientAnswering(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}"), Reason.INPUT_TOO_LARGE);
        stop();
        assertReason(clientAnswering(404, "{}"), Reason.REQUEST_REJECTED);
    }

    @Test
    @DisplayName("T8 — 5xx một lần rồi thành công: thử lại, có kết quả, báo đúng một lần thử lại")
    void transientErrorIsRetried() throws IOException {
        OpenAiChatClient client = client(RETRY, Duration.ofSeconds(2), Reply.of(503, "{}"), Reply.of(200, OK_BODY));

        OpenAiChatClient.Completion completion = client.complete(prompt());

        assertThat(completion.promptTokens()).isEqualTo(10);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(retried).containsExactly(Reason.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("T8 — 5xx liên tục: dừng sau maxAttempts lần, trả PROVIDER_ERROR")
    void retriesAreBounded() throws IOException {
        assertReason(client(RETRY, Duration.ofSeconds(2), Reply.of(500, "{}")), Reason.PROVIDER_ERROR);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("T8 — lỗi cố định (401, 400, 429) không thử lại: đúng một request")
    void permanentErrorsAreNotRetried() throws IOException {
        assertReason(client(RETRY, Duration.ofSeconds(2), Reply.of(401, "{}")), Reason.AUTH_FAILED);
        assertThat(requests.getAndSet(0)).isEqualTo(1);
        stop();
        assertReason(client(RETRY, Duration.ofSeconds(2),
                Reply.of(429, "{\"error\":{\"code\":\"insufficient_quota\"}}")), Reason.QUOTA_EXCEEDED);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(retried).isEmpty();
    }

    @Test
    @DisplayName("T8 — lần đầu quá giờ, lần sau trong phần ngân sách còn lại thì thành công")
    void timeoutIsRetriedWithinBudget() throws IOException {
        OpenAiChatClient client = client(RETRY, Duration.ofMillis(300),
                new Reply(200, OK_BODY, 1_500), Reply.of(200, OK_BODY));

        assertThat(client.complete(prompt()).completionTokens()).isEqualTo(2);
        assertThat(retried).containsExactly(Reason.TIMEOUT);
    }

    @Test
    @DisplayName("T8 — ngân sách gần hết sau lần quá giờ đầu: không thử nữa, trả TIMEOUT")
    void noRetryBeyondBudget() throws IOException {
        RetryPolicy tight = new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(350), Duration.ofMillis(100));

        assertReason(client(tight, Duration.ofMillis(300), new Reply(200, OK_BODY, 1_500)), Reason.TIMEOUT);
        assertThat(retried).isEmpty();
    }
}
