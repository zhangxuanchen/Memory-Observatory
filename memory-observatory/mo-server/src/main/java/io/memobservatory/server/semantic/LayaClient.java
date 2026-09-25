package io.memobservatory.server.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * laya 推理后端的 HTTP 客户端。
 *
 * <p>只走项目自带后端（{@code laya-backend/server.py}）暴露的 {@code /api/predict}，
 * 且显式传 {@code model=auto}：原版后端还有个 {@code /v1/predict}，其 model 缺省值是硬编码的
 * {@code "english"}，非拉丁文本会被送进英文 checkpoint——而英文 checkpoint 读非拉丁脚本会崩到
 * 接近随机却仍报高置信（Khmer 0.000 准确率 @ 0.952 置信）。本项目后端已去掉那个端点，
 * 但 {@code model=auto} 仍必须显式传，否则退化到该 checkpoint 的默认行为。
 */
@Slf4j
@Component
public class LayaClient {

    private final SemanticProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public LayaClient(SemanticProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** 后端是否可达（用于就绪等待与健康探测）。 */
    public boolean healthy() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/health"))
                    .timeout(Duration.ofMillis(1000))
                    .GET()
                    .build();
            return http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 跑一次判定，返回后端原始 JSON（含 answers / routing / latency_ms）。
     *
     * @param state     状态对象，键名需与 questions 的 instructions 中反引号引用的字段一致
     * @param questions 问题定义（choice / score / noul）
     * @param langHint  显式语言族；{@code null} 表示交回 Router 自动探测。
     *                  取值与理由见 {@link ConfirmKind#langHint()}——关键是<b>按「被判内容本身
     *                  是什么语言」决定，而不是按「状态里有没有中文」</b>。
     */
    public JsonNode predict(Map<String, Object> state, Map<String, Object> questions, String langHint)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("questions", questions);
        body.put("model", "auto");   // 显式 auto：由后端 Router 决策，勿删
        // lang 优先级高于 Router 的脚本探测，用于绕开「中英混排被判成 latin」的误判。
        // 只在调用方明确要求时声明：PROBLEM / LOOP 的中文叙述必须声明，RISK 的代码片段不能声明。
        if (langHint != null && containsCjk(state)) {
            body.put("lang", langHint);
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/predict"))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body), java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(
                java.nio.charset.StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("laya http " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readTree(resp.body());
    }

    /** 不带语言提示（等价于 {@code predict(state, questions, null)}）。 */
    public JsonNode predict(Map<String, Object> state, Map<String, Object> questions) throws Exception {
        return predict(state, questions, null);
    }

    /** state 的文本值里是否含 CJK 表意文字（配合调用方给出的 {@code langHint} 使用）。 */
    private static boolean containsCjk(Map<String, Object> state) {
        for (Object v : state.values()) {
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v);
            for (int i = 0; i < s.length(); i++) {
                if (isCjk(s.charAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** CJK 统一表意文字：基本区 U+4E00–U+9FFF、扩展 A U+3400–U+4DBF、兼容区 U+F900–U+FAFF。 */
    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\uF900' && c <= '\uFAFF');
    }

    /** 解析 {@code answers.<qid>.noul}（P(true)）。取不到时返回 0.5（不确定 → 不触发剔除）。 */
    public static double noul(JsonNode result, String qid) {
        JsonNode v = result.path("answers").path(qid).path("noul");
        return v.isNumber() ? v.asDouble() : 0.5;
    }

    /**
     * 解析 {@code answers.<qid>.choice}（多分类标签，取概率最大的选项 key）。
     *
     * <p>后端返回形如 {@code {"type":"choice","choice":"pathological_loop",
     * "probabilities":{...},"confidence":0.71}}。取不到时返回空串——
     * <b>不要</b>用 {@code score} 原语，它是 laya 最弱的（SST-5 0.372）。
     */
    public static String choice(JsonNode result, String qid) {
        JsonNode v = result.path("answers").path(qid).path("choice");
        return v.isTextual() ? v.asText() : "";
    }

    /** 解析 {@code answers.<qid>.confidence}（分类置信度）。取不到时返回 0。 */
    public static double confidence(JsonNode result, String qid) {
        JsonNode v = result.path("answers").path(qid).path("confidence");
        return v.isNumber() ? v.asDouble() : 0.0;
    }

    /** 解析实际路由到的 checkpoint（审计用）。 */
    public static String routedModel(JsonNode result) {
        return result.path("routing").path("model").asText("");
    }
}
