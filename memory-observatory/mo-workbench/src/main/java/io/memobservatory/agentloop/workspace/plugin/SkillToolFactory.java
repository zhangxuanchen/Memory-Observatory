/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】SkillToolFactory.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】能力包工具工厂抽象：把 AgentFactory 手里的工具实例（FileTools/BashTool/
 *            WebSearchTool，需注入 bash 超时 / Tavily Key）延迟交给动态能力包装配，
 *            避免 plugin 包反向依赖 workbench.tools 与 AgentProperties。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

/**
 * 动态能力包（YamlSkillPlugin）装配其 tools[] 时用到的内置工具工厂。
 * 由 AgentFactory 提供实现（持有 AgentProperties 的 bash 超时与 Tavily Key）。
 */
public interface SkillToolFactory {

    /** 文件读写三件套（read_file / write_file / edit_file）。 */
    Object fileTools();

    /** shell 逃生舱（bash）。 */
    Object bashTool();

    /** 联网搜索（web_search）。 */
    Object webSearchTool();
}