/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】HookPlugin.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】钩子插件接口：包装成 AgentScope 2.0 官方推荐的
 *            {@code io.agentscope.core.middleware.MiddlewareBase}（取代 2.0 已废弃的 Hook），
 *            装配期按 priority 升序挂到 ReActAgent。
 * 【核心改动】2026-08-23 新增；改用 MiddlewareBase（与 MemoryReportMiddleware 同源）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 钩子插件：把一段可编程动作包装成 AgentScope 2.0 {@link MiddlewareBase}（onAgent /
 * onReasoning / onActing / onModelCall / onSystemPrompt 五点拦截）。priority 越小越先执行。
 */
public interface HookPlugin extends AgentPlugin {

    /** 装配成 AgentScope 中间件。 */
    MiddlewareBase toMiddleware();

    /** 执行优先级（升序，越小越先）。 */
    int priority();
}