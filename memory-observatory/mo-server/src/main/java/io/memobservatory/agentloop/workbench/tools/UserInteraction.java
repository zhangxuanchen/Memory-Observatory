/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】UserInteraction.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】HITL 挂起-恢复抽象接口（依赖倒置）：AskUserTool 只依赖此接口，
 *            实现方（hitl.UserInteractionHub）再向上提供查询/回答等宿主能力。
 * 【核心改动】2026-08-23 新增此抽象以消除 tools→hitl 反向依赖。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * HITL 挂起-恢复抽象（依赖倒置）。AskUserTool 只依赖该接口，不依赖具体中枢实现，
 * 从而避免 tools 层反向依赖 hitl 包；实现方（如 {@code hitl.UserInteractionHub}）
 * 再向上提供查询/回答等宿主侧能力。sessionId 用于把问题归属到具体会话。
 */
public interface UserInteraction {

    /**
     * 登记问题并返回"用户回答时才会完成"的 Future。
     *
     * @param sessionId 当前会话
     * @param question  问题文本
     * @param options   可选答案（可空）
     * @param reason    为何向用户提问的原因/背景（可空，用于展示引导）
     * @return 用户回答时完成的 Future
     */
    CompletableFuture<String> suspend(String sessionId,
                                      String question,
                                      List<String> options,
                                      String reason);
}