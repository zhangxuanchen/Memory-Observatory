/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · web
 * 【文件】SseEventSink.java（io.memobservatory.agentloop.workbench.web）
 * 【核心功能】把 AgentEvent 流式转发为 SSE event（token / tool / toolresult / …）。
 * 【核心改动】2026-08-27 从 AgentController.dispatch.forward 拆出（架构评审第 3 项：
 *            AgentController 按职责拆分），作为 SingleTurnExecutor 的 EventSink 实现。
 * 【设计要点】工具事件按 toolCallId 累积流式入参/结果，结束时发出带摘要的事件，
 *            让"做了什么"可见。每个 SseEventSink 绑一条 SSE 连接（随单轮新建实例），
 *            自带 callArgs/callResults，天然并发隔离。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.web;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.memobservatory.agentloop.workbench.core.SingleTurnExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮 Agent 事件的 SSE 转发器。实现 {@link SingleTurnExecutor.EventSink}。
 */
final class SseEventSink implements SingleTurnExecutor.EventSink {

    private final SseEmitter emitter;
    /** 工具呼叫实例集合：流式累积入参/结果，用 toolCallId 隔离，跨流/Call 天然唯一。 */
    private final Map<String, String> callArgs = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> callResults = new ConcurrentHashMap<>();

    SseEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onEvent(AgentEvent ev) {
        try {
            if (ev instanceof TextBlockDeltaEvent t) {
                emitter.send(SseEmitter.event().name("token").data(t.getDelta()));
            } else if (ev instanceof ToolCallStartEvent t) {
                callArgs.put(t.getToolCallId(), "");
            } else if (ev instanceof ToolCallDeltaEvent t) {
                callArgs.merge(t.getToolCallId(), t.getDelta(), String::concat);
            } else if (ev instanceof ToolCallEndEvent t) {
                String args = summarize(callArgs.remove(t.getToolCallId()));
                emitter.send(SseEmitter.event().name("tool")
                        .data(t.getToolCallName() + (args == null ? "" : " · " + args)));
            } else if (ev instanceof ToolResultStartEvent t) {
                callResults.put(t.getToolCallId(), new StringBuilder());
            } else if (ev instanceof ToolResultTextDeltaEvent t) {
                StringBuilder sb = callResults.get(t.getToolCallId());
                if (sb != null) {
                    sb.append(t.getDelta());
                }
            } else if (ev instanceof ToolResultEndEvent t) {
                StringBuilder sb = callResults.remove(t.getToolCallId());
                String result = summarizePreserve(sb == null ? null : sb.toString());
                if (result != null) {
                    emitter.send(SseEmitter.event().name("toolresult").data(result));
                }
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /** 工具入参摘要：压缩空白、截断到 200 字符，便于气泡里单行展示。 */
    private static String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 工具结果摘要：保留换行与缩进的 Markdown 结构（标题/代码块/列表依赖换行），
     *  只压缩行内多余空白、合并连续空行，并按行边界截断到 MAX_CHARS 字符。
     *  若截断点落在一个未闭合的代码围栏块内，会推进到该块闭合处，避免 mermaid/代码残缺。
     *  读取失败时把错误单行化追加，便于前端一眼看到原因。 */
    private static String summarizePreserve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll(" {2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        final int MAX = 2000;
        if (s.length() > MAX) {
            int cut = s.indexOf('\n', MAX);
            if (cut < 0) {
                cut = MAX;
            }
            cut = Math.min(cut, s.length());
            // 若候选截断点落在未闭合的代码围栏(```)块内，推进到该块闭合之后，
            // 保证 mermaid / 代码块结构完整，而不是从 ``` 中间切断导致渲染失败。
            if ((countFencesTo(s, cut) & 1) == 1) {
                int close = s.indexOf("```", cut);
                if (close >= 0) {
                    int eol = s.indexOf('\n', close + 3);
                    cut = Math.min(s.length(), eol < 0 ? close + 3 : eol + 1);
                }
            }
            s = s.substring(0, cut) + "\n…（结果较长已截断）";
        }
        return s;
    }

    /** 统计 s[0,end) 内代码围栏 ``` 的出现次数（偶数=不在块内，奇数=块内）。 */
    private static int countFencesTo(String s, int end) {
        int n = 0, i = 0;
        while (i < end) {
            int j = s.indexOf("```", i);
            if (j < 0 || j >= end) {
                break;
            }
            n++;
            i = j + 3;
        }
        return n;
    }
}