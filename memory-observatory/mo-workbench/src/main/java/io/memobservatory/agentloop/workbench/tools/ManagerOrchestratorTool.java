/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】ManagerOrchestratorTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】工作区经理（admin Agent）专属协作编排工具集：让经理在自己的会话里
 *            「分析拆解需求 → 给出验收门禁 → 派发工作指令给工作区其他 Agent →
 *            汇总汇报 → 对照门禁验收 → 不通过则携带修改要求重新派发 → 直到通过」。
 *            复用现有 AgentFactory.create + BuildVerifier + GateParser 基建。
 * 【核心改动】2026-08-27 新增。经理驱动闭环的第 1 环：派发/收回/门禁/轮次治理。
 * 【设计要点】run_worker 在独立线程跑目标 Agent（有界超时），阻塞式收集其最终汇报，
 *            以摘要（截断）返回给经理判定；按 (工作区,Agent) 记录派发轮次，超过最大
 *            轮次机械性阻断，防死循环；新需求开始时由 reset_rounds 清零轮次。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.memobservatory.agentloop.workbench.core.AgentFactory;
import io.memobservatory.agentloop.workbench.core.SingleTurnExecutor;
import io.memobservatory.agentloop.workbench.hitl.ContextQueue;
import io.memobservatory.agentloop.workflow.BuildVerifier;
import io.memobservatory.agentloop.workflow.GateParser;
import io.memobservatory.agentloop.workflow.GateSpec;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import io.memobservatory.agentloop.workspace.manifest.AgentManifest;
import io.memobservatory.agentloop.workflow.BuildVerifier.GateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作区经理协作编排工具。仅装配到 admin（工作区经理）Agent，驱动其他 Agent 协同交付。
 */
public class ManagerOrchestratorTool {

    private static final Logger log = LoggerFactory.getLogger(ManagerOrchestratorTool.class);

    /** 单个 Agent 单次派发的最长执行时间（阻塞等待收集汇报）。 */
    private static final long WORKER_TIMEOUT_MS = 15 * 60_000L;
    /** 汇报文本截断长度，避免撑爆经理上下文。 */
    private static final int REPORT_CAP = 3000;
    /** 每个 (工作区,Agent) 的最大派发轮次，超过则机械性阻断（防死循环）。 */
    private static final int MAX_ROUNDS = 4;
    /** 派发工作指令的强约束六段（顺序固定，缺段即拒发）。子 Agent 依据这些段执行，完成后对照验收标准自查。 */
    private static final String[] DISPATCH_KEYS =
            {"目标", "输入结构", "输出结构", "完成指导思路", "验收标准", "注意事项"};
    /** 强约束格式模板：缺段时随拒发错误返回给经理，引导其补全。 */
    private static final String DISPATCH_TEMPLATE =
            "【目标】\n<要实现什么、达成什么效果>\n\n"
            + "【输入结构】\n<你拿到什么输入/数据/接口：字段、类型、约束>\n\n"
            + "【输出结构】\n<你应交付什么产出：格式/文件/接口返回/字段>\n\n"
            + "【完成指导思路】\n<建议的实施思路/关键步骤/技术选型>\n\n"
            + "【验收标准】\n<逐条硬性验收项（编号列表），子 Agent 逐条自检通过后才可汇报>\n\n"
            + "【注意事项】\n<边界、禁忌、必须遵守的约束>";
    /** 注入子 Agent 的强约束执行与自查验收要求（置于六段之后）。 */
    private static final String SELF_ACCEPTANCE =
            "【执行与自查验收要求】\n"
            + "1. 严格按【完成指导思路】实施，产出遵循【输入结构】与【输出结构】，全程遵守【注意事项】。\n"
            + "2. 完成后必须先对照【验收标准】逐条自查验收：逐条给出 通过/未通过(PASS/FAIL)，并附客观证据"
            + "（构建/测试退出码、样例输入输出、运行日志节选等）。\n"
            + "3. 自查必须全部通过后才向经理汇报；若有未达标项，先按指导思路与注意事项自行修正，直到全部达标；"
            + "确无法达成的，必须在汇报中明确列出未达标项与原因。\n"
            + "4. 汇报结构：《自查验收结论》(逐条 PASS/FAIL) → 改动摘要 → 验证方式 → 达标说明。";

