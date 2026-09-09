/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】AskUserTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】HITL 挂起工具：返回"用户回答前不完成"的 Mono<ToolResultBlock>，
 *            从而挂起 Agent 的 Acting 阶段，直到宿主提交回答；
 *            超时由 onErrorResume 兜底为最佳方案。
 * 【核心改动】2026-08-23 依赖倒置到 UserInteraction 接口，tools 层不再反向依赖 hitl。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * HITL 挂起工具：返回一个"在用户回答前不完成的 Mono<ToolResultBlock>"，
 * 从而把 ReAct 循环的 Acting 阶段挂起，直到宿主收到回答后 complete 它。
 */
public class AskUserTool {

    private final UserInteraction interactionHub;
    private final String sessionId;

    public AskUserTool(UserInteraction interactionHub, String sessionId) {
        this.interactionHub = interactionHub;
        this.sessionId = sessionId;
    }

    @Tool(name = "ask_user",
            description = "向用户提出一个澄清问题并等待回答。当任务指令有歧义、"
                    + "存在多个合理方案、或即将执行有风险操作需要确认时使用。"
                    + "不要在不必要的时候使用——能合理推断就先推断。")
    public Mono<ToolResultBlock> askUser(
            @ToolParam(name = "question", description = "要问的问题，一句话说清楚")
            String question,
            @ToolParam(name = "options", required = false,
                    description = "可选答案列表（2-4 个）；开放式问题可省略")
            List<String> options,
            @ToolParam(name = "reason", required = false,
                    description = "为什么需要向用户提问：说明背后原因/背景，以及对用户选择各选项的影响，帮助用户做决定。可空。")
            String reason) {

        return Mono.fromFuture(() ->
                interactionHub.suspend(sessionId, question,
                        options == null ? List.of() : options,
                        reason == null ? "" : reason)
        ).map(answer -> ToolResultBlock.text("用户回答: " + answer))
         .onErrorResume(e -> {
             // 用户点击"停止"：下达停止指令，让 Agent 收尾结束
             if (e instanceof java.util.concurrent.CancellationException) {
                 return Mono.just(ToolResultBlock.text(
                         "用户点击了停止。请立即停止当前执行，不要继续调用任何工具，"
                                 + "用已有信息总结当前进展并收尾结束。"));
             }
             return Mono.just(ToolResultBlock.text(
                     "等待用户回答超时（10 分钟）。请基于现有信息给出最佳方案，"
                             + "或说明缺少哪些关键信息。"));
         });
    }
}