/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】AgentKeyController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】模型 AK 的全局默认展示 + 有效性校验 + 全局 AK 界面配置端点。
 *          · GET  /api/agent/keys/defaults  返回全局默认 AK 明文（供前端 Agent 表单预填，未配置为 null）
 *          · POST /api/agent/keys/validate  对某个 provider 的 AK 发起 ping 校验
 *          · GET  /api/agent/keys/global    全局 AK 掩码态（是否配置 + 来源），不回传明文
 *          · PUT  /api/agent/keys/global    保存/清除界面配置的全局 AK（加密落盘，立即生效）
 * 【设计要点】校验只打一条短消息，能收到非空回复即视为 AK 正确；不发明文到日志。
 *          全局取钥优先级：界面配置（GlobalKeyStore）→ 环境变量 → agent.env 兜底文件。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workbench.core.AgentProperties;
import io.memobservatory.agentloop.workbench.core.GlobalKeyStore;
import io.memobservatory.agentloop.workbench.core.ModelFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AK 全局默认与校验端点。全局默认优先取界面配置（GlobalKeyStore），回落服务端
 * AgentProperties（环境/兜底文件配置），前端据此在新建/编辑 Agent 时对未绑定项做默认预填。
 */
@RestController
@RequestMapping("/api/agent/keys")
public class AgentKeyController {

    private final AgentProperties props;
    private final ModelFactory modelFactory;

    public AgentKeyController(AgentProperties props, ModelFactory modelFactory) {
        this.props = props;
        this.modelFactory = modelFactory;
    }

    /** 全局默认 AK 明文（供前端预填；未配置为 null）。界面配置优先于环境变量。 */
    @GetMapping("/defaults")
    public Map<String, String> defaults() {
        return Map.of(
                "dashscope", globalAk("dashscope"),
                "openai", globalAk("openai"));
    }

    /** 校验某个 provider 的 AK：发 "ping" 消息，收到正常回复即视为正确。 */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ValidateKeyRequest req) {
        return modelFactory.testKey(req.provider(), req.apiKey());
    }

    /** 全局 AK 掩码态：provider → {masked, source}（source="ui" 界面配置 / "env" 环境变量 / null 未配置）。 */
    @GetMapping("/global")
    public Map<String, Object> globalStatus() {
        Map<String, Object> st = GlobalKeyStore.status();
        for (Map.Entry<String, Object> e : st.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) e.getValue();
            if (node.get("masked") != null) continue; // 界面已配置，优先展示
            String envKey = "openai".equals(e.getKey())
                    ? props.getOpenaiApiKey() : props.getDashscopeApiKey();
            if (envKey != null && !envKey.isBlank()) {
                node.put("masked", GlobalKeyStore.mask(envKey));
                node.put("source", "env");
            }
        }
        return st;
    }

    /**
     * 保存/清除界面配置的全局 AK（加密落盘 ~/.workbench/model-keys.json，立即生效）。
     * body：{provider: "dashscope"|"openai", apiKey: 非空=保存覆盖 / 空串=清除并回落环境变量}
     */
    @PutMapping("/global")
    public Map<String, Object> updateGlobal(@RequestBody UpdateGlobalKeyRequest req) {
        return GlobalKeyStore.apply(req.provider(), req.apiKey());
    }

    /** 全局默认取钥：界面配置优先，其次环境变量/兜底文件（props）。 */
    private String globalAk(String provider) {
        String ui = GlobalKeyStore.plain(provider);
        if (ui != null && !ui.isBlank()) return ui;
        return "openai".equals(provider) ? props.getOpenaiApiKey() : props.getDashscopeApiKey();
    }

    public record ValidateKeyRequest(String provider, String apiKey) {
    }

    public record UpdateGlobalKeyRequest(String provider, String apiKey) {
    }
}