    /** 校验工作指令是否合规：遍历六段，返回缺失/为空的段名列表；全部齐全返回 null。 */
    private static List<String> missingDispatchSections(String workOrder) {
        Map<String, String> sec = splitDispatchSections(workOrder);
        List<String> missing = new ArrayList<>();
        for (String k : DISPATCH_KEYS) {
            String v = sec.get(k);
            if (v == null || v.isBlank()) {
                missing.add("【" + k + "】");
            }
        }
        return missing.isEmpty() ? null : missing;
    }

    /** 按六段标记把工作指令拆成 {段名→内容}；仅保留已知段，其余自由文本忽略。 */
    private static Map<String, String> splitDispatchSections(String workOrder) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = workOrder == null ? "" : workOrder.strip();
        int pos = 0;
        while (pos < text.length()) {
            int mark = text.indexOf('【', pos);
            if (mark < 0) {
                break;
            }
            int close = text.indexOf('】', mark + 1);
            if (close < 0) {
                break;
            }
            String tag = text.substring(mark + 1, close).strip();
            int next = text.indexOf('【', close + 1);
            int end = (next < 0) ? text.length() : next;
            String content = text.substring(close + 1, end).strip();
            for (String k : DISPATCH_KEYS) {
                if (k.equals(tag)) {
                    out.put(tag, content);
                    break;
                }
            }
            pos = end;
        }
        return out;
    }

    /** 按固定顺序重建规范化六段式指令（丢失原顺序/多余空行），供注入子 Agent。 */
    private static String buildDispatchRepr(Map<String, String> sec) {
        StringBuilder sb = new StringBuilder();
        for (String k : DISPATCH_KEYS) {
            sb.append("【").append(k).append("】\n").append(sec.getOrDefault(k, "")).append("\n\n");
        }
        return sb.toString();
    }

    private final AgentFactory agentFactory;
    private final WorkspaceManager workspaceManager;
    private final Workspace workspace;
    private final String adminAgentId;
    private final String sessionId;
    private final String userId;
    /** 统一单轮执行入口（与 /chat 共用同一执行路径，跑派发子 Agent）。 */
    private final SingleTurnExecutor turnRunner;
    /** 会话补充上下文队列：执行子 Agent 前把其排队补充 pop 出来并进模型上下文，避免补充滞留不生效。 */
    private final ContextQueue contextQueue;
    /** 真实构建门禁校验器；可为 null，null 时 verify_gates 回落文本提示。 */
    private final BuildVerifier buildVerifier;
    /** 进度回传通道：非阻塞地把进度文本推给经理会话 SSE（可为 null，null 时静默）。 */
    private volatile java.util.function.Consumer<String> progressSink;
    /** 目标 Agent 运行态回传（agentId, running）：派发开始=run，完成/异常=!run，供前端在 Agent 按钮上显示“运行中…”。 */
    private volatile java.util.function.BiConsumer<String, Boolean> agentRunningSink;
    /** 思维链 plan 事件回传：非阻塞地把结构化 plan 事件推到经理会话 SSE（可为 null，null 时静默）。 */
    private volatile java.util.function.Consumer<String> planSink;
    /** 派发轮次计数：key=(workspaceId::agentId)。 */
    private final ConcurrentHashMap<String, Integer> rounds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ManagerOrchestratorTool(AgentFactory agentFactory, WorkspaceManager workspaceManager,
                                   Workspace workspace, String adminAgentId, String sessionId, String userId,
                                   SingleTurnExecutor turnRunner, ContextQueue contextQueue,
                                   BuildVerifier buildVerifier,
                                   java.util.function.Consumer<String> progressSink,
                                   java.util.function.BiConsumer<String, Boolean> agentRunningSink,
                                   java.util.function.Consumer<String> planSink) {
        this.agentFactory = agentFactory;
        this.workspaceManager = workspaceManager;
        this.workspace = workspace;
        this.adminAgentId = adminAgentId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.turnRunner = turnRunner;
        this.contextQueue = contextQueue;
        this.buildVerifier = buildVerifier;
        this.progressSink = progressSink;
        this.agentRunningSink = agentRunningSink;
        this.planSink = planSink;
    }

    /** 回传一段进度（静默兜底，不抛异常）。 */
    private void progress(String line) {
        try {
            java.util.function.Consumer<String> s = progressSink;
            if (s != null) {
                s.accept(line);
            }
        } catch (Exception ignored) {
            // 进度回传失败（如连接已断）不影响派发主流程
        }
    }

    /** 向经理会话推送目标 Agent 运行态（静默兜底）。 */
    private void agentRunning(String agentId, boolean running) {
        try {
            java.util.function.BiConsumer<String, Boolean> s = agentRunningSink;
            if (s != null) {
                s.accept(agentId, running);
            }
        } catch (Exception ignored) {
        }
    }

    /** 向经理会话 SSE 推送一条结构化 plan 事件（建节点/状态/一句思考），供前端思维树渲染（静默兜底）。 */
    private void plan(String json) {
        try {
            java.util.function.Consumer<String> s = planSink;
            if (s != null) {
                s.accept(json);
            }
        } catch (Exception ignored) {
        }
    }

    private String key(String agentId) {
        return (workspace.id() == null ? "" : workspace.id()) + "::" + agentId;
    }

    /** JSON 字符串转义（用于 plan 事件 payload 中的自由文本）。 */
    private static String jq(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** 从工作指令中提取「一句在干什么」：取首个换行前的部分，并压缩空白、截断到 <=80 字。 */
    private static String doingOneLine(String workOrder) {
        String s = (workOrder == null ? "" : workOrder).strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).strip();
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** 建计划节点：经理把任务拆给某 Agent。 */
    private void planNode(String agentId, String workOrder, int round, boolean parallel) {
        String name = agentId;
        try {
            for (AgentManifest am : workspaceManager.listAgents(workspace)) {
                if (agentId.equals(am.id()) && am.name() != null && !am.name().isBlank()) {
                    name = am.name();
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        plan("{\"t\":\"node\",\"id\":\"" + jq(agentId) + "\",\"round\":" + round
                + ",\"name\":\"" + jq(name) + "\",\"doing\":\"" + jq(doingOneLine(workOrder))
                + "\",\"detail\":\"" + jq(nodeDetail(workOrder))
                + "\",\"parallel\":" + parallel + "}");
    }

    /** 节点展开详情：下发任务的完整六段原文（与注入子 Agent 的版本一致），供思考树展开查看。 */
    private static String nodeDetail(String workOrder) {
        if (workOrder == null || workOrder.isBlank()) {
            return "";
        }
        return buildDispatchRepr(splitDispatchSections(workOrder));
    }

    /** 计划节点状态流转。 */
    private void planStatus(String agentId, int round, String status) {
        plan("{\"t\":\"status\",\"id\":\"" + jq(agentId) + "\",\"round\":" + round
                + ",\"status\":\"" + jq(status) + "\"}");
    }

    /** 补充队列 key（与 AgentController.queueKey 一致）：ws/agentId/sessionId；子 Agent 会话取其自身 agentId。 */
    private String queKey(String agentId) {
        return (workspace.id() == null ? "" : workspace.id()) + "/" + agentId + "/" + agentId;
    }

    /** 列出当前工作区可协作的其他 Agent（排除经理自己 admin）。 */
    @Tool(name = "list_workers",
            description = "列出当前工作区可派发工作指令的 Agent（排除经理自己）。返回每个 Agent 的 id、名称与描述，"
                    + "供经理选择工作指令接收方时使用。")
    public Mono<ToolResultBlock> listWorkers() {
        try {
            StringBuilder sb = new StringBuilder("当前工作区可协作的 Agent：\n");
            int n = 0;
            for (AgentManifest am : workspaceManager.listAgents(workspace)) {
                if (am.admin()) {
                    continue;
                }
                n++;
                sb.append("- id=").append(am.id())
                        .append("，名称=").append(am.name() == null ? "" : am.name())
                        .append("，描述=").append(am.description() == null ? "" : am.description())
                        .append("\n");
            }
            if (n == 0) {
                return Mono.just(ToolResultBlock.text("当前工作区没有可协作的其他 Agent。可先在管理卡片创建 Agent，或直接自己完成需求。"));
            }
            return Mono.just(ToolResultBlock.text("共 " + n + " 个可协作 Agent：\n" + sb));
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.text("枚举 Agent 失败: " + e.getMessage()));
        }
    }

    /** 派发工作指令给某 Agent，阻塞收集其完成汇报后返回（经理据此验收）。 */
    @Tool(name = "run_worker",
            description = "把一条工作指令派发给工作区指定 Agent 去执行，等待其完成后返回该 Agent 的完成汇报，"
                    + "供经理对照验收门禁判定是否通过。若验收不过需要该 Agent 继续修改，再次调用本工具并携带 roundHint"
                    + "（第几轮 + 具体修改要求）。同一次需求内对同一 Agent 的派发最多 "
                    + MAX_ROUNDS + " 轮，超限将被阻断以防死循环。")
    public Mono<ToolResultBlock> runWorker(
            @ToolParam(name = "agentId", description = "接收工作指令的工作区 Agent 的 id（不能是经理自己）")
            String agentId,
            @ToolParam(name = "workOrder",
                    description = "派发给该 Agent 的强约束工作指令，必须含六段（缺段将拒发）："
                            + "【目标】【输入结构】【输出结构】【完成指导思路】【验收标准】【注意事项】。"
                            + "子 Agent 会据此实施并完成后对照【验收标准】自查，通过后才向你汇报，再由你验收。")
            String workOrder,
            @ToolParam(name = "roundHint", required = false,
                    description = "如需继续修改时填写，形如：\"第2轮修改：...\"，含上一轮未通过原因与本轮修改要求；首次派发可省略")
            String roundHint) {
        if (agentId == null || agentId.isBlank()) {
            return Mono.just(ToolResultBlock.text("请提供合法的 agentId（用 list_workers 查看）。"));
        }
        int round = rounds.merge(key(agentId), 1, Integer::sum);
        if (round > MAX_ROUNDS) {
            return Mono.just(ToolResultBlock.text(
                    "已超出该 Agent 的最大派发轮次（" + MAX_ROUNDS + "）。请基于已有成果与汇报做最终验收判定："
                            + "若仍不通过，把未通过项与客观原因写进总结收尾，避免死循环。"));
        }
        // 强约束：派发指令必须含六段，缺失即拒发并回传模板，引导经理补全
        List<String> missing = missingDispatchSections(workOrder);
        if (missing != null) {
            return Mono.just(ToolResultBlock.text(
                    "派发指令不合规，缺少以下必需段：\n- " + String.join("\n- ", missing)
                            + "\n\n请按强约束格式重写 workOrder（六段齐全后才可派发）：\n\n" + DISPATCH_TEMPLATE
                            + "\n\n说明：子 Agent 依据这些段实施，完成后对照【验收标准】自查，通过后才向你汇报。"));
        }
        int r = round;
        log.info("[manager-orch] dispatch round#{} → agent={}", r, agentId);
        planNode(agentId, workOrder, r, false);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> runWorkerBlocking(agentId, workOrder, roundHint, r), executor);
        return Mono.fromFuture(future)
                .map(ToolResultBlock::text)
                .onErrorResume(e -> Mono.just(ToolResultBlock.text(
                        "派发执行异常（" + agentId + " round#" + r + "）: " + e.getMessage())));
    }

    /**
     * 并行派发工具：把多条工作指令同时交给多个工作区 Agent 并行执行，全部完成后一并返回各自的完成汇报，
     * 供经理汇总验收。入参每行一条 `agentId::工作指令`，多行即多个 Agent 并行。适用于子任务相互独立、
     * 可并行的需求（节省总耗时），等最后一个完成才汇总返回。各 Agent 也按 MAX_ROUNDS 独立防空循环。
     */
    @Tool(name = "run_parallel",
            description = "把多条工作指令同时派发给多个工作区 Agent 并行执行，全部完成后再一起返回各自的完成汇报，"
                    + "供经理汇总验收。入参 workItems 每行一条，格式 `agentId::工作指令`；多行=多个 Agent 并行。"
                    + "适用于可并行拆分的子任务。总耗时约等于其中最慢一个 Agent 的耗时。")
    public Mono<ToolResultBlock> runParallel(
            @ToolParam(name = "workItems",
                    description = "多行；每行形如 `agentId::工作指令`，指定该 Agent 的任务与验收要求。至少 2 行才有并行意义。")
            String workItems) {
        List<String[]> items = new ArrayList<>();
        if (workItems != null) {
            for (String line : workItems.split("\n")) {
                line = line == null ? "" : line.strip();
                if (line.isBlank()) {
                    continue;
                }
                int sep = line.indexOf("::");
                if (sep <= 0) {
                    continue;
                }
                items.add(new String[]{line.substring(0, sep).strip(), line.substring(sep + 2).strip()});
            }
        }
        if (items.isEmpty()) {
            return Mono.just(ToolResultBlock.text("未解析到有效的派发条目。每行格式应为：`agentId::工作指令`。"));
        }
        if (items.size() < 2) {
            return Mono.just(ToolResultBlock.text("并行派发至少需要 2 个条目；1 个请用 run_worker。"));
        }
        // 轮次预检：任一 Agent 超限则整体拒绝，避免部分下发后卡死。
        int[] roundsThis = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            int r = this.rounds.merge(key(items.get(i)[0]), 1, Integer::sum);
            if (r > MAX_ROUNDS) {
                return Mono.just(ToolResultBlock.text(
                        "Agent[" + items.get(i)[0] + "] 已达到最大派发轮次（" + MAX_ROUNDS + "）。请基于已有成果收尾验收，避免死循环。"));
            }
            roundsThis[i] = r;
        }
        // 强约束：每个条目的工作指令都须含六段，任一缺失即整体拒发并回传明细
        List<String> badLines = new ArrayList<>();
        for (String[] it : items) {
            List<String> m = missingDispatchSections(it[1]);
            if (m != null) {
                badLines.add(it[0] + "：缺少 " + String.join("、", m));
            }
        }
        if (!badLines.isEmpty()) {
            return Mono.just(ToolResultBlock.text(
                    "并行派发不合规，以下 Agent 的指令缺段：\n- " + String.join("\n- ", badLines)
                            + "\n\n请按强约束格式重写对应 workOrder（六段齐全后才可派发）：\n\n" + DISPATCH_TEMPLATE));
        }
        progress("⏳ 并行派发 " + items.size() + " 个 Agent 同时执行…");
        for (int i = 0; i < items.size(); i++) {
            planNode(items.get(i)[0], items.get(i)[1], roundsThis[i], true);
        }
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int idx = i;
            futures.add(CompletableFuture.supplyAsync(
                    () -> runWorkerBlocking(items.get(idx)[0], items.get(idx)[1], null, roundsThis[idx]),
                    executor));
        }
        return Mono.fromCompletionStage(CompletableFuture
                // 全部完成后统一聚合
                .allOf(futures.toArray(new CompletableFuture[0]))
                .handle((v, e) -> {
                    StringBuilder agg = new StringBuilder("▶ 并行派发 " + items.size() + " 个 Agent，全部完成，汇总如下：\n");
                    for (int i = 0; i < items.size(); i++) {
                        agg.append("\n===== Agent[").append(items.get(i)[0]).append("] =====\n");
                        agg.append(futures.get(i).getNow("（该 Agent 无返回）"));
                    }
                    agg.append("\n\n请对照各自验收门禁汇总判定是否全部通过。");
                    return agg.toString();
                }))
                .map(ToolResultBlock::text)
                .onErrorResume(e -> Mono.just(ToolResultBlock.text(
                        "并行派发异常: " + e.getMessage())));
    }

    /** 在工作区「展示」面板打开/预览一个网页给用户查看（生成页面后主动展示，让用户看到成果）。
     *  支持本机文件（绝对路径或相对工作区根，走服务端预览）与外部 http(s) 网址（iframe 直接打开）。
     *  注意：部分外部站点设置了 X-Frame-Options/CSP，拒绝被 iframe 嵌套，会显示空白或拒绝；本地文件最稳妥。 */
    @Tool(name = "open_webpage",
            description = "在工作区右侧/中间「展示」面板打开一个网页供用户查看。生成页面后应主动调用，让用户看到成果。"
                    + "支持两种：url=外部网页地址（http/https，iframe 直接打开）；path=本机文件（绝对路径或相对工作区根，服务端预览）。"
                    + "二者传其一即可。外部站点若拒绝 iframe 嵌套可能显示空白。")
    public Mono<ToolResultBlock> openWebpage(
            @ToolParam(name = "url", required = false,
                    description = "要打开的外部网页地址，须以 http:// 或 https:// 开头；与 path 二选一。")
            String url,
            @ToolParam(name = "path", required = false,
                    description = "要预览的本机文件绝对路径，或相对工作区根的路径；与 url 二选一。")
            String path) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasPath = path != null && !path.isBlank();
        if (!hasUrl && !hasPath) {
            return Mono.just(ToolResultBlock.text("请提供 url 或 path 至少其一，才能在展示面板打开网页。"));
        }
        try {
            if (hasUrl) {
                String u = url.strip();
                if (!(u.startsWith("http://") || u.startsWith("https://"))) {
                    return Mono.just(ToolResultBlock.text("url 必须是 http(s) 外部地址；本机文件请改用 path。"));
                }
                sendWeb("url", u);
                return Mono.just(ToolResultBlock.text("已在「工作区展示」打开外部网页：" + u
                        + "（若该站点禁止 iframe 嵌套会显示空白，可让用户浏览器直接访问）。"));
            }
            String p = resolveLocalPath(path.strip());
            if (p == null) {
                return Mono.just(ToolResultBlock.text("无法解析路径，请用本机绝对路径或相对工作区根路径。"));
            }
            if (!Files.isRegularFile(Path.of(p))) {
                return Mono.just(ToolResultBlock.text("该路径不是可预览的文件（不存在或非普通文件）: " + p));
            }
            sendWeb("file", p);
            return Mono.just(ToolResultBlock.text("已在「工作区展示」预览本机文件：" + p));
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.text("打开网页失败: " + e.getMessage()));
        }
    }

    /** 把地址解析为服务端可 serve 的绝对路径：已是绝对路径直接用；否则按工作区根解析。 */
    private String resolveLocalPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) {
            p = workspace.root().resolve(p);
        }
        return p.normalize().toString();
    }

    /** 向经理会话 SSE 推一个网页展示指令，前端据此把「工作区展示」的 iframe 指过去。 */
    private void sendWeb(String kind, String target) {
        try {
            plan("{\"t\":\"web\",\"kind\":\"" + jq(kind) + "\",\"url\":\"" + jq(target) + "\"}");
        } catch (Exception ignored) {
        }
    }

    /** 执行单个目标 Agent：新建只读无关的独立实例，注入工作指令，收集最终汇报。 */
    private String runWorkerBlocking(String agentId, String workOrder, String roundHint, int round) {
        StringBuilder out = new StringBuilder();
        out.append("▶ 第 ").append(round).append(" 轮派发 Agent[").append(agentId).append("]")
                .append((roundHint == null || roundHint.isBlank()) ? "" : "（" + roundHint.trim().replace('\n', ' ') + "）")
                .append("\n");
        agentRunning(agentId, true);
        planStatus(agentId, round, "running");
        progress("⏳ 第 " + round + " 轮正在派发 Agent[" + agentId + "] 执行，请稍候…");
        StringBuilder userMsg = new StringBuilder();
        if (roundHint != null && !roundHint.isBlank()) {
            userMsg.append(roundHint.strip()).append("\n\n");
        }
        // 把该子 Agent 在派发期间排队的用户补充上下文逐条 pop 出来，并入本次工作指令
        // （经理用 run_worker/run_parallel 直接建 ReActAgent 跑子 Agent，不经 /chat dispatch，
        //   若不清这里，补充会一直滞留在队列、进不了子 Agent 的模型上下文）。
        StringBuilder supplements = new StringBuilder();
        ContextQueue cq = this.contextQueue;
        if (cq != null) {
            String qk = queKey(agentId);
            String s;
            while ((s = cq.poll(qk)) != null) {
                supplements.append("- ").append(s).append("\n");
            }
        }
        if (supplements.length() > 0) {
            userMsg.append("【用户排队补充（请一并处理）】\n").append(supplements);
        }
        userMsg.append("【工作指令】\n")
                // 强约束：把六段的规范化版本原样下发，并追加自查验收要求；
                // 子 Agent 据此实施，完成后先对照【验收标准】自查通过，才向经理汇报。
                .append(buildDispatchRepr(splitDispatchSections(workOrder)))
                .append(SELF_ACCEPTANCE);
        try {
            // 以目标 Agent 自己的清单 + 工作区根为边界注入本次工作指令上下文，
            // 并载入其已有会话记忆（跨轮连续），使目标 Agent 能感知“经理派发给我的任务”。
            String existing = agentFactory.sessionMemoryContext(workspace.id(), agentId, agentId);
            String injected = "【来自工作区经理的工作派发】\n" + userMsg
                    + (existing == null || existing.isBlank() ? "" : "\n\n【你此前的会话记忆】\n" + existing);
            // 统一单轮执行入口：建子 Agent + 跑流 + 落盘其会话记忆，文本由执行器收集经 onDone 回读。
            // 目标 Agent 自身的会话已由执行器统一落盘（与 /chat 同一执行路径）。
            long start = System.currentTimeMillis();
            AtomicReference<String> reportRef = new AtomicReference<>(null);
            AtomicBoolean timedOut = new AtomicBoolean(false);
            CountDownLatch gate = new CountDownLatch(1);
            SingleTurnExecutor.TurnSpec spec = new SingleTurnExecutor.TurnSpec(
                    workspace.id(), agentId, agentId, userId, userMsg.toString(), injected,
                    this::progress, this::agentRunning, null,
                    Duration.ofMillis(WORKER_TIMEOUT_MS),
                    () -> progress("⏳ Agent[" + agentId + "] 仍在执行（已等待 "
                            + ((System.currentTimeMillis() - start) / 1000) + "s）…"));
            turnRunner.runTurn(spec, ev -> {
            }, new SingleTurnExecutor.TurnListener() {
                @Override
                public void onDone(SingleTurnExecutor.TurnResult r) {
                    reportRef.set(r.assistantText());
                    planStatus(agentId, round, "done");
                    gate.countDown();
                }

                @Override
                public void onError(Throwable t) {
                    out.append("目标 Agent 执行出错: ").append(t.getMessage()).append("\n");
                    planStatus(agentId, round, "error");
                    progress("⚠️ Agent[" + agentId + "] 执行出错: " + t.getMessage());
                    gate.countDown();
                }

                @Override
                public void onTimeout() {
                    timedOut.set(true);
                    planStatus(agentId, round, "timeout");
                    gate.countDown();
                }
            });
            // 阻塞等待单轮收尾（看门狗已按超时中断，此处仅兜底等待）；完成后回读汇报
            try {
                gate.await(WORKER_TIMEOUT_MS + 60_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            String report = reportRef.get();
            String reportText = (report == null || report.isBlank())
                    ? (timedOut.get() ? "（执行超时，未收到完整汇报）" : "（该 Agent 无文本汇报）") : report;
            // 写入经理自己的会话记忆，让「经理面板」留档这次派发与回报（目标 Agent 会话由执行器落盘）。
            agentFactory.recordSessionTurn(workspace.id(), adminAgentId, sessionId,
                    "（工作区经理 → Agent[" + agentId + "] 第 " + round + " 轮派发）\n" + userMsg,
                    reportText);
            if (report == null || report.isBlank()) {
                out.append(reportText);
            } else {
                out.append("\n【").append(agentId).append(" 完成汇报】\n")
                        .append(report.length() > REPORT_CAP ? report.substring(0, REPORT_CAP) + "\n…（汇报较长已截断）" : report);
            }
            progress("✅ Agent[" + agentId + "] 第 " + round + " 轮已完成，请对照门禁验收。");
        } catch (Exception e) {
            out.append("执行异常: ").append(e.getMessage()).append("\n");
            progress("⚠️ Agent[" + agentId + "] 派发异常: " + e.getMessage());
        } finally {
            agentRunning(agentId, false);
        }
        return out.toString();
    }

    /** 对照门禁清单跑真实构建（mvn test / npm run build），把逐门禁 PASS/FAIL 返回给经理验收。 */
    @Tool(name = "verify_gates",
            description = "把经理给出的验收门禁块交给真实构建校验器执行（backend→`mvn -q test`，frontend→`npm run build`），"
                    + "返回每条门禁的通过与否与构建尾部输出。用于在经理验收阶段用真实构建结果佐证判定。")
    public Mono<ToolResultBlock> verifyGates(
            @ToolParam(name = "gateBlock",
                    description = "验收门禁清单文本，每行形如：`- 后端构建 | backend | 说明` 或 `- 前端构建 | frontend | 说明`")
            String gateBlock) {
        if (buildVerifier == null) {
            return Mono.just(ToolResultBlock.text("真实构建门禁校验器未启用，请改由工具结果/汇报文本自行判定验收。"));
        }
        List<GateSpec> gates = GateParser.parseGates(gateBlock == null ? "" : gateBlock);
        StringBuilder sb = new StringBuilder("门禁校验结果（逐一对照）：\n");
        for (GateSpec g : gates) {
            try {
                GateResult r = buildVerifier.verify(workspace, g, Duration.ofMinutes(15));
                sb.append("- [").append(r.pass() ? "通过" : "未通过").append("] ").append(r.name())
                        .append("（").append(g.kind()).append("）\n");
                String s = r.summary();
                if (s != null && !s.isBlank()) {
                    String tail = s.replace("\r", "").strip();
                    int cut = Math.min(tail.length(), 800);
                    sb.append("  ").append(tail, 0, cut).append(cut < tail.length() ? "…" : "").append("\n");
                }
            } catch (Exception e) {
                sb.append("- [异常] ").append(g.name()).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return Mono.just(ToolResultBlock.text(sb.toString()));
    }

    /** 清零派发轮次计数：在新需求开始编排前调用，避免沿用上个需求的轮次。 */
    @Tool(name = "reset_rounds",
            description = "清零当前工作区所有 Agent 的派发轮次计数。每当开始编排一个新的独立需求前调用一次，避免死循环治理误判。")
    public Mono<ToolResultBlock> resetRounds() {
        rounds.clear();
        return Mono.just(ToolResultBlock.text("已清零派发轮次计数，可开始新一轮需求编排。"));
    }
}