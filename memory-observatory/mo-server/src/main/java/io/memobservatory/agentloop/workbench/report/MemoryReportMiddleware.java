/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · report（旁路记忆观测）
 * 【文件】MemoryReportMiddleware.java（io.memobservatory.agentloop.workbench.report）
 * 【核心功能】记忆观测中间件：旁路采集 Agent 事件流中的模型 token 与工具调用，
 *            组装后经 EventReporter 上报 Memory Observatory。基于 AgentScope 2.0
 *            响应式 Middleware，只在最外层分流、不改事件，失败仅 WARN。
 * 【核心改动】
 *   2026-08-23 从 mo-agentloop 迁入 mo-server，成为 workbench Agent 默认观测能力。
 * 【设计要点】旁路观测容错：采集/上报全 try-catch，绝不阻塞或改变主流程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.report;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.DataBlockEndEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 记忆观测中间件：旁路采集 AgentScope 事件流中的模型推理（token）与工具调用，
 * 组装成事件上报到 Memory Observatory。
 *
 * 基于 AgentScope 2.0 响应式 Middleware（装饰器）。只在最外层 {@link #onAgent}
 * 对事件流 doOnNext 分流，不修改任何事件，维持"旁路观测容错"：采集与上报全 try-catch，
 * 失败仅 WARN，绝不阻塞 / 改变 Agent 主流程。已并入 mo-server 单进程，作为
 * workbench Agent 的默认观测能力。
 */
public class MemoryReportMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(MemoryReportMiddleware.class);

    /** 单次调用的 Turn 聚合状态。随 onAgent 局部变量（闭包）传递，绝不写入实例字段，
     *  同一次调用内的 observe / reportTurnEnd 共享，跨调用天然隔离（并发安全）。
     *  注意：工作台调用路径中 RuntimeContext 可能为 null，因此不依赖 ctx 携带状态。 */
    private static final class TurnState {
        String turnMessageId;
        String turnUser;
        String outcome;
        int actionIdx;
        long totalTokens;
        // 已分摊到工具事件的 token 累计（分摊增量 = totalTokens - attributedTokens）
        long attributedTokens;
        // 操作步骤描述摘要（与 action_idx 一一对应），主事件写 metadata.turn_actions
        final List<String> actions = new ArrayList<>();
        // 流式块累积：BLOCK_*_DELTA 不断拼接，BLOCK_*_END 合成为一条上报，避免高频刷库。
        // key=blockId → 思考 / 正文 / 数据文本
        final Map<String, StringBuilder> think = new HashMap<>();
        final Map<String, StringBuilder> text = new HashMap<>();
        final Map<String, StringBuilder> data = new HashMap<>();
        // key=toolCallId → 工具结果文本（TOOL_RESULT_TEXT_DELTA → TOOL_RESULT_END）
        final Map<String, StringBuilder> toolResult = new HashMap<>();
        // key=toolCallId → 工具开始时间(nanos)，TOOL_RESULT_END 算耗时用
        final Map<String, Long> toolStartNanos = new HashMap<>();
        // 待分摊 token 的工具调用事件（TOOL_CALL_START 产生，下一次 MODEL_CALL_END
        // 把「本次模型调用 token 增量」均摊后上报——处理该工具结果所花的推理成本）
        final List<Map<String, Object>> pendingToolEvents = new ArrayList<>();
    }

    private final ReportConfig config;
    private final EventReporter reporter;

    public MemoryReportMiddleware(ReportConfig config) {
        this.config = config;
        this.reporter = new EventReporter(config.endpoint());
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx,
                                    AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        if (!config.enabled()) return next.apply(input);
        long startNanos = System.nanoTime();
        TurnState turn = initTurn(ctx, input);
        return next.apply(input)
                .doOnNext(ev -> observe(agent, ctx, ev, turn))
                // doFinally 覆盖正常完成/异常/取消，保证 Turn 结束事件一直上报
                .doFinally(__ -> reportTurnEnd(agent, ctx, startNanos, turn));
    }

    /** 初始化本次调用的 Turn 状态：生成 turn_message_id、抽取用户问题。 */
    private TurnState initTurn(RuntimeContext ctx, AgentInput input) {
        TurnState turn = new TurnState();
        try {
            String sessionId = (ctx != null && ctx.getSessionId() != null)
                    ? ctx.getSessionId() : "default-session";
            turn.turnMessageId = "turn-" + sessionId + "-" + System.nanoTime();
            turn.turnUser = extractUserText(input);
        } catch (Exception e) {
            log.warn("memory init turn failed: {}", e.toString());
        }
        return turn;
    }

    /** 统一分流：把 AgentScope 事件流完整归一化上报。对应《事件系统》一文的 28 种事件。
     *
     *  分类策略：
     *   - 模型 MODEL_CALL_* → layer=model（含 token 消耗）
     *   - 流式块 TEXT/THINKING/DATA 的 DELTA 先累积进 TurnState，END 时合成一条上报，避免高频刷库
     *   - 工具 TOOL_CALL_START / TOOL_RESULT_* → layer=skill（结果合并在 END）
     *   - 生命周期 AGENT_START/END、子Agent → layer=agent
     *   - 人机协同 REQUIRE_USER_CONFIRM / USER_CONFIRM_RESULT / 外部执行 → layer=hitl
     *   - 控制 EXCEED_MAX_ITERS / REQUEST_STOP / ALL_TOOLS_DENIED → layer=control
     *   - 其余 HINT / CUSTOM → layer=hint / custom
     *  全程 try-catch，仅 WARN，绝不改变或阻塞 Agent 主流程。 */
    private void observe(Agent agent, RuntimeContext ctx, AgentEvent ev, TurnState turn) {
        try {
            if (ev instanceof ModelCallStartEvent) {
                reportLifecycle(agent, ctx, ev, turn, "model", "model:" + agentId(agent), "模型调用开始");
            } else if (ev instanceof ModelCallEndEvent m) {
                reportModelCall(agent, ctx, m, turn);
            } else if (ev instanceof ThinkingBlockDeltaEvent t) {
                accumulate(turn.think, t.getBlockId(), t.getDelta());
            } else if (ev instanceof ThinkingBlockEndEvent t) {
                reportBlockEnd(agent, ctx, turn, "model", "thinking:" + agentId(agent),
                        "AI 思考：", t.getBlockId(), turn.think);
            } else if (ev instanceof TextBlockDeltaEvent t) {
                accumulate(turn.text, t.getBlockId(), t.getDelta());
            } else if (ev instanceof TextBlockEndEvent t) {
                reportBlockEnd(agent, ctx, turn, "text", "text:" + agentId(agent),
                        "内容：", t.getBlockId(), turn.text);
            } else if (ev instanceof DataBlockDeltaEvent t) {
                accumulate(turn.data, t.getBlockId(), t.getDelta());
            } else if (ev instanceof DataBlockEndEvent t) {
                reportBlockEnd(agent, ctx, turn, "data", "data:" + agentId(agent),
                        "数据：", t.getBlockId(), turn.data);
            } else if (ev instanceof ToolCallStartEvent s) {
                reportToolCall(agent, ctx, s, turn);
            } else if (ev instanceof ToolCallEndEvent s) {
                String n = toolName(s.getToolCallName());
                reportLifecycle(agent, ctx, ev, turn, "skill", "tool:" + n + ":end", "工具结束 · " + n);
            } else if (ev instanceof ToolResultTextDeltaEvent s) {
                accumulate(turn.toolResult, s.getToolCallId(), s.getDelta());
            } else if (ev instanceof ToolResultEndEvent s) {
                reportToolResultEnd(agent, ctx, s, turn);
            } else if (ev instanceof AgentStartEvent s) {
                reportLifecycle(agent, ctx, ev, turn, "agent", "agent:start",
                        "Agent 启动 · " + (s.getName() == null ? agentId(agent) : s.getName()));
            } else if (ev instanceof AgentEndEvent) {
                reportLifecycle(agent, ctx, ev, turn, "agent", "agent:end", "Agent 结束");
            } else if (ev instanceof AgentResultEvent r) {
                // 记录最终产出，作为 Turn 主事件的 turn_outcome
                if (r.getResult() != null) {
                    String txt = r.getResult().getTextContent();
                    if (txt != null && !txt.isBlank()) turn.outcome = txt;
                }
            } else if (ev instanceof ExceedMaxItersEvent e) {
                reportLifecycle(agent, ctx, ev, turn, "control", "agent:max-iters",
                        "超过最大迭代次数 " + e.getMaxIters() + " 次，已终止");
            } else if (ev instanceof AllToolsDeniedEvent) {
                reportLifecycle(agent, ctx, ev, turn, "skill", "tool:denied", "所有工具调用被拒绝");
            } else if (ev instanceof RequireUserConfirmEvent) {
                reportLifecycle(agent, ctx, ev, turn, "hitl", "hitl:confirm", "需要用户确认工具调用");
            } else if (ev instanceof UserConfirmResultEvent) {
                reportLifecycle(agent, ctx, ev, turn, "hitl", "hitl:confirm-result", "用户已响应确认");
            } else if (ev instanceof RequireExternalExecutionEvent) {
                reportLifecycle(agent, ctx, ev, turn, "hitl", "hitl:external-exec", "需要外部执行工具");
            } else if (ev instanceof ExternalExecutionResultEvent) {
                reportLifecycle(agent, ctx, ev, turn, "hitl", "hitl:external-exec-result", "外部执行结果已返回");
            } else if (ev instanceof RequestStopEvent s) {
                reportLifecycle(agent, ctx, ev, turn, "control", "agent:stop",
                        "请求停止 · " + s.getReason());
            } else if (ev instanceof SubagentExposedEvent s) {
                reportLifecycle(agent, ctx, ev, turn, "agent", "agent:subagent",
                        "子Agent 暴露 · " + s.getSubagentId());
            } else if (ev instanceof HintBlockEvent h) {
                reportLifecycle(agent, ctx, ev, turn, "hint", "hint:" + h.getHintSource(), "提示：" + h.getHint());
            } else if (ev instanceof CustomEvent c) {
                reportLifecycle(agent, ctx, ev, turn, "custom", "custom:" + c.getName(),
                        "自定义事件 · " + c.getName());
            }
        } catch (Exception e) {
            log.warn("memory observe failed: {}", e.toString());
        }
    }

    /** 模型推理结束：先把上个模型调用之后积累的工具调用事件分摊 token 上报，
     *  再上报一次 LLM token 消耗（layer=model，op=WRITE）。 */
    private void reportModelCall(Agent agent, RuntimeContext ctx, ModelCallEndEvent ev, TurnState turn) {
        ChatUsage u = ev.getUsage();
        int total = u != null ? u.getTotalTokens() : 0;
        // 分摊：本次模型调用 token 增量（处理上批工具结果所花的推理成本）均摊到本批工具调用
        flushPendingToolEvents(turn, total - turn.attributedTokens);
        turn.attributedTokens = total;
        Map<String, Object> f = base(agent, ctx, "WRITE", "model");
        f.put("memoryKey", "model:" + agentId(agent));
        String in = u != null ? String.valueOf(u.getInputTokens()) : "0";
        String out = u != null ? String.valueOf(u.getOutputTokens()) : "0";
        String cached = (u != null && u.getCachedTokens() > 0)
                ? " 缓存in=" + u.getCachedTokens() : "";
        String detail = "LLM 推理 · token=" + total + "（in=" + in + " out=" + out + cached + "）";
        f.put("memorySummary", detail);
        f.put("tokenCount", total);
        f.put("latencyMs", u != null ? (long) (u.getTime() * 1000L) : 0L);
        turn.totalTokens += total;
        attachAction(turn, f, detail);
        reporter.report(f);
    }

    /** 工具调用：识别 memory 读写类工具，上报 SKILL 事件。
     *  事件先进 pending 队列，下一次 MODEL_CALL_END 把模型 token 增量均摊后再上报，
     *  使 layer=skill 事件携带真实 token 消耗（否则 tool 事件 token 恒为 0，无法做成本分析）。 */
    private void reportToolCall(Agent agent, RuntimeContext ctx, ToolCallStartEvent ev, TurnState turn) {
        String name = ev.getToolCallName() == null ? "tool" : ev.getToolCallName();
        String op = isMemoryRead(name) ? "READ" : (isMemoryWrite(name) ? "WRITE" : "WRITE");
        Map<String, Object> f = base(agent, ctx, op, "skill");
        f.put("memoryKey", "tool:" + name);
        String detail = "工具调用 · " + name + (ev.getToolCallId() != null ? " · " + ev.getToolCallId() : "");
        f.put("memorySummary", detail);
        attachAction(turn, f, detail);
        if (ev.getToolCallId() != null) {
            turn.toolStartNanos.put(ev.getToolCallId(), System.nanoTime());
        }
        // 记录发生时刻（pending 上报时回填，保证时间线顺序正确）
        f.put("timestamp", Instant.now().toString());
        turn.pendingToolEvents.add(f);
    }

    /** 分摊并上报 pending 的工具调用事件：把 tokensDelta 均摊到每条（处理该批工具
     *  结果的模型推理成本），时间戳回填为事件发生时刻。turn 结束时也会以 0 兜底调用。 */
    private void flushPendingToolEvents(TurnState turn, long tokensDelta) {
        if (turn.pendingToolEvents.isEmpty()) return;
        try {
            long share = turn.pendingToolEvents.size() > 0 && tokensDelta > 0
                    ? tokensDelta / turn.pendingToolEvents.size() : 0;
            for (Map<String, Object> f : turn.pendingToolEvents) {
                f.put("tokenCount", share);
                reporter.report(f);
            }
        } catch (Exception e) {
            log.warn("flush pending tool events failed: {}", e.toString());
        } finally {
            turn.pendingToolEvents.clear();
        }
    }

    /** 累积流式块增量（TEXT/THINKING/DATA/TOOL_RESULT 的 DELTA）。单块上限 20000 字符防撑爆内存。 */
    private static void accumulate(Map<String, StringBuilder> buf, String key, String delta) {
        if (delta == null || delta.isBlank()) return;
        StringBuilder sb = buf.computeIfAbsent(key, k -> new StringBuilder());
        if (sb.length() >= 20000) return;
        sb.append(delta);
    }

    /** 流式块结束：把累积的 DELTA 合成一条上报（AI 思考 / 正文 / 数据），避免逐条刷库。 */
    private void reportBlockEnd(Agent agent, RuntimeContext ctx, TurnState turn,
                                String layer, String memoryKey, String prefix,
                                String blockId, Map<String, StringBuilder> buf) {
        StringBuilder sb = (blockId == null) ? null : buf.remove(blockId);
        String content = (sb == null) ? null : truncate(sb.toString(), 3000);
        if (content == null || content.isBlank()) return;
        Map<String, Object> f = base(agent, ctx, "WRITE", layer);
        f.put("memoryKey", memoryKey);
        String detail = prefix + content;
        f.put("memorySummary", detail);
        f.put("tokenCount", 0);
        attachAction(turn, f, detail);
        reporter.report(f);
    }

    /** 工具结果结束：把累积的工具结果文本合成一条上报（layer=skill）。
     *  同时写入 latency（START→END 差值）与 metadata.status（对齐 Trace 汇总的
     *  failed 判定口径 metadata->>'status'='failed'），供技能分析/问题检测使用。 */
    private void reportToolResultEnd(Agent agent, RuntimeContext ctx, ToolResultEndEvent ev, TurnState turn) {
        String name = ev.getToolCallName() == null ? "tool" : ev.getToolCallName();
        String state = (ev.getState() == null) ? "" : " · " + ev.getState();
        StringBuilder sb = (ev.getToolCallId() == null)
                ? null : turn.toolResult.remove(ev.getToolCallId());
        String content = (sb == null) ? "" : truncate(sb.toString(), 2000);
        Map<String, Object> f = base(agent, ctx, "WRITE", "skill");
        f.put("memoryKey", "tool:" + name + ":result");
        String detail = "工具结果 · " + name + state + (content.isBlank() ? "" : "\n" + content);
        f.put("memorySummary", truncate(detail, 3000));
        f.put("tokenCount", 0);
        // 耗时：TOOL_CALL_START 起算的差值（纳秒 → 毫秒）
        Long startNanos = (ev.getToolCallId() != null)
                ? turn.toolStartNanos.remove(ev.getToolCallId()) : null;
        if (startNanos != null) {
            f.put("latencyMs", (System.nanoTime() - startNanos) / 1_000_000L);
        }
        attachAction(turn, f, detail);
        // 状态结构化：ERROR→failed（问题检测/Trace 汇总判定用），其余写原值小写
        if (ev.getState() != null) {
            Object meta = f.get("metadata");
            if (meta instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, String> mm = (Map<String, String>) m;
                mm.put("status", ev.getState() == io.agentscope.core.message.ToolResultState.ERROR
                        ? "failed" : ev.getState().getValue());
            }
        }
        reporter.report(f);
    }

    /** 一次性事件统一上报（生命周期 / 人机协同 / 控制 / 提示 / 自定义）。 */
    private void reportLifecycle(Agent agent, RuntimeContext ctx, AgentEvent ev, TurnState turn,
                                 String layer, String memoryKey, String summary) {
        Map<String, Object> f = base(agent, ctx, "WRITE", layer);
        f.put("memoryKey", memoryKey);
        f.put("memorySummary", summary);
        f.put("tokenCount", 0);
        // 子Agent 转发事件带 source 路径（如 main/researcher），记录便于追溯来源
        if (ev.getSource() != null && !ev.getSource().isBlank()) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("turn_message_id", turn.turnMessageId);
            meta.put("source", ev.getSource());
            f.put("metadata", meta);
        } else {
            attachAction(turn, f, summary);
        }
        reporter.report(f);
    }

    private static String toolName(String name) {
        return (name == null || name.isBlank()) ? "tool" : name;
    }

    /** Turn 结束：上报一条会话级主事件（layer=session, op=WRITE），作为事件详情页的一行。 */
    private void reportTurnEnd(Agent agent, RuntimeContext ctx, long startNanos, TurnState turn) {
        if (startNanos <= 0) return;
        try {
            // 兜底：Turn 结束时仍有未分摊的工具调用事件（如最后无 MODEL_CALL_END 收尾），以 0 token 上报
            flushPendingToolEvents(turn, 0);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String sessionId = (ctx != null && ctx.getSessionId() != null)
                    ? ctx.getSessionId() : "default-session";
            Map<String, Object> f = base(agent, ctx, "WRITE", "session");
            f.put("memoryKey", "turn:" + sessionId);
            f.put("latencyMs", elapsedMs);
            // 主事件携带本 Turn 的 LLM token 合计，使详情抽屉/汇总页的 token 显示真实值
            f.put("tokenCount", turn.totalTokens);

            Map<String, String> meta = new LinkedHashMap<>();
            if (turn.turnMessageId != null && !turn.turnMessageId.isBlank()) {
                meta.put("turn_message_id", turn.turnMessageId);
            }
            if (turn.turnUser != null && !turn.turnUser.isBlank()) meta.put("turn_user", turn.turnUser);
            if (turn.outcome != null && !turn.outcome.isBlank()) meta.put("turn_outcome", turn.outcome);
            meta.put("action_count", String.valueOf(turn.actionIdx));
            if (!turn.actions.isEmpty()) meta.put("turn_actions", toJsonArray(turn.actions));
            f.put("metadata", meta);

            f.put("memorySummary", (turn.outcome != null && !turn.outcome.isBlank())
                    ? turn.outcome : "Turn 结束 · 耗时 " + elapsedMs + "ms");
            reporter.report(f);
        } catch (Exception e) {
            log.warn("report turnEnd failed: {}", e.toString());
        }
    }

    /** 给 action 子事件打上 turn 分组元数据（turn_message_id + action_idx + action_full）。 */
    private void attachAction(TurnState turn, Map<String, Object> fields, String actionFull) {
        if (turn == null) return;
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("turn_message_id", turn.turnMessageId);
        meta.put("action_idx", String.valueOf(turn.actionIdx));
        if (actionFull != null && !actionFull.isBlank()) meta.put("action_full", actionFull);
        // 与 action_idx 一一对应，收集到 TurnState 供主事件写 turn_actions
        turn.actions.add(actionFull == null ? "" : actionFull);
        fields.put("metadata", meta);
    }

    /** 步骤列表 → JSON 数组字符串（供主事件 metadata.turn_actions，前端逐条展示操作步骤）。 */
    private static String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    /** 从 AgentInput 的消息中提取最后一条用户问题（作为 turn_user）。 */
    private static String extractUserText(AgentInput input) {
        if (input == null || input.msgs() == null) return null;
        String found = null;
        for (Msg msg : input.msgs()) {
            if (msg != null && msg.getRole() == MsgRole.USER) {
                String t = msg.getTextContent();
                if (t != null && !t.isBlank()) found = t;
            }
        }
        return truncate(found, 500);
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 事件公共字段（对齐 CreateEventDTO）。 */
    private Map<String, Object> base(Agent agent, RuntimeContext ctx, String op, String layer) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("agentId", agentId(agent));
        // sessionId 优先取运行时上下文；无上下文时回落真实 agent id，对齐"会话以 Agent 为单位"（sessionId=agentId）。
        f.put("sessionId", (ctx != null && ctx.getSessionId() != null)
                ? ctx.getSessionId() : agentId(agent));
        f.put("operation", op);
        f.put("layer", layer != null ? layer : config.defaultLayer());
        f.put("timestamp", Instant.now().toString());
        return f;
    }

    // agentId 优先回落运行时 Agent 真实名（= 工作区 Agent id，装配时 name(m.id())），
    // config.agentName() 仅作无运行时上下文时的兜底（默认置空）。
    private String agentId(Agent agent) {
        if (agent != null) {
            if (agent.getName() != null && !agent.getName().isBlank()) return agent.getName();
            if (agent.getAgentId() != null && !agent.getAgentId().isBlank()) return agent.getAgentId();
        }
        if (config.agentName() != null && !config.agentName().isBlank()) return config.agentName();
        return "unknown";
    }

    private static boolean isMemoryRead(String tool) {
        if (tool == null) return false;
        String t = tool.toLowerCase();
        return t.contains("remember") || t.contains("retrieve")
                || t.contains("search") || t.contains("find") || t.endsWith(".read");
    }

    private static boolean isMemoryWrite(String tool) {
        if (tool == null) return false;
        String t = tool.toLowerCase();
        return t.contains("store") || t.contains("write") || t.contains("memorize")
                || t.contains("save") || t.contains("update");
    }
}