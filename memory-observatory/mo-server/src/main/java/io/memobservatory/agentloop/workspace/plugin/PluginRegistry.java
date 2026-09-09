/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】PluginRegistry.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】插件注册表：按 manifest 里的 type/id 解析对应插件实现。
 *            skills 与 hooks（MiddlewareBase）都是「给 Agent 追加能力或行为」的扩展，
 *            统一收敛在 {@link AgentPlugin} 抽象之下。未知 id 返回 null，装配期告警并跳过。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 4.1 节）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

import io.memobservatory.agentloop.workspace.plugin.AuditMiddleware.AuditPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件注册表：内置插件集中登记，按 type/id 提供插件实例。实例均为无状态单例，可并发复用。
 * 当前内置：hook「audit」（工具调用审计）。skills 与 subagents 为可扩展空位。
 */
public class PluginRegistry {

    private final Map<String, AgentPlugin> hooks = new LinkedHashMap<>();
    private final Map<String, AgentPlugin> skills = new LinkedHashMap<>();

    public PluginRegistry() {
        hooks.put("audit", new AuditPlugin());
        // skills 空位：后续按需扩展能力包
    }

    /** 解析钩子插件；未知 type 返回 null。 */
    public HookPlugin hook(String type) {
        AgentPlugin p = hooks.get(type);
        return p instanceof HookPlugin h ? h : null;
    }

    /** 解析能力包插件；未知 id 返回 null。 */
    public SkillPlugin skill(String id) {
        AgentPlugin p = skills.get(id);
        return p instanceof SkillPlugin s ? s : null;
    }
}