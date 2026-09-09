/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · web
 * 【文件】AgentController.java（io.memobservatory.agentloop.workbench.web）
 * 【核心功能】工作台宿主端点（已并入 mo-server Servlet 栈）：
 *            POST /chat 返回 SSE 流；GET /questions 轮询 ask_user 问题；
 *            POST /questions/{qid}/answer 提交回答唤醒挂起工具。
 * 【核心改动】
 *   2026-08-23 迁入 mo-server 单进程；SSE 由 WebFlux 改为 Servlet SseEmitter，
 *   内部 subscribe 消费 Agent 流式事件并逐条转发。
 * 【设计要点】每请求新建独立 Agent 实例，规避有状态不可并发复用陷阱。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.web;

import io.memobservatory.agentloop.workbench.core.ContextSummarizer;
import io.memobservatory.agentloop.workbench.core.ConversationMemory;
import io.memobservatory.agentloop.workbench.core.SingleTurnExecutor;
import io.memobservatory.agentloop.workbench.hitl.ContextQueue;
import io.memobservatory.agentloop.workbench.hitl.UserInteractionHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 工作台宿主端点（已并入 mo-server Servlet 栈，SSE 用 SseEmitter）。
 * <ul>
 *   <li>POST /chat 返回 SSE 流。</li>
 *   <li>GET /questions 轮询待回答的 ask_user 问题。</li>
 *   <li>POST /questions/{qid}/answer 提交回答唤醒挂起的工具。</li>
 * </ul>
 * 每请求新建独立 Agent 实例（规避 Agent/Toolkit 有状态不可并发复用的陷阱）。
 * 内部用 subscribe 消费 Agent 的响应式事件流，并逐条写入 SseEmitter。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final UserInteractionHub interactionHub;
    private final ContextQueue contextQueue;
    private final ConversationMemory conversationMemory;
    private final ContextSummarizer contextSummarizer;
    /** 统一单轮执行入口（/chat 与经理派发子 Agent 共用同一执行路径）。 */
    private final SingleTurnExecutor turnRunner;

    public AgentController(UserInteractionHub interactionHub,
                           ContextQueue contextQueue, ConversationMemory conversationMemory,
                           ContextSummarizer contextSummarizer, SingleTurnExecutor turnRunner) {
        this.interactionHub = interactionHub;
        this.contextQueue = contextQueue;
        this.conversationMemory = conversationMemory;
        this.contextSummarizer = contextSummarizer;
        this.turnRunner = turnRunner;
    }

    /** 会话补充上下文队列的隔离 key。 */
    private static String queueKey(ChatRequest req) {
        return req.workspaceId() + "/" + req.agentId() + "/" + req.sessionId();
    }

    /** 执行中补充上下文：入队暂存，由当前 SSE 在本轮跑完后 pop 出继续执行。 */
    @PostMapping("/context")
    public Map<String, Object> context(@RequestBody ChatRequest req) {
        contextQueue.enqueue(queueKey(req), req.message());
        return Map.of(
                "queued", true,
                "pending", contextQueue.size(queueKey(req)));
    }

    /** 只读：当前会话（memKey）的记忆状态快照，供左下角记忆面板定时轮询。
     *  含：KEEP_RECENT 原文摘要等基础项 + 五区占用结构 + 记忆三层拆分。
     *  未传 sessionId 时按约定回落为 agentId（前端会话以 Agent 为单位）。 */
    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam String workspaceId,
                                      @RequestParam String agentId,
                                      @RequestParam(required = false) String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? agentId : sessionId;
        final String memKey = ConversationMemory.key(workspaceId, agentId, sid);
        ConversationMemory.MemorySnapshot snap = conversationMemory.snapshot(memKey);
        long window = ConversationMemory.ContextBudget.DEFAULT_WINDOW;
        double memoryBudget = ConversationMemory.ContextBudget.RATIO
                .get(ConversationMemory.ContextBudget.Zone.MEMORY) * window;
        double pct = memoryBudget <= 0 ? 0 : snap.estChars() / memoryBudget * 100.0;

        // 五区结构：各区占比/上限字符；实际占用仅 MEMORY 落库可估，FREE=窗口−已用（近似）。
        java.util.Map<ConversationMemory.ContextBudget.Zone, Double> ratio =
                ConversationMemory.ContextBudget.RATIO;
        long freeChars = Math.max(0, window - snap.estChars());
        double freeRatio = (double) freeChars / window;
        String freeStatus = freeRatio < 0.10 ? "OVER" : freeRatio < 0.15 ? "WARN" : "OK";
        java.util.List<Map<String, Object>> zones = new java.util.ArrayList<>();
        for (ConversationMemory.ContextBudget.Zone z : ConversationMemory.ContextBudget.Zone.values()) {
            double r = ratio.get(z);
            long cap = (long) (r * window);
            long used = z == ConversationMemory.ContextBudget.Zone.MEMORY ? snap.estChars()
                    : z == ConversationMemory.ContextBudget.Zone.TOOL ? snap.toolChars()
                    : z == ConversationMemory.ContextBudget.Zone.FREE ? freeChars : 0;
            long u = Math.min(used, cap);
            String status = z == ConversationMemory.ContextBudget.Zone.FREE ? freeStatus
                    : z == ConversationMemory.ContextBudget.Zone.MEMORY
                    ? (snap.estChars() > cap ? "OVER"
                    : snap.estChars() > cap * 0.8 ? "WARN" : "OK")
                    : z == ConversationMemory.ContextBudget.Zone.TOOL
                    ? (u > cap ? "OVER" : u > cap * 0.8 ? "WARN" : "OK") : "OK";
            zones.add(Map.of("zone", z.name(), "ratio", r, "cap", cap, "used", u, "status", status));
        }

        return Map.ofEntries(
                Map.entry("memKey", memKey),
                Map.entry("exists", snap.exists()),
                Map.entry("compressMarked", conversationMemory.isCompressMarked(memKey)),
                Map.entry("summary", snap.summary()),
                Map.entry("recentTurns", snap.recentTurns()),
                Map.entry("recent", snap.recent()),
                Map.entry("totalTurns", snap.totalTurns()),
                Map.entry("estChars", snap.estChars()),
                Map.entry("memPercent", (int) Math.round(pct)),
                Map.entry("window", window),
                Map.entry("threeLayer", Map.of(
                        "folded", snap.folded(),
                        "summaryChars", snap.summaryChars(), // 早期要旨
                        "recentChars", snap.recentChars())),   // 最近原文
                Map.entry("zones", zones),
                Map.entry("compressionLog", conversationMemory.compressionLog(memKey).stream().limit(10)
                        .map(c -> Map.of("ts", c.ts(),
                                "before", c.memCharsBefore(),
                                "after", c.memCharsAfter(),
                                "target", c.targetChars(),
                                "achieved", c.achieved()))
                        .toList()),
                Map.entry("foldTrail", conversationMemory.foldTrail(memKey)));
    }

    /** 手动标记记忆压缩：仅置位待压缩标记，不立即执行；下一轮 Agent 运行时消费并压缩。
     *  返回标记结果 + 该会话当前是否另有待压缩标记。 */
    @PostMapping("/memory/compress")
    public Map<String, Object> compressMemory(@RequestParam String workspaceId,
                                              @RequestParam String agentId,
                                              @RequestParam(required = false) String sessionId) {
        String sid = (sessionId == null || sessionId.isBlank()) ? agentId : sessionId;
        final String memKey = ConversationMemory.key(workspaceId, agentId, sid);
        conversationMemory.markCompress(memKey);
        return Map.of(
                "memKey", memKey,
                "marked", true,
                "message", "已标记待压缩，将在下一轮 Agent 运行时执行",
                "pending", conversationMemory.isCompressMarked(memKey));
    }

    /** SSE 流式对话：把 Agent 事件游标转发为 SSE event（token/tool/done/error）。
     *  先跑用户首条消息，跑完自动 pop 执行中排队的补充上下文继续，队空才结束流。 */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        // 10 分钟内无新事件则超时关闭，避免挂死
        final SseEmitter emitter = new SseEmitter(10 * 60_000L);
        // 用 agentId 记录每个 Agent 在本流内已跑的轮次数，判定轮次边界，取代位置性 first 标记。
        // 每个请求新建一张表，天然并发隔离。
        Map<String, Integer> turnCount = new java.util.HashMap<>();
        dispatch(emitter, req, req.message(), turnCount);
        return emitter;
    }

    /** 消费循环：跑完一条消息后 pop 下一条补充；队空发 done 结束整个 SSE 流。
     *  @param turnCount 以 agentId 为键的轮次表；某 Agent 跑第 2 轮起先发 prompt 事件标记轮次边界。 */
    private void dispatch(SseEmitter emitter, ChatRequest req, String message, Map<String, Integer> turnCount) {
        int turnNo = turnCount.merge(req.agentId(), 1, Integer::sum);
        if (turnNo > 1) {
            try {
                emitter.send(SseEmitter.event().name("prompt").data(req.agentId()));
            } catch (Exception e) {
                return;
            }
        }

        // Agent 影响边界 = 其所在工作区根；统一单轮执行入口负责：按清单装配 + 待压缩消费 +
        // 注入会话记忆 + 跑流 + 转发事件 + 落盘。SSE 专有的事件转发交给 SseEventSink。
        // 进度/运行态通道仍把经理长工具的 progress / agentrun 事件推给前端，保持连接活跃。
        SingleTurnExecutor.TurnSpec spec = new SingleTurnExecutor.TurnSpec(
                req.workspaceId(), req.agentId(), req.sessionId(), req.userId(),
                message,
                conversationMemory.context(ConversationMemory.key(req.workspaceId(), req.agentId(), req.sessionId())),
                line -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(line));
                    } catch (Exception ignored) {
                    }
                },
                (workerId, running) -> {
                    // 目标 Agent 运行态：供前端在 Agent 按钮上显示“运行中…”（经理 run_worker/run_parallel 派发的目标 Agent）
                    try {
                        emitter.send(SseEmitter.event().name("agentrun")
                                .data(workerId + " " + (running ? 1 : 0)));
                    } catch (Exception ignored) {
                    }
                },
                // 工作区经理思维链：结构化 plan 事件（建节点/状态/一句思考）直接转 SSE，供前端「思考树」面板渲染
                line -> {
                    try {
                        emitter.send(SseEmitter.event().name("plan").data(line));
                    } catch (Exception ignored) {
                    }
                },
                // 主对话心跳：模型长时间思考（工具间隔可达十几秒乃至更久）期间 SSE 若长时间无字节，
                // 连接会被容器/网络按空闲掐断，前端 reader 挂死显得“卡住不刷新”。这里每 5s 发一条
                // event:ping 保活。timeout 保持 null 表示只看门狗保活、绝不强杀一次正常但稍慢的模型调用。
                null, () -> {
                    // 用「命名事件 + data」保活：实测 Spring 的 event().comment() 在无 data 时不会往连接
                    // 写任何字节（send 成功但线上无内容），保活等于失效；命名事件走与 token 相同的序列化
                    // 路径（该路径实测可靠送达）。前端把 event:ping 当存活信号、只刷新哨兵不再渲染。
                    try {
                        emitter.send(SseEmitter.event().name("ping").data(""));
                    } catch (Exception e) {
                        log.warn("[keepalive] send failed: {}", e.toString());
                    }
                });
        try {
            turnRunner.runTurn(spec, new SseEventSink(emitter), new SingleTurnExecutor.TurnListener() {
                @Override
                public void onDone(SingleTurnExecutor.TurnResult result) {
                    // 本轮结束：单轮记忆落盘已由执行器完成，再 pop 下一条补充继续，队空发 done
                    String next = contextQueue.poll(queueKey(req));
                    if (next == null) {
                        complete(emitter, "done", "ok");
                    } else {
                        dispatch(emitter, req, next, turnCount);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    complete(emitter, "error", "ERROR: " + t);
                }
            });
        } catch (Exception e) {
            log.error("create agent failed: {}", e.toString());
            complete(emitter, "error", "ERROR: " + e.getMessage());
        }
    }

    private void complete(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    /** 当前待回答的所有 ask_user 问题（前端轮询或结合事件渲染）。 */
    @GetMapping("/questions")
    public Object openQuestions() {
        return interactionHub.openQuestions();
    }

    /** 提交回答，唤醒挂起的 ask_user 工具。 */
    @PostMapping("/questions/{qid}/answer")
    public boolean answer(@PathVariable String qid, @RequestBody AnswerRequest req) {
        return interactionHub.submitAnswer(qid, req.answer());
    }

    /** 用户点击"停止"：取消挂起的 ask_user，让 Agent 收尾停止。 */
    @PostMapping("/questions/{qid}/cancel")
    public boolean cancel(@PathVariable String qid) {
        return interactionHub.cancel(qid);
    }

    public record ChatRequest(String workspaceId, String agentId,
                              String sessionId, String userId, String message) {
    }

    public record AnswerRequest(String answer) {
    }
}