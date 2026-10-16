/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】AgentPlugin.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】插件统一接口。skills / subagents / hooks 本质都是「给 Agent 追加能力或
 *            行为」的扩展，统一抽象便于校验、排序与枚举。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 4.1 节）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

/**
 * 插件统一接口。
 *
 * @param id 全局唯一插件 id（与 manifest 中的引用对齐）
 */
public interface AgentPlugin {

    String id();
}