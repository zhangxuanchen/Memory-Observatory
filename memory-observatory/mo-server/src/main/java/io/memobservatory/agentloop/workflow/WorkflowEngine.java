/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】WorkflowEngine.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】多角色顺序编排器（Harness 门禁语义）：为每个角色创建一个独立 ReActAgent
 *            （复用 AgentFactory.createRole），按阶段顺序推进；阶段间把上一角色产出拼进下
 *            一角色用户消息前缀。机械化门禁：产物含失败信号 → 进入 bugfix 修复闭环重跑；
 *            连续失败超限中止；git 阶段提交前先编译校验；收口角色做验收 + 总结。
 * 【核心改动】2026-08-24 新增。M1 采用自研顺序调度，Harness API 成熟后可替换。
 * 【设计要点】每角色独立实例规避有状态不可并发复用；流式事件透传前端，结果经
 *            AgentResultEvent 取最终文本作为门禁输入。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.memobservatory.agentloop.workbench.core.AgentFactory;
import io.memobservatory.agentloop.workbench.report.EventReporter;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 工作流顺序编排器。把一次用户需求推进为完整的多阶段交付。
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final AgentFactory agentFactory;
    private final WorkflowConfig config;
    /** 旁路阶段观测上报器：可为 null，null 时跳过阶段事件上报（不影响流程）。 */
    private final EventReporter reporter;
    /** 真实构建门禁校验器：可为 null，null 时回落到文本关键词扫描门禁。 */
    private final BuildVerifier buildVerifier;
    /** 任务黑板（动态子 agent）：可为 null。 */
    private final TaskBoard taskBoard;
    /** 工作流断点存档：可为 null，null 时不落盘（仅内存）。 */
    private final WorkflowRunStore runStore;
    /** 并发隔离锁注册表：可为 null，null 时不做并发互斥。 */
    private final WorkspaceLockRegistry lockRegistry;
    /** 工作区读写（定位工作区根，供构建/存档）。 */
    private final WorkspaceManager workspaceManager;

    /** 一次工作流运行请求。 */
    public record RunRequest(String workspaceId, String sessionId, String userId,
                             String message, String workflowId) {
    }

    /** 流式事件监听：name = phase / phase_end / token / tool / done / error。 */
    @FunctionalInterface
    public interface Listener {
        void onEvent(String name, String data);
    }

    /** 一个角色的一次执行结果。 */
    private record RoleResult(String roleId, String output) {
    }

    public WorkflowEngine(AgentFactory agentFactory, WorkflowConfig config) {
        this(agentFactory, config, null, null, null, null, null, null);
    }

    public WorkflowEngine(AgentFactory agentFactory, WorkflowConfig config, EventReporter reporter) {
        this(agentFactory, config, reporter, null, null, null, null, null);
    }

    public WorkflowEngine(AgentFactory agentFactory, WorkflowConfig config, EventReporter reporter,
                          WorkspaceManager workspaceManager, BuildVerifier buildVerifier,
                          TaskBoard taskBoard, WorkflowRunStore runStore,
                          WorkspaceLockRegistry lockRegistry) {
        this.agentFactory = agentFactory;
        this.config = config;
        this.reporter = reporter;
        this.workspaceManager = workspaceManager;
        this.buildVerifier = buildVerifier;
        this.taskBoard = taskBoard;
        this.runStore = runStore;
        this.lockRegistry = lockRegistry;
    }

    /**
     * 运行整条工作流（阻塞）。任一阶段异常统一以 error 事件收尾；成功以 done 事件输出
     * 收口角色总结。
     */
    public void run(RunRequest req, Listener listener) {
        String wfId = (req.workflowId() == null || req.workflowId().isBlank())
                ? "wf_" + System.currentTimeMillis() : req.workflowId();

        // 并发隔离：同一 workspace+session 只允许一个运行实例推进，防止并行覆盖改动
        boolean locked = lockRegistry != null
                && lockRegistry.tryLock(req.workspaceId(), req.sessionId());
        if (lockRegistry != null && !locked) {
            listener.onEvent("error", "该工作区/会话已有工作流在运行，请等待其完成后再试。");
            return;
        }
        reportWorkflow(req, wfId, "workflow", "start");
        try {
            runLocked(req, listener, wfId);
        } finally {
            if (locked) {
                lockRegistry.unlock(req.workspaceId(), req.sessionId());
            }
        }
    }

    /** 工作流推进主体（已持有锁）。断点续跑、动态门禁、真实构建校验、任务黑板均在此落地。 */
    private void runLocked(RunRequest req, Listener listener, String wfId) {
        log.info("[workflow:{}] start: {}", wfId, req.message());
        Workspace ws = workspaceManager != null ? workspaceManager.byId(req.workspaceId()) : null;
        if (runStore != null) {
            runStore.setStatus(ws, wfId, "running");
        }
        // 需求原文贯穿全程
        StringBuilder context = new StringBuilder();
        context.append("【用户需求】\n").append(req.message()).append("\n\n");
        try {
            // 1. 项目经理整体规划（定义门禁与门禁产出物），断点已 done 则复用
            RoleResult plan = runRoleOrReuse(ws, wfId, "pm", role("pm"), req.message(), req, listener);
            append(context, "【项目经理规划】", plan.output());

            // 动态门禁规则 + 任务黑板（PM 产出驱动，无匹配回落默认/空任务）
            List<GateSpec> gates = config.isDynamicGates()
                    ? GateParser.parseGates(plan.output()) : GateSpec.DEFAULTS;
            if (config.isTaskBoard() && taskBoard != null) {
                List<TaskBoard.Task> tasks = GateParser.parseTasks(plan.output());
                if (!tasks.isEmpty()) {
                    taskBoard.putAll(req.workspaceId(), req.sessionId(), tasks);
                    listener.onEvent("task", tasks.size() + " 项任务已录入黑板");
                }
            }
            String taskHint = taskSummary(req, gates);

            // 2. 架构流程分析（能力开关裁剪）
            RoleResult arch = null;
            if (config.isArchitectureAnalysis()) {
                arch = runRoleOrReuse(ws, wfId, "architect", role("architect"),
                        "需求：\n" + req.message() + "\n\n项目经理规划：\n" + plan.output(), req, listener);
                append(context, "【技术实现方案】", arch.output());
            }

            // 3. 后端开发（java-maven）
            RoleResult backend = runRoleOrReuse(ws, wfId, "backend", role("backend"),
                    context + taskHint, req, listener);
            append(context, "【后端完成】", backend.output());
            boardProgress(req, listener, "backend", true, backend.output());

            // 4. 前端开发（frontend）
            RoleResult frontend = runRoleOrReuse(ws, wfId, "frontend", role("frontend"),
                    context + taskHint + "\n完成后端后将需求推进到前端页面实现。", req, listener);
            append(context, "【前端完成】", frontend.output());
            boardProgress(req, listener, "frontend", true, frontend.output());

            // 5. 审查/验证（端到端构建 + 真实门禁判定）
            RoleResult review = runRoleOrReuse(ws, wfId, "review", role("review"),
                    context.toString(), req, listener);
            append(context, "【验证结论】", review.output());

            boolean reviewPass = verifyGates(ws, wfId, gates, req, listener, review.output());
            boardProgress(req, listener, "review-gate", reviewPass, review.output());
            int attempts = 0;
            while (!reviewPass && config.isBugfix()) {
                if (attempts >= config.getGateMaxRetries()) {
                    break;
                }
                attempts++;
                // BUG 修改闭环：定位根因 → 最小改动 → 重跑验证
                RoleResult bugfix = runRoleOrReuse(ws, wfId, "bugfix#" + attempts, role("bugfix"),
                        "【失败信息】\n" + review.output() + "\n\n【需求】\n" + req.message(), req, listener);
                append(context, "【修复记录 #" + attempts + "】", bugfix.output());
                review = runRoleOrReuse(ws, wfId, "review#" + attempts, role("review"),
                        "请复验上一轮修复后的改动（修复产出）：\n" + bugfix.output() + "\n\n需求：\n" + req.message(),
                        req, listener);
                append(context, "【复验结论 #" + attempts + "】", review.output());
                reviewPass = verifyGates(ws, wfId, gates, req, listener, review.output());
            }
            if (!reviewPass) {
                if (runStore != null) {
                    runStore.setStatus(ws, wfId, "error");
                    runStore.recordMessage(ws, wfId, req.message());
                }
                listener.onEvent("error",
                        "门禁未通过，连续 " + attempts + " 次修复仍失败，工作流中止。失败与修复信息见上方输出。");
                return;
            }

            // 6. git 提交/交付（能力开关裁剪，提交前编译校验）
            if (config.isGitCommit()) {
                RoleResult git = runRoleOrReuse(ws, wfId, "git", role("git"),
                        context + "\n请在提交前完成编译校验并 commit。", req, listener);
                append(context, "【git 提交结论】", git.output());
            }

            // 7. 项目经理验收 + 总结（收口，经验回馈后清黑板）
            String boardProgress = boardProgressSummary(req);
            RoleResult summary = runRoleOrReuse(ws, wfId, "summary", role("summary"),
                    context + "\n\n【黑板进度】已完成阶段如下，供对照验收：\n" + boardProgress
                            + "\n请对照需求与各阶段门禁产出物完成项目验收并总结。", req, listener);
            if (config.isTaskBoard() && taskBoard != null) {
                taskBoard.completeAll(req.workspaceId(), req.sessionId());
            }
            if (runStore != null) {
                runStore.setStatus(ws, wfId, "done");
                runStore.recordMessage(ws, wfId, req.message());
            }
            reportWorkflow(req, wfId, "workflow", "done");
            listener.onEvent("done", summary.output());
        } catch (Exception e) {
            log.error("[workflow:{}] abort: {}", wfId, e.toString());
            if (runStore != null) {
                runStore.setStatus(ws, wfId, "error");
                runStore.recordMessage(ws, wfId, req.message());
            }
            reportWorkflow(req, wfId, "workflow", "error");
            listener.onEvent("error", "ERROR: " + e.getMessage());
        }
    }

    /** 断点续跑：已 done 阶段复用存档产出，未完成才真正执行。 */
    private RoleResult runRoleOrReuse(Workspace ws, String wfId, String phaseId,
                                      WorkflowConfig.Role role, String userInput,
                                      RunRequest req, Listener listener) throws InterruptedException {
        if (runStore != null && ws != null) {
            WorkflowRunStore.RunState st = runStore.load(ws, wfId);
            // 仅当存档 message 与本次需求完全一致才允许断点复用；旧存档(无 message)或
            // 需求变化一律按全新请求重跑，避免被同名 workflowId 误命中旧成果。
            if (st != null && (st.message() == null || !st.message().equals(req.message()))) {
                if (st != null) {
                    log.info("[workflow:{}] 需求与存档不一致，跳过断点复用，按新需求执行", wfId);
                    listener.onEvent("reuse", "需求已变化，重新执行 " + phaseId);
                }
            } else {
                WorkflowRunStore.PhaseRec p = (st != null && st.phases() != null)
                        ? st.phases().get(phaseId) : null;
                if (p != null && "done".equals(p.status())
                        && p.output() != null && !p.output().isBlank()) {
                    log.info("[workflow:{}] 复用已完成阶段 {} 产出", wfId, phaseId);
                    listener.onEvent("reuse", phaseId);
                    return new RoleResult(role.id(), p.output());
                }
            }
        }
        RoleResult r = runRole(wfId, role, userInput, req, listener);
        if (runStore != null && ws != null) {
            runStore.recordPhase(ws, wfId, phaseId, "done", r.output());
        }
        return r;
    }

    /** 把一段阶段产出作为进度写回黑板，并透传 progress SSE 事件。 */
    private void boardProgress(RunRequest req, Listener listener, String phase, boolean ok, String output) {
        if (config.isTaskBoard() && taskBoard != null) {
            String summary = (output == null || output.isBlank()) ? ""
                    : (output.length() > 160 ? output.substring(0, 160) : output)
                    .replaceAll("\\s+", " ").trim();
            taskBoard.recordProgress(req.workspaceId(), req.sessionId(), phase,
                    ok ? TaskBoard.Status.DONE : TaskBoard.Status.FAILED, summary);
            listener.onEvent("progress", phase + " " + (ok ? "DONE" : "FAILED"));
        }
    }

    /** 读黑板进度摘要，供验收角色对照。 */
    private String boardProgressSummary(RunRequest req) {
        if (config.isTaskBoard() && taskBoard != null) {
            String s = taskBoard.progressSummary(req.workspaceId(), req.sessionId());
            return s.isBlank() ? "（暂无阶段进度记录）" : s;
        }
        return "（任务黑板未开启）";
    }

    /** 把黑板任务 + 门禁清单拼进开发阶段提示，指导子 agent 定向实施。 */
    private String taskSummary(RunRequest req, List<GateSpec> gates) {
        StringBuilder sb = new StringBuilder();
        if (config.isTaskBoard() && taskBoard != null) {
            List<TaskBoard.Task> tasks = taskBoard.pending(req.workspaceId(), req.sessionId());
            if (!tasks.isEmpty()) {
                sb.append("\n\n【任务黑板】请按以下任务定向实施：\n");
                for (TaskBoard.Task t : tasks) {
                    sb.append("- ").append(t.id()).append(": ").append(t.description()).append("\n");
                }
            }
        }
        if (config.isDynamicGates() && gates != null && !gates.isEmpty()) {
            sb.append("\n【门禁清单】完成后须以退出码通过：\n");
            for (GateSpec g : gates) {
                sb.append("- ").append(g.name()).append(" (").append(g.kind()).append(")\n");
            }
        }
        return sb.toString();
    }

    /** 真实门禁判定：开启 realBuildGate 时以 mvn/npm 退出码为准，否则回落文本关键词扫描。 */
    private boolean verifyGates(Workspace ws, String wfId, List<GateSpec> gates,
                                RunRequest req, Listener listener, String reviewOutput) {
        if (!config.isRealBuildGate() || buildVerifier == null || ws == null) {
            return gate(reviewOutput);
        }
        boolean allPass = true;
        for (GateSpec g : gates) {
            BuildVerifier.GateResult r = buildVerifier.verify(ws, g, config.getBuildBashTimeout());
            emitGate(listener, r);
            reportWorkflow(req, wfId, "gate:" + g.id(), r.pass() ? "pass" : "fail");
            if (!r.pass()) {
                allPass = false;
            }
        }
        return allPass;
    }

    /** 门禁结果以 gate SSE 事件回流前端（失败同时给出收敛的 build 输出尾部）。 */
    private void emitGate(Listener listener, BuildVerifier.GateResult r) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", r.id());
            p.put("name", r.name());
            p.put("pass", r.pass());
            p.put("summary", r.summary());
            listener.onEvent("gate",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(p));
        } catch (Exception ignored) {
        }
    }

    /** 旁路上报一条工作流/阶段观测事件（layer=session, op=WRITE）。异常仅 WARN 不阻断。 */
    private void reportWorkflow(RunRequest req, String wfId, String memoryKey, String status) {
        if (reporter == null) {
            return;
        }
        try {
            String agentId = req.userId() == null || req.userId().isBlank() ? "workflow" : req.userId();
            String sessionId = req.sessionId() == null || req.sessionId().isBlank()
                    ? req.workspaceId() : req.sessionId();
            String summary = switch (status) {
                case "start" -> "工作流启动 · " + wfId;
                case "done" -> "工作流完成 · " + wfId;
                case "error" -> "工作流中止 · " + wfId;
                default -> "阶段 " + memoryKey + " · " + status;
            };
            Map<String, Object> f = EventReporter.fieldsBuilder();
            f.put("agentId", agentId);
            f.put("sessionId", sessionId);
            f.put("operation", "WRITE");
            f.put("layer", "session");
            f.put("memoryKey", memoryKey);
            f.put("memorySummary", summary);
            f.put("tokenCount", 0);
            f.put("latencyMs", 0);
            f.put("timestamp", java.time.Instant.now().toString());
            reporter.report(f);
        } catch (Exception e) {
            log.warn("[workflow] 阶段观测上报失败: {}", e.toString());
        }
    }

    /** 按 id 取角色；缺失（能力被裁剪）抛异常防御。 */
    private WorkflowConfig.Role role(String roleId) {
        for (WorkflowConfig.Role r : config.roles()) {
            if (r.id().equals(roleId)) {
                return r;
            }
        }
        throw new IllegalStateException("角色不存在（疑似能力开关裁剪异常）: " + roleId);
    }

    /** 运行单个角色阶段：创建独立 Agent、流式执行、收集最终文本并透传事件。 */
    private RoleResult runRole(String wfId, WorkflowConfig.Role role, String userInput,
                               RunRequest req, Listener listener) throws InterruptedException {
        log.info("[workflow:{}] phase start: {}", wfId, role.id());
        listener.onEvent("phase", role.id());
        reportWorkflow(req, wfId, "phase:" + role.id(), "start");

        HarnessAgent agent;
        try {
            agent = agentFactory.createRole(req.workspaceId(), role.template(), req.sessionId(),
                    req.userId(), role.prompt(), role.readOnly(), config.getBuildBashTimeout());
        } catch (Exception e) {
            log.error("[workflow:{}] create role failed {}: {}", wfId, role.id(), e.toString());
            listener.onEvent("error", "角色实例化失败 " + role.id() + ": " + e.getMessage());
            throw new IllegalStateException("角色实例化失败: " + role.id(), e);
        }

        Flux<AgentEvent> stream;
        try {
            Msg userMsg = Msg.builder().textContent(userInput).build();
            stream = agent.streamEvents(userMsg);
        } catch (Exception e) {
            throw new IllegalStateException("角色执行失败: " + role.id(), e);
        }

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        List<Throwable> errs = new ArrayList<>(1);
        stream.subscribe(
                ev -> note(ev, result, listener),
                err -> {
                    errs.add(err);
                    latch.countDown();
                },
                latch::countDown);

        if (!latch.await(config.getRoleTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("[workflow:{}] phase timeout: {}", wfId, role.id());
            throw new IllegalStateException("角色阶段超时: " + role.id());
        }
        if (!errs.isEmpty()) {
            // 工具/业务错误被 Agent 自身吸收后流通常正常完成；仅当抛出到订阅者才算失败
            log.warn("[workflow:{}] phase {} stream error: {}", wfId, role.id(), errs.get(0).toString());
        }

        listener.onEvent("phase_end", role.id());
        reportWorkflow(req, wfId, "phase:" + role.id(), "done");
        log.info("[workflow:{}] phase end: {} (chars={})", wfId, role.id(), result.length());
        return new RoleResult(role.id(), result.toString());
    }

    /** 记录 Agent 流式事件：token/tool 透传前端，AgentResultEvent 提取最终文本。 */
    private void note(AgentEvent ev, StringBuilder result, Listener listener) {
        try {
            if (ev instanceof TextBlockDeltaEvent t) {
                listener.onEvent("token", t.getDelta());
            } else if (ev instanceof ToolCallStartEvent t) {
                listener.onEvent("tool", t.getToolCallName());
            } else if (ev instanceof AgentResultEvent r && r.getResult() != null) {
                result.setLength(0);
                result.append(r.getResult().getTextContent());
            }
        } catch (Exception e) {
            // 前端断连等写失败属非致命，忽略
        }
    }

    /** 门禁机械化判定：产物文本含任一失败信号即判失败。 */
    private boolean gate(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        for (String s : WorkflowPrompts.FAILURE_SIGNALS) {
            if (output.contains(s)) {
                log.info("[workflow] gate fail by signal: {}", s);
                return false;
            }
        }
        return true;
    }

    /** 把一段产物追加进跨阶段上下文。 */
    private void append(StringBuilder ctx, String title, String content) {
        if (content == null || content.isBlank()) {
            ctx.append(title).append("：（无产出）\n\n");
            return;
        }
        ctx.append(title).append("\n").append(content).append("\n\n");
    }
}