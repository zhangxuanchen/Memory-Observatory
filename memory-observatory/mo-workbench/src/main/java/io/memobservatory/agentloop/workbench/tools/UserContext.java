/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】UserContext.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】工具执行上下文 record：承载 userId / sessionId / workspaceRoot，
 *            经 ToolExecutionContext 按类型注入工具方法；LLM 不可见、不可伪造。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import java.nio.file.Path;

/**
 * 工具执行上下文：通过 ToolExecutionContext 按类型自动注入工具方法参数。
 * LLM 完全不可见，无法通过提示注入伪造身份或越权访问其它工作区。
 *
 * @param userId        用户唯一标识
 * @param sessionId     会话唯一标识
 * @param workspaceRoot 该用户的工作区根目录（文件/命令类工具的越界边界）
 */
public record UserContext(String userId, String sessionId, Path workspaceRoot) {
}