/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】SkillPlugin.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】能力包插件接口：按 Agent 显式启用才装配，向 ToolKit 与系统提示贡献能力。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 4.2 节）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

/**
 * 能力包插件：按 Agent {@code skills[]} 显式启用才装配。装配时经
 * {@link SkillContext} 注册自带工具 + 追加提示片段。
 */
public interface SkillPlugin extends AgentPlugin {

    /** 装配期贡献能力。 */
    void contribute(SkillContext ctx);
}