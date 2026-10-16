package io.memobservatory.exoskeleton.tasks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmChatClient} 的重试与退避：只有「瞬时失败」（HTTP 5xx / IOException）才重试，
 * 4xx（参数 / 鉴权错误）不重试。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 起一个假端点，零新增依赖。
 * 退避取 1ms，让测试不因等待而变慢。
 */
class LlmChatClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 起一个假端点，处理器按需返回状态码；每条请求进来先计数。 */
    private String start(Responder responder, AtomicInteger calls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            responder.reply(ex, n);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static final String OK_BODY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    @FunctionalInterface
    private interface Responder {
        void reply(com.sun.net.httpserver.HttpExchange ex, int call) throws IOException;
    }

    @Test
    @DisplayName("前两次 503、第三次 200：按 max-retries 重试并成功")
    void 瞬时5xx重试到成功() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> {
            if (n < 3) {
                ex.sendResponseHeaders(503, -1);
                ex.close();
            } else {
                respondJson(ex, OK_BODY);
            }
        }, calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 2, 1);
        assertEquals("ok", client.chat("s", "u"));
        assertEquals(3, calls.get(), "首次 + 2 次重试 = 3 次调用");
    }

    @Test
    @DisplayName("一直 5xx：重试配额用尽后如实抛出")
    void 服务端持续5xx重试耗尽后抛出() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        }, calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 2, 1);
        assertThrows(Exception.class, () -> client.chat("s", "u"));
        assertEquals(3, calls.get(), "首次 + 2 次重试 = 3 次调用");
    }

    @Test
    @DisplayName("4xx 是参数 / 鉴权错误：不重试，只发一次")
    void 参数错误4xx不重试() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> {
            ex.sendResponseHeaders(400, -1);
            ex.close();
        }, calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 2, 1);
        Exception e = assertThrows(Exception.class, () -> client.chat("s", "u"));
        assertEquals(1, calls.get(), "4xx 不得重试");
        assertTrue(e.getMessage().contains("400"), e.getMessage());
    }

    @Test
    @DisplayName("未配置时抛出「LLM 未配置」，不发起任何请求")
    void 未配置即不可用() {
        LlmChatClient client = new LlmChatClient("", "", "", "", 5, 2, 1);
        assertThrows(Exception.class, () -> client.chat("s", "u"));
    }

    @Test
    @DisplayName("成功调用：把响应体里的 usage 如实回执给 sink")
    void 成功回执用量() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> respondJson(ex,
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120}}"),
                calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 0, 1);
        LlmUsage[] got = new LlmUsage[1];
        boolean[] ok = new boolean[1];
        assertEquals("ok", client.chat("s", "u", (success, usage) -> {
            ok[0] = success;
            got[0] = usage;
        }));
        assertTrue(ok[0], "成功应为 ok=true");
        assertEquals(100, got[0].promptTokens());
        assertEquals(20, got[0].completionTokens());
        assertEquals(120, got[0].totalTokens());
    }

    @Test
    @DisplayName("端点未上报 usage：成功但用量为 null（不造 0）")
    void 无用量时如实为null() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> respondJson(ex, OK_BODY), calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 0, 1);
        LlmUsage[] got = new LlmUsage[]{new LlmUsage(1, 1, 1)};
        client.chat("s", "u", (success, usage) -> got[0] = usage);
        assertNull(got[0], "端点没报 usage 就得如实为空，不能造 0");
    }

    @Test
    @DisplayName("失败调用：sink 收到 ok=false，用量无意义")
    void 失败回执ok为false() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String baseUrl = start((ex, n) -> {
            ex.sendResponseHeaders(400, -1);
            ex.close();
        }, calls);

        LlmChatClient client = new LlmChatClient(baseUrl, "m", "", "", 5, 0, 1);
        boolean[] ok = new boolean[]{true};
        assertThrows(Exception.class, () -> client.chat("s", "u", (success, usage) -> ok[0] = success));
        assertTrue(!ok[0], "失败应回执 ok=false，调用方才能把「调了几次」与「烧了多少」分开算");
    }
}