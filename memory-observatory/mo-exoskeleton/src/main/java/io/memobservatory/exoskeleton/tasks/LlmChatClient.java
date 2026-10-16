package io.memobservatory.exoskeleton.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 LLM 客户端：直接走 OpenAI 兼容的 {@code /chat/completions}。
 *
 * <p><b>为什么不用 agentscope</b>：mo-exoskeleton 刻意不依赖 mo-server 代码、也没有 agentscope
 * 依赖（见 pom.xml）。为一次分类调用引入跨模块的模型框架耦合不划算；用 JDK 自带的
 * {@code java.net.http.HttpClient} + Jackson（starter-web 已带）可以<b>零新增依赖</b>，
 * 形态与 mo-server 的 {@code UsageController.llmChat} 一致。
 *
 * <p><b>endpoint 必须可配</b>：mo-server 那份把它硬编码成 dashscope，本类不能照抄这一点——
 * 本模块要能指向任意 OpenAI 兼容端点（本机 Ollama 即 {@code http://localhost:11434/v1}）。
 *
 * <p><b>不可用即不启用</b>：baseUrl 或 model 为空 → {@link #isEnabled()} 为 false，调用方跳过并
 * 如实报告，不报错、不阻塞（fail-open，与 {@code SemanticFilterService} 同规矩）。
 */
@Component
public class LlmChatClient {

    private static final Logger log = LoggerFactory.getLogger(LlmChatClient.class);

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    /**
     * 关掉思考链：Qwen3 等思考模型在中长上下文下会生成数千 token 的推理链，长会话必超时。
     *
     * <p><b>为什么不是往 prompt 里塞 {@code /no_think}</b>：实测在 Ollama 的 OpenAI 兼容端点
     * （{@code /v1/chat/completions}）上，把 {@code /no_think} 追加到 system 或 user 文本里
     * <b>完全无效</b>——模型照样生成几千 token 的推理链并超时。有效的机制是请求体里的
     * {@code reasoning_effort} 字段（{@code none/low/medium/high}），{@code none} 才真正关闭思考。
     *
     * <p>空串 = 不发这个字段：非思考模型（如 gpt-4o-mini）不需要、也不一定认。
     */
    private final String reasoningEffort;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    /** 瞬时失败最多重试几次（不含首次）。 */
    private final int maxRetries;
    /** 首次重试的退避毫秒数；之后按 ×3 递增（例如默认 500 → 500ms、1500ms）。 */
    private final long retryBackoffMs;

    public LlmChatClient(@Value("${mo.exoskeleton.tasks.llm.base-url:}") String baseUrl,
                         @Value("${mo.exoskeleton.tasks.llm.model:}") String model,
                         @Value("${mo.exoskeleton.tasks.llm.api-key:}") String apiKey,
                         @Value("${mo.exoskeleton.tasks.llm.reasoning-effort:}") String reasoningEffort,
                         @Value("${mo.exoskeleton.tasks.timeout-seconds:60}") int timeoutSeconds,
                         @Value("${mo.exoskeleton.tasks.llm.max-retries:2}") int maxRetries,
                         @Value("${mo.exoskeleton.tasks.llm.retry-backoff-ms:500}") long retryBackoffMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.model = model == null ? "" : model.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxRetries = Math.max(0, maxRetries);
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        if (isEnabled()) {
            log.info("task_type 分类器 LLM 通道就绪：baseUrl={} model={} reasoningEffort={} maxRetries={} backoffMs={}",
                    this.baseUrl, this.model, this.reasoningEffort.isEmpty() ? "(不发)" : this.reasoningEffort,
                    this.maxRetries, this.retryBackoffMs);
        } else {
            log.info("task_type 分类器 LLM 未配置（{}/{} 为空），分类端点将如实报告未启用",
                    "mo.exoskeleton.tasks.llm.base-url", "mo.exoskeleton.tasks.llm.model");
        }
    }

    /** 是否可用：baseUrl 与 model 都要有。apiKey 可空（本机 Ollama 不校验）。 */
    public boolean isEnabled() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    public String model() {
        return model;
    }

    /**
     * 单次对话，返回正文文本。
     *
     * <p><b>瞬时失败会重试</b>：{@link IOException}（含连接超时 / 读超时等 {@code HttpTimeoutException}）
     * 与 HTTP 5xx 视为瞬时失败，按 {@code maxRetries} 重试、两次之间退避（首次 {@code retryBackoffMs}，
     * 之后 ×3）；HTTP 4xx（参数 / 鉴权错误）<b>不重试</b>——重试改变不了结果，只会白等。重试耗尽后
     * 抛出最后一次异常，由调用方按单条失败处理（保持 NULL、下轮重试）。
     *
     * <p>正文为空时回落 {@code reasoning_content}：思考模型在小 max_tokens 下 {@code content}
     * 会是空串，真正的文字在 {@code reasoning_content} 里——这是本项目踩过的坑。
     *
     * @throws Exception HTTP 非 2xx 或网络异常（已含重试）；由调用方按单条失败处理
     */
    public String chat(String system, String user) throws Exception {
        return chat(system, user, null);
    }

    /**
     * 单次对话，并把用量经 {@code sink} 如实回执给调用方（供按 Agent / 任务类型记账）。
     *
     * <p>{@code sink} 为 null = 不计量（流式 / 一次性脚本调用）。无论成败都会回调一次：
     * 失败时 {@code ok=false}、用量无意义——调用方据此能把「调了几次」与「烧了多少 token」分开算。
     *
     * @param sink 用量回执口；可为 null
     */
    public String chat(String system, String user, LlmUsageSink sink) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("LLM 未配置");
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        List<Map<String, Object>> msgs = new ArrayList<>(2);
        msgs.add(msg("system", system));
        msgs.add(msg("user", user));
        root.put("messages", msgs);
        root.put("temperature", 0);   // 分类要可复现，不要随机性
        if (!reasoningEffort.isEmpty()) {
            root.put("reasoning_effort", reasoningEffort);   // none = 关掉思考链，见字段注释
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(root), StandardCharsets.UTF_8)).build();

        try {
            Response resp = send(req);
            if (sink != null) {
                sink.accept(true, resp.usage());
            }
            return resp.text();
        } catch (Exception e) {
            // 失败也如实回执：调用已发出，成本真实发生，只是拿不到用量
            if (sink != null) {
                sink.accept(false, null);
            }
            throw e;
        }
    }

    /** 一次成功响应的读取结果：正文 + 用量（端点未上报 usage 时用量为 null）。 */
    private record Response(String text, LlmUsage usage) {
    }

    /** 带重试的发送：首次 + 最多 {@code maxRetries} 次重试，仅对瞬时失败重试。 */
    private Response send(HttpRequest req) throws Exception {
        // 解析刻意放在重试循环之外：重试只该解决「网络 / 服务端瞬时抖动」，
        // 响应体格式不对重试几次结果一样，不该白等退避（与 readBody 的注释一致）。
        return readBody(sendWithRetry(req));
    }

    /** 只包住「发一次请求」这一步，供 {@link #send} 重试。 */
    private String sendWithRetry(HttpRequest req) throws Exception {
        int attempts = maxRetries + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return sendOnce(req);
            } catch (LlmHttpException e) {
                // 4xx：重试改变不了结果，直接抛；5xx 重试到配额用尽再抛
                if (!e.transientFailure() || attempt >= attempts) {
                    throw e;
                }
                retryAfter(attempt, attempts, e);
            } catch (IOException e) {
                // 连接 / 读超时等（HttpTimeoutException 属 IOException）
                if (attempt >= attempts) {
                    throw e;
                }
                retryAfter(attempt, attempts, e);
            }
        }
    }

    /** 记一条重试日志并退避。 */
    private void retryAfter(int attempt, int attempts, Exception cause) throws InterruptedException {
        long wait = backoffMs(attempt);
        log.warn("LLM 瞬时失败（第 {}/{} 次），{}ms 后重试：{}", attempt, attempts, wait, cause.toString());
        sleep(wait);
    }

    /** 真正发一次请求：2xx 返回响应体，其余抛 {@link LlmHttpException}（带状态码，供上层判是否重试）。 */
    private String sendOnce(HttpRequest req) throws IOException, InterruptedException, LlmHttpException {
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new LlmHttpException(resp.statusCode());
        }
        return resp.body();
    }

    /** 解析响应体取正文与用量：解析失败属「非瞬时」，不重试，直接上抛。 */
    private Response readBody(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode msg = root.path("choices").path(0).path("message");
        String text = msg.path("content").asText("");
        if (text.isBlank()) {
            text = msg.path("reasoning_content").asText("");
        }
        // 用量如实取：端点没报 usage（或缺字段）时给 null，不造 0（0 与「不知道」是两回事）
        JsonNode u = root.path("usage");
        LlmUsage usage = u.isMissingNode() || u.isNull()
                ? null
                : new LlmUsage(u.path("prompt_tokens").asInt(0),
                        u.path("completion_tokens").asInt(0),
                        u.path("total_tokens").asInt(0));
        return new Response(text.strip(), usage);
    }

    /** 第 {@code attempt} 次失败后的退避：首次 = 基准，之后 ×3（默认 500ms、1500ms）。 */
    private long backoffMs(int attempt) {
        long wait = retryBackoffMs;
        for (int i = 1; i < attempt; i++) {
            wait *= 3;
        }
        return wait;
    }

    /** 退避等待；被中断则恢复中断标志并如实上抛（不把中断吞掉当重试）。 */
    private static void sleep(long ms) throws InterruptedException {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** LLM 返回非 2xx：带状态码，区分「5xx 可重试」与「4xx 不可重试」。 */
    private static class LlmHttpException extends Exception {
        private final int statusCode;

        LlmHttpException(int statusCode) {
            super("LLM HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        /** 5xx 视为瞬时失败，可重试；4xx 不可重试。 */
        boolean transientFailure() {
            return statusCode / 100 == 5;
        }
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** baseUrl 可能带或不带尾斜杠，统一收口。 */
    private String endpoint() {
        return (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/chat/completions";
    }
}