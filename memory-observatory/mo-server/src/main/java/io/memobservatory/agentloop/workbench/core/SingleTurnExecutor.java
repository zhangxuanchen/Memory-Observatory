/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】SingleTurnExecutor.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】统一「单轮 Agent 执行」入口：把「/chat 用户对话」与「工作区经理
 *            run_worker / run_parallel 派发子 Agent」共有的
 *            「建实例 → 投喂消息 → 跑事件流 → 收集输出计量 → 落盘会话记忆」收敛到
 *            同一条路径，消除两处重复耦合与潜在的队列/记忆口径漂移。
 * 【核心改动】2026-08-27 新增。承接架构评审第 1 项：统一子 Agent 执行路径。
 * 【设计要点】
 *            - 每轮新建独立 Agent 实例（复用 AgentFactory.create，规避有状态不可并发复用陷阱）。
 *            - 事件经 {@link EventSink} 交调用方做表现层转发（如 SSE/收集汇报），
 *              执行器只从中抽取通用计量（助手文本、工具输出字符），用于统一落盘。
 *            - 单轮最长执行时间由 TurnSpec.timeout 控制（watchdog 超时触发
 *              TurnListener.onTimeout 并取消订阅）；TurnSpec.heartbeat 供长阻塞期间
 *              周期回调，保持 SSE 连接活跃。
 *            - 补充队列（ContextQueue）消费时机由调用方各自掌握（对话流每轮后 pop；
 *              经理派发前 pop 合并），执行器不耦合队列读取节奏。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 统一单轮执行入口。见文件头注释。
 */
