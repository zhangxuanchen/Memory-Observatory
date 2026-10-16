/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】SkillContext.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】能力包装配期上下文：向 Toolkit 注册自带工具、向系统提示追加使用说明。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 4.2 节）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

import io.agentscope.core.tool.Toolkit;

/**
 * 能力包（SkillPlugin）的装配期上下文：由 AgentFactory 构造并注入给每个启用的能力包。
 *
 * @param toolkit      当前 Agent 专属 Toolkit（能力包可 {@link #register} 自带工具）
 * @param promptBuffer 能力包向系统提示追加片段的可变缓冲
 */
public record SkillContext(Toolkit toolkit, StringBuilder promptBuffer) {

    /** 向 Toolkit 注册一个自定义工具。 */
    public SkillContext register(io.agentscope.core.tool.Tool tool) {
        toolkit.registerTool(tool);
        return this;
    }

    /** 注册一个含 {@code @Tool} 方法的普通对象（如 FileTools / BashTool / WebSearchTool）。 */
    public SkillContext registerObject(Object toolObject) {
        toolkit.registerTool(toolObject);
        return this;
    }

    /** 向系统提示追加一段能力包使用说明。 */
    public SkillContext appendPrompt(String text) {
        promptBuffer.append("\n").append(text.strip());
        return this;
    }
}