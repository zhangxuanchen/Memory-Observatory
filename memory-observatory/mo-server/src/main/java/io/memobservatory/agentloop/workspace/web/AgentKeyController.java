/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】AgentKeyController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】模型 AK 的全局默认展示 + 有效性校验端点。
 *          · GET /api/agent/keys/defaults  返回全局默认 AK（供前端预填，未配置为 null）
 *          · POST /api/agent/keys/validate 对某个 provider 的 AK 发起 ping 校验
 * 【设计要点】校验只打一条短消息，能收到非空回复即视为 AK 正确；不发明文到日志。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workbench.core.AgentProperties;
import io.memobservatory.agentloop.workbench.core.ModelFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AK 全局默认与校验端点。全局默认来自服务端 AgentProperties（环境/兜底文件配置），
 * 前端据此在新建/编辑 Agent 时对未绑定项做默认预填。
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

    /** 全局默认 AK（明文，供前端预填；未配置为 null）。 */
    @GetMapping("/defaults")
    public Map<String, String> defaults() {
        return Map.of(
                "dashscope", props.getDashscopeApiKey(),
                "openai", props.getOpenaiApiKey());
    }

    /** 校验某个 provider 的 AK：发 "ping" 消息，收到正常回复即视为正确。 */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody ValidateKeyRequest req) {
        return modelFactory.testKey(req.provider(), req.apiKey());
    }

    public record ValidateKeyRequest(String provider, String apiKey) {
    }
}