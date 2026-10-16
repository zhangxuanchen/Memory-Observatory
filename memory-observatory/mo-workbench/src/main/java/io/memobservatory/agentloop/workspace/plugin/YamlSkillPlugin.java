/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】YamlSkillPlugin.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】动态能力包插件：由一个用户 yaml（UserSkill）驱动的 SkillPlugin。
 *            装配时把能力的名称/描述/提示片段追加进系统提示，并按 tools[] 注册
 *            FileTools / BashTool / WebSearchTool（经 SkillToolFactory 取实例）。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

import io.memobservatory.agentloop.workspace.UserSkill;
import io.memobservatory.agentloop.workspace.mcp.McpRegistry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * yaml 驱动的能力包：让 Agent 真正「使用」用户配置的 Skill——既有提示片段注入，
 * 也能注册对应工具，而非纯文件占位。工具归属同一套内置工具，不引入新实现。
 * tools[] 支持 mcp:{serverId}/{toolName}：把外部 MCP server 的单个工具挂到本 Agent。
 */
public class YamlSkillPlugin implements SkillPlugin {

    private final UserSkill skill;
    private final SkillToolFactory toolFactory;
    private final McpRegistry mcpRegistry;

    public YamlSkillPlugin(UserSkill skill, SkillToolFactory toolFactory, McpRegistry mcpRegistry) {
        this.skill = skill;
        this.toolFactory = toolFactory;
        this.mcpRegistry = mcpRegistry;
    }

    /** 插件 id 即能力包 id（与 manifest.skills[] 中的引用对齐）。 */
    @Override
    public String id() {
        return skill.id() == null ? "" : skill.id();
    }

    @Override
    public void contribute(SkillContext ctx) {
        // 1. 提示片段：名称 / 描述 / 自定义 prompt 追加进系统提示「附加能力」
        StringBuilder sb = new StringBuilder("能力包「").append(skill.name()).append("」");
        if (skill.description() != null && !skill.description().isBlank()) {
            sb.append("\n").append(skill.description().strip());
        }
        if (skill.prompt() != null && !skill.prompt().isBlank()) {
            sb.append("\n").append(skill.prompt().strip());
        }
        ctx.appendPrompt(sb.toString());

        // 2. 按 tools[] 注册对应内置工具（去重；未知工具 id 忽略）
        Set<String> kinds = new LinkedHashSet<>();
        for (String t : skill.tools() == null ? List.<String>of() : skill.tools()) {
            if (t != null && t.startsWith("mcp:")) {
                registerMcpTool(ctx, t.substring(4).strip());
                continue;
            }
            switch (t == null ? "" : t) {
                case "readFile", "writeFile", "editFile", "file" -> kinds.add("file");
                case "bash", "shell" -> kinds.add("bash");
                case "webSearch", "web_search" -> kinds.add("webSearch");
                default -> {
                    // 不认识的工具 id 静默跳过，避免装配期失败
                }
            }
        }
        if (kinds.contains("file")) {
            ctx.registerObject(toolFactory.fileTools());
        }
        if (kinds.contains("bash")) {
            ctx.registerObject(toolFactory.bashTool());
        }
        if (kinds.contains("webSearch")) {
            ctx.registerObject(toolFactory.webSearchTool());
        }
    }

    /** 注册外部 MCP 工具（格式 serverId/toolName）。失败不阻断装配，只在提示里留痕。 */
    private void registerMcpTool(SkillContext ctx, String ref) {
        int slash = ref.indexOf('/');
        if (slash <= 0 || slash == ref.length() - 1) {
            ctx.appendPrompt("（提示：技能引用的 MCP 工具格式无效：" + ref + "，应为 mcp:serverId/toolName）");
            return;
        }
        String serverId = ref.substring(0, slash);
        String toolName = ref.substring(slash + 1);
        if (mcpRegistry == null) {
            ctx.appendPrompt("（提示：MCP 接入未就绪，工具 " + ref + " 不可用）");
            return;
        }
        try {
            // McpTool 实现了 AgentTool，走 Toolkit 的专用注册入口（等价 registerTool 的 AgentTool 分支）
            ctx.toolkit().registerAgentTool(mcpRegistry.toolFor(serverId, toolName));
        } catch (Exception e) {
            ctx.appendPrompt("（提示：MCP 工具 " + ref + " 当前不可用：" + e.getMessage() + "）");
        }
    }
}