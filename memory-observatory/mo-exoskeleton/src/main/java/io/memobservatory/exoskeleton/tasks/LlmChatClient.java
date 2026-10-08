package io.memobservatory.exoskeleton.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    public LlmChatClient(@Value("${mo.exoskeleton.tasks.llm.base-url:}") String baseUrl,
                         @Value("${mo.exoskeleton.tasks.llm.model:}") String model,
                         @Value("${mo.exoskeleton.tasks.llm.api-key:}") String apiKey,
                         @Value("${mo.exoskeleton.tasks.llm.reasoning-effort:}") String reasoningEffort,
                         @Value("${mo.exoskeleton.tasks.timeout-seconds:60}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
        this.model = model == null ? "" : model.strip();
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        if (isEnabled()) {
            log.info("task_type 分类器 LLM 通道就绪：baseUrl={} model={} reasoningEffort={}",
                    this.baseUrl, this.model, this.reasoningEffort.isEmpty() ? "(不发)" : this.reasoningEffort);
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
     * <p>正文为空时回落 {@code reasoning_content}：思考模型在小 max_tokens 下 {@code content}
     * 会是空串，真正的文字在 {@code reasoning_content} 里——这是本项目踩过的坑。
     *
     * @throws Exception HTTP 非 2xx 或网络异常；由调用方按单条失败处理
     */
    public String chat(String system, String user) throws Exception {
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

        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("LLM HTTP " + resp.statusCode());
        }
        JsonNode msg = mapper.readTree(resp.body()).path("choices").path(0).path("message");
        String text = msg.path("content").asText("");
        if (text.isBlank()) {
            text = msg.path("reasoning_content").asText("");
        }
        return text.strip();
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