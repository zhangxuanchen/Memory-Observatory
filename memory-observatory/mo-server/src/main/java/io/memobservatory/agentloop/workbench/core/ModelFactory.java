/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】ModelFactory.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】模型策略工厂（Strategy 持卡者）：按 provider 选择
 *            DashScope（Qwen）或 OpenAI 兼容（DeepSeek/GLM）模型，只暴露 Model。
 * 【核心改动】2026-08-23 从 AgentProperties 抽离模型装配职责，形成单一职责。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 模型策略工厂（Strategy 持卡者）：按 provider 选择 DashScope（Qwen）或
 * OpenAI 兼容（DeepSeek/GLM 等）具体模型实现，向上层只暴露 {@link Model}。
 * 从 {@link AgentProperties} 读取配置，职责单一、可独立测试。
 */
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final AgentProperties props;

    /** 模型窗口解析器：优先模型侧、其次内置表、可配置联动远程拉取真值。单例共享。 */
    private final ModelWindowResolver windowResolver = new ModelWindowResolver();

    public ModelFactory(AgentProperties props) {
        this.props = props;
    }

    /**
     * 取模型的输入上下文窗口（字符）。交由 {@link ModelWindowResolver} 动态解析：
     * ① 模型侧真实上报 → ② 内置模型名表 → ③ 元数据接口远程拉取 → ④ 默认窗口。
     * token 值直接作字符锚定的近似，与 CHARS_PER_TOKEN=1 的口径一致。
     *
     * @param model 已组装模型；null 时直接查属性默认模型名
     */
    public int effectiveWindow(Model model) {
        return windowResolver.resolve(model, props);
    }

    /**
     * 组装模型（按 provider 选择）。使用全局配置。
     *
     * @return 具体 {@link Model} 实现
     */
    public Model build() {
        return build(null);
    }

    /**
     * 组装模型：Agent 清单可覆盖 provider / 模型名，任一为空回落全局配置。
     *
     * @param ref Agent 清单模型引用（可为 null）
     * @return 具体 {@link Model} 实现
     */
    public Model build(io.memobservatory.agentloop.workspace.manifest.AgentManifest.ModelRef ref) {
        return build(ref, null);
    }

    /**
     * 组装模型：Agent 清单可覆盖 provider / 模型名，并可覆盖该 Agent 绑定的 AK。
     * 某 provider 的 AK 已绑定（非空）则用之，否则回落到全局默认 AK。
     *
     * @param ref Agent 清单模型引用（可为 null）
     * @param ak  该 Agent 已解密的 AK 表（provider → 明文 key）；未绑定可为 null/空
     * @return 具体 {@link Model} 实现
     */
    public Model build(io.memobservatory.agentloop.workspace.manifest.AgentManifest.ModelRef ref,
                       Map<String, String> ak) {
        String provider = (ref != null && ref.provider() != null && !ref.provider().isBlank())
                ? ref.provider() : props.getProvider();
        String dashModel = (ref != null && ref.model() != null && !ref.model().isBlank())
                ? ref.model() : props.getDashscopeModel();
        String openModel = (ref != null && ref.model() != null && !ref.model().isBlank())
                ? ref.model() : props.getOpenaiModel();
        // 该 Agent 绑定的 AK 优先，未绑定回落全局默认（界面配置 → 环境变量 → 兜底文件）
        String dashKey = pickAgentAk(ak, "dashscope", globalAk("dashscope"));
        String openaiKey = pickAgentAk(ak, "openai", globalAk("openai"));
        log.info("[ModelFactory] provider={} dashscopeKeySet={} openaiKeySet={}",
                provider,
                dashKey != null && !dashKey.isBlank(),
                openaiKey != null && !openaiKey.isBlank());
        // 抬升单次输出上限（若配置）：避免模型按默认 max_tokens 把长回复截断；未配则为 null，走模型默认。
        Integer maxOut = props.getMaxOutputTokens();
        if (maxOut != null) {
            log.info("[ModelFactory] 应用单次输出上限 maxOutputTokens={}", maxOut);
        }
        return switch (provider) {
            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(dashKey)
                    .modelName(dashModel)
                    .stream(true)
                    .defaultOptions(withMaxTokens(maxOut))
                    .build();
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(openaiKey)
                    .modelName(openModel)
                    .baseUrl(props.getOpenaiBaseUrl())
                    .stream(true)
                    .generateOptions(withMaxTokens(maxOut))
                    .build();
            default -> throw new IllegalArgumentException(
                    "unknown provider: " + provider);
        };
    }

    /** 取某 provider 的绑定 AK；未绑定（null/空/无该键）回落全局默认。 */
    private static String pickAgentAk(Map<String, String> ak, String provider, String global) {
        if (ak != null) {
            String bound = ak.get(provider);
            if (bound != null && !bound.isBlank()) return bound;
        }
        return global;
    }

    /** 全局默认 AK：界面配置（GlobalKeyStore）优先，其次环境变量/兜底文件（props）。 */
    private String globalAk(String provider) {
        String ui = GlobalKeyStore.plain(provider);
        if (ui != null && !ui.isBlank()) return ui;
        return "openai".equals(provider) ? props.getOpenaiApiKey() : props.getDashscopeApiKey();
    }

    /**
     * 校验指定 provider 的 AK 是否可用：发一条 "ping" 消息，能正常收到非空回复即视为正确。
     * 仅用于创建/编辑 Agent 时的密钥有效性校验；异常/空回复返回 false。
     *
     * @return {ok:boolean, message:String}
     */
    public Map<String, Object> testKey(String provider, String apiKey) {
        if (provider == null || apiKey == null || apiKey.isBlank()) {
            return Map.of("ok", false, "message", "密钥为空");
        }
        try {
            Model model = buildForProvider(provider, apiKey);
            Msg user = Msg.builder().textContent("ping").build();
            String reply = model.stream(List.of(user), null, null)
                    .timeout(Duration.ofSeconds(20))
                    .map(ModelFactory::textOf)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce("", (a, b) -> a + b)
                    .block(Duration.ofSeconds(25));
            if (reply == null || reply.isBlank()) {
                return Map.of("ok", false, "message", "响应为空，AK 可能无效或余额不足");
            }
            return Map.of("ok", true, "message", "AK 有效，回复正常");
        } catch (Exception e) {
            String m = e.getMessage(); if (m == null) m = e.toString();
            if (m.length() > 160) m = m.substring(0, 160);
            return Map.of("ok", false, "message", "调用失败：" + m);
        }
    }

    /** 用指定 provider 与 AK（其余用全局配置的模型名/baseUrl）构造一个校验用模型实例。 */
    private Model buildForProvider(String provider, String apiKey) {
        Integer maxOut = props.getMaxOutputTokens();
        return switch (provider) {
            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(props.getDashscopeModel())
                    .stream(true)
                    .defaultOptions(withMaxTokens(maxOut))
                    .build();
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(props.getOpenaiModel())
                    .baseUrl(props.getOpenaiBaseUrl())
                    .stream(true)
                    .generateOptions(withMaxTokens(maxOut))
                    .build();
            default -> throw new IllegalArgumentException("unknown provider: " + provider);
        };
    }

    private static String textOf(ChatResponse r) {
        if (r.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.getContent()) {
            if (b instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }

    /** 组装输出上限的 GenerateOptions；未配置输出上限时返回 null（不覆盖模型默认）。 */
    private static io.agentscope.core.model.GenerateOptions withMaxTokens(Integer maxTokens) {
        if (maxTokens == null) {
            return null;
        }
        return io.agentscope.core.model.GenerateOptions.builder().maxTokens(maxTokens).build();
    }
}