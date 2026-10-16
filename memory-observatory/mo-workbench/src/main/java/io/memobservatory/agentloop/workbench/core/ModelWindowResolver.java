/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】ModelWindowResolver.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】运行时解析模型输入上下文窗口（动态，尽量取真值）。解析优先级：
 *  ① 模型侧真实上报 model.getContextWindowSize() > 0；
 *  ② 配置映射表 mo.agent.model-windows（默认统一 1M，可按实配覆盖）；
 *  ③ 配置了元数据接口（MO_AGENT_MODEL_META_URL / openai 兼容 /models）时，运行时
 *     HTTP 拉取该模型对应条目的 context 字段，命中即为真值；
 *  ④ 全未命中回落 ConversationMemory.ContextBudget.DEFAULT_WINDOW。
 * 【设计要点】单例、结果按 (provider|model) 缓存；网络拉取尽力而为、带超时，失败仅 WARN
 *            绝不阻断装配；拉取仅针对映射表未命中的模型，避免多余请求。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelWindowResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelWindowResolver.class);

    /** 元数据条目里可能承载上下文长度的字段名（各厂商命名不一，逐一探测）。 */
    private static final String[] CONTEXT_FIELDS = {
            "context_window", "context_length", "max_input_tokens",
            "max_context_length", "max_model_len", "context_size", "maxTokens"};

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Integer> cache = new ConcurrentHashMap<>();

    /** 解析窗口：优先真值，其次映射表，最后可配置联动的远程拉取，兜底默认窗口。 */
    public int resolve(Model model, AgentProperties props) {
        // ① 模型侧真实上报
        if (model != null && model.getContextWindowSize() > 0) {
            return model.getContextWindowSize();
        }
        String provider = props.getProvider();
        String modelName = model != null && model.getModelName() != null
                ? model.getModelName() : null;
        String key = provider + "|" + (modelName == null ? "" : modelName.toLowerCase());
        // ② 映射表命中即返回（含缓存写回）
        Integer known = knownWindow(provider, modelName, props);
        if (known != null) {
            cache.put(key, known);
            return known;
        }
        // 缓存命中（含负数=已尝试拉取失败）
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        // ③ 运行时拉取真值（映射表未命中的模型才尝试，避免多余网络请求）
        int remote = fetchRemote(provider, modelName, props);
        if (remote <= 0) {
            remote = (int) ConversationMemory.ContextBudget.DEFAULT_WINDOW;
        }
        cache.put(key, remote);
        return remote;
    }

    /** 按 provider + 候选模型名查配置映射表（mo.agent.model-windows，键小写）；未命中 null。 */
    private static Integer knownWindow(String provider, String modelName, AgentProperties props) {
        Map<String, Integer> table = props.getModelWindows();
        if (table == null || table.isEmpty()) {
            return null;
        }
        if (modelName != null && !modelName.isBlank()) {
            Integer w = table.get(modelName.toLowerCase().trim());
            if (w != null) {
                return w;
            }
        }
        // 属性默认模型名兜底（未配具体 Agent 时）
        String[] cands = "dashscope".equals(provider)
                ? new String[]{props.getDashscopeModel()}
                : new String[]{props.getOpenaiModel(), props.getDashscopeModel()};
        for (String c : cands) {
            if (c == null) continue;
            Integer w = table.get(c.toLowerCase().trim());
            if (w != null) return w;
        }
        return null;
    }

    /** 尽力拉取某模型条目的 context 字段；命中返回窗口 token 数，否则 0。失败仅 WARN。 */
    private int fetchRemote(String provider, String modelName, AgentProperties props) {
        try {
            String url = metadataUrl(provider, props);
            if (url == null || url.isBlank()) {
                return 0; // 未配置/无对应接口，跳过拉取
            }
            String apiKey = "openai".equals(provider)
                    ? props.getOpenaiApiKey() : props.getDashscopeApiKey();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            HttpResponse<String> resp = http.send(rb.GET().build(), HttpResponse.BodyHandlers.ofString());
            int w = parseContext(resp.statusCode(), resp.body(), modelName);
            if (w > 0) {
                log.info("[ModelWindowResolver] 从 {} 拉取到模型 {} 窗口 = {}", url, modelName, w);
            }
            return w;
        } catch (Exception e) {
            log.warn("[ModelWindowResolver] 拉取模型窗口失败（回落默认，不阻断）: {}", e.toString());
            return 0;
        }
    }

    /** 决定元数据接口地址：显式配置优先，openai 兼容回落 {baseUrl}/models。 */
    private static String metadataUrl(String provider, AgentProperties props) {
        String explicit = props.getModelMetaUrl();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if ("openai".equals(provider)) {
            String base = props.getOpenaiBaseUrl();
            if (base == null || base.isBlank()) return null;
            return base.replaceAll("/+$", "") + "/models";
        }
        return null;
    }

    /** 解析元数据响应：优先精确匹配模型条目的 context 字段；未命中扫描所有条目取最大值。 */
    private int parseContext(int status, String body, String modelName) throws Exception {
        if (status != 200 || body == null || body.isBlank()) {
            return 0;
        }
        JsonNode root = mapper.readTree(body);
        JsonNode data = root.path("data");
        // 结构一：{"data":[{...model entry...}, ...]}
        if (data.isArray()) {
            JsonNode matched = null;
            int best = 0;
            for (JsonNode entry : data) {
                String id = entry.path("id").asText();
                int w = contextOf(entry);
                if (w > best) best = w;
                if (modelName != null && modelName.equalsIgnoreCase(id)) {
                    matched = entry;
                }
            }
            if (matched != null) {
                int w = contextOf(matched);
                if (w > 0) return w;
            }
            return best;
        }
        // 结构二：平铺 {"context_window":..., "max_input_tokens":...}（单模型元数据）
        return contextOf(root);
    }

    /** 从单个模型条目/对象提取 context 字段整数值；无命中 0。 */
    private int contextOf(JsonNode node) {
        if (node == null) return 0;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            for (String f : CONTEXT_FIELDS) {
                if (e.getKey().equalsIgnoreCase(f)) {
                    return e.getValue().asInt(0);
                }
            }
        }
        return 0;
    }
}