public class SingleTurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(SingleTurnExecutor.class);

    private final AgentFactory agentFactory;
    private final ConversationMemory conversationMemory;
    private final ContextSummarizer contextSummarizer;

    public SingleTurnExecutor(AgentFactory agentFactory,
                              ConversationMemory conversationMemory,
                              ContextSummarizer contextSummarizer) {
        this.agentFactory = agentFactory;
        this.conversationMemory = conversationMemory;
        this.contextSummarizer = contextSummarizer;
    }

    /** 单轮执行所需上下文：决定建哪个 Agent、跑哪条消息、事件如何转出（由调用方组装）。 */
    public record TurnSpec(
            String workspaceId,
            String agentId,
            String sessionId,
            String userId,
            String message,
            /** 会话历史上下文注入块；null/空则按会话记忆口径回落 conversationMemory.context(memKey)。 */
            String memoryContext,
            /** 进度文本回传（可为 null，静默）；经理派发/长工具期间保持连接活跃。 */
            Consumer<String> progress,
            /** 目标 Agent 运行态回传 (agentId, running)（可为 null）。 */
            BiConsumer<String, Boolean> running,
            /** 思维链结构化计划事件回传（可为 null）：管理器派发/验收时把 plan 事件（建节点/状态/一句思考）
             *  推给前端思维树。与其他 sink 一样随单轮新建实例注入。 */
            Consumer<String> plan,
            /** 单轮最长执行时间；null 表示不设 timeout 看门狗（如普通对话流，靠 SSE 自身超时）。 */
            Duration timeout,
            /** 长阻塞期间的周期心跳回调（可为 null）；仅在 timeout != null 时随看门狗调用。 */
            Runnable heartbeat) {
    }

    /** 单轮执行结果：助手最终文本（供调用方回读汇报/驱动下一轮）。 */
    public record TurnResult(String assistantText) {
    }

    /** 单轮事件转发口：由调用方决定每个 AgentEvent 如何转出（SSE / 收集/丢弃），执行器不感知表现层。 */
    public interface EventSink {
        void onEvent(AgentEvent ev);
    }

    /** 单轮生命周期回调：三类收尾互斥触发一次，用于关闭 SSE / countDown / 驱动队列下一轮。 */
    public abstract static class TurnListener {
        /** 单轮正常结束（已落盘会话记忆后触发）。 */
        public void onDone(TurnResult result) {
        }

        /** 单轮流式执行异常（含 create/stream 阶段）。 */
        public void onError(Throwable t) {
        }

        /** 单轮超时（由看门狗触发，订阅已被取消）。 */
        public void onTimeout() {
        }
    }

    /**
     * 执行单轮 Agent：建实例 → 投喂 → 跑流 → 事件转发 + 抽取计量 → 落盘 → 回调。
     *
     * <p>create 阶段的资源性异常（IOException）向上抛出，由调用方决定如何兜底；流式阶段的
     * 运行时异常统一走 {@link TurnListener#onError}。
     */
    public void runTurn(TurnSpec spec, EventSink sink, TurnListener listener) throws IOException {
        String memKey = ConversationMemory.key(spec.workspaceId(), spec.agentId(), spec.sessionId());

        // 1) 若挂有「待压缩」标记，先消费并执行一次自收敛，让本轮读到收敛后的记忆
        if (conversationMemory.consumeCompress(memKey)) {
            long memTarget = (long) (ConversationMemory.ContextBudget.RATIO
                    .get(ConversationMemory.ContextBudget.Zone.MEMORY)
                    * ConversationMemory.ContextBudget.DEFAULT_WINDOW);
            conversationMemory.compressMem(memKey, memTarget, contextSummarizer);
        }

        // 2) 会话历史上下文：调用方自定义（如经理注入的工作派发块）优先，否则按会话记忆口径
        String memoryContext = (spec.memoryContext() != null && !spec.memoryContext().isBlank())
                ? spec.memoryContext() : conversationMemory.context(memKey);
        HarnessAgent agent = agentFactory.create(spec.workspaceId(), spec.agentId(), spec.sessionId(),
                spec.userId(), memoryContext, spec.progress(), spec.running(), spec.plan());

        // 3) 跑事件流：事件转表现层（sink），同时抽取通用计量（助手文本 / 工具输出字符）
        StringBuilder asst = new StringBuilder();
        AtomicLong toolChars = new AtomicLong();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        Flux<AgentEvent> stream;
        try {
            Msg userMsg = Msg.builder().textContent(spec.message()).build();
            stream = agent.streamEvents(userMsg);
        } catch (Exception e) {
            listener.onError(e);
            return;
        }
        subRef.set(stream.subscribe(
                ev -> {
                    sink.onEvent(ev);
                    accumulate(ev, asst, toolChars);
                },
                err -> {
                    if (finished.compareAndSet(false, true)) {
                        listener.onError(err);
                    }
                },
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        persist(memKey, spec.message(), asst.toString(), toolChars.get());
                        listener.onDone(new TurnResult(asst.toString()));
                    }
                }));

        // 4) 单轮看门狗：按 timeout 机械性中断、按 heartbeat 周期回调（保持连接活跃）
        if (spec.timeout() != null || spec.heartbeat() != null) {
            Thread watchdog = new Thread(() -> {
                long waited = 0;
                while (!finished.get()) {
                    if (finished.get()) {
                        break;
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (finished.get()) {
                        break;
                    }
                    waited += 5000;
                    if (spec.heartbeat() != null) {
                        try {
                            spec.heartbeat().run();
                        } catch (Exception ignored) {
                            // 心跳失败不影响单轮主流程
                        }
                    }
                    if (spec.timeout() != null && waited >= spec.timeout().toMillis()
                            && finished.compareAndSet(false, true)) {
                        Disposable d = subRef.get();
                        if (d != null && !d.isDisposed()) {
                            d.dispose();
                        }
                        listener.onTimeout();
                        break;
                    }
                }
            }, "single-turn-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    /** 抽取文本增量 token 与工具输出字符，供统一的会话记忆计量。 */
    private void accumulate(AgentEvent ev, StringBuilder asst, AtomicLong toolChars) {
        if (ev instanceof io.agentscope.core.event.TextBlockDeltaEvent t) {
            asst.append(t.getDelta());
        } else if (ev instanceof io.agentscope.core.event.ToolResultTextDeltaEvent t) {
            String d = t.getDelta();
            if (d != null) {
                toolChars.addAndGet(d.length());
            }
        }
    }

    /** 落盘单轮会话记忆：先累加工具输出字符到 TOOL 区计量，再追加对话轮（会按需触发四轮摘要压缩）。 */
    private void persist(String memKey, String message, String asst, long toolChars) {
        try {
            conversationMemory.recordTool(memKey, toolChars);
            conversationMemory.append(memKey, message, asst, contextSummarizer);
        } catch (Exception e) {
            log.warn("记录会话记忆失败（不阻断）: {}", e.toString());
        }
    }
}