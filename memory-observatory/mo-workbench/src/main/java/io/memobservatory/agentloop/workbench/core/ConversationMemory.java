/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】ConversationMemory.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】Agent 全部「上下文 + 内存」逻辑聚合点，统一在一个文件便于检查。含三层：
 *  ① ConversationMemory：会话上下文管理（存时压路线）。融入官方 AgentScope 状态管理，
 *     以 AgentStateStore 持久化 + AgentState 承接会话（上下文 List<Msg> + 可持久化
 *     summary）。采用「三层分区 + 四轮监控」：L1 单条超长保头尾截断，L2 超过 KEEP_RECENT
 *     轮把最旧一轮折叠，L3 累计摘要达阈值才调模型重摘要（否则规则累加兜底），L4 主动清空
 *     开新上下文。注出历史按［早期要旨/中段折叠/最近原文］三段组织。
 *  ② ContextBudget：五区上下文预算模型（隔离 + 计量 + 判定）。把喂入模型的上下文按
 *     SYSTEM/TASK/MEMORY/TOOL/FREE 五区独立归位计量，再按阈值规则产出 Decision。
 *  ③ ContextBudgetMiddleware：五区预算运行时判定钩子（纯计量 + 判定，不改写模型输入）。
 *     装配期只能计量 SYSTEM/MEMORY；TOOL 区在推理时注入，故运行时补齐 TASK 与 TOOL。
 * 【路线约定】「存时压」为唯一压缩路线（已删除并取代「看时压」ContextCompressionMiddleware）。
 * 【设计要点】状态槽持久化由官方 JsonFileAgentStateStore 负责（原子写、安全目录段）；
 *            ConversationMemory 只做会话编排；ContextBudget 只做计量与判定，不改写输入；
 *            ContextBudgetMiddleware 每次调用持有各自闭包状预算（seed 深拷贝），并发安全。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/* =============================================================================
 * ① 会话上下文管理（存时压）
 * ========================================================================== */

/**
 * 按会话粒度管理上下文，后端为官方 {@link AgentStateStore} + {@link AgentState}。
 * 每个会话 = 一个 AgentState：{@code context} 保留最近 KEEP_RECENT 轮用户原文，
 * {@code summary} 存运行摘要（L3 折叠产物，可持久化）。四轮监控逐级触发，状态按官方
 * 槽键持久化，服务重启后同一会话可恢复记忆。
 */
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** 保留原文的最近用户轮数（L2 折叠阈值）。 */
    public static final int KEEP_RECENT = 5;

    /** L1 单条消息超过该长度时保头尾截断（避免单次长话/长工具结果撑爆注出窗口）。 */
    private static final int L1_MAX_TEXT = 12_000;
    private static final int L1_HEAD = 6_000;
    private static final int L1_TAIL = 2_000;

    /** L3 重摘要阈值：累计摘要超过该字符数才调用模型重摘要；否则规则累加兜底。 */
    private static final int L3_MIN_CHARS = 2_000;

    /** 规则累加后的摘要上限（兜底，保证摘要不无限增长）。 */
    private static final int MAX_SUMMARY_CHARS = 2_000;

    /** 状态持久化 key，与官方 ReActAgent 保存 {@code agent_state} 用同一键，便于互操作。 */
    private static final String STATE_KEY = "agent_state";

    private final AgentStateStore store;

    /** 官方 JsonFile store 的根目录（仅在注入的确实是该实现时可用，用于累计轮次伴生文件）。 */
    private final java.nio.file.Path storeRoot;

    public ConversationMemory(AgentStateStore store) {
        this.store = store;
        this.storeRoot = (store instanceof JsonFileAgentStateStore s) ? s.getRootDirectory() : null;
    }

    /* ---- 会话累计轮次（持久化伴生文件，重启不丢，供悬浮窗记录轮次）----
     * 累计轮次无官方槽可变字段可放，故在官方 store 同目录落 turns.json（只读/写，纯数字）。
     * 路径规则与官方一致：root/context/<base64url(userId)>/<sessionId>/turns.json。 */
    private Path turnFile(Resolved r) {
        if (storeRoot == null) {
            return null;
        }
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(r.userId().getBytes(StandardCharsets.UTF_8));
        return storeRoot.resolve("context").resolve(b64).resolve(r.sessionId()).resolve("turns.json");
    }

    private Path toolFile(Resolved r) {
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(r.userId().getBytes(StandardCharsets.UTF_8));
        return storeRoot.resolve("context").resolve(b64).resolve(r.sessionId()).resolve("tools.json");
    }

    private long loadToolChars(Resolved r) {
        Path f = toolFile(r);
        if (f == null || !Files.isRegularFile(f)) {
            return 0;
        }
        try {
            return Long.parseLong(Files.readString(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveToolChars(Resolved r, long n) {
        Path f = toolFile(r);
        if (f == null) {
            return;
        }
        try {
            Path p = f.getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
            Files.writeString(f, Long.toString(n), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 工具计量落盘失败不阻断
        }
    }

    /** 追加本轮累计工具输出字符到会话持久化计量（TOOL 区占用展示）。 */
    public void recordTool(String key, long chars) {
        Resolved r = resolve(key);
        if (chars <= 0) {
            return;
        }
        synchronized (this) {
            long cur = loadToolChars(r);
            saveToolChars(r, cur + chars);
        }
    }

    /** 读该会话累计工具输出字符（TOOL 区已用）。 */
    public long toolChars(String key) {
        return loadToolChars(resolve(key));
    }

    private int loadTurns(Resolved r) {
        Path f = turnFile(r);
        if (f == null || !Files.isRegularFile(f)) {
            return 0;
        }
        try {
            return Integer.parseInt(Files.readString(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveTurns(Resolved r, int n) {
        Path f = turnFile(r);
        if (f == null) {
            return;
        }
        try {
            Path p = f.getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
            Files.writeString(f, Integer.toString(n), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 轮次落盘失败不阻断对话
        }
    }

    /* ---- 折叠足迹（轻量溯源：记录折叠时间 + 该轮用户问题摘句，不保留全文）---- */
    private static final int MAX_FOLD_TRAIL = 20;
    private static final int FOLD_SNIPPET = 40;

    private Path foldTrailFile(Resolved r) {
        if (storeRoot == null) {
            return null;
        }
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(r.userId().getBytes(StandardCharsets.UTF_8));
        return storeRoot.resolve("context").resolve(b64).resolve(r.sessionId()).resolve("fold-trail.txt");
    }

    /** 记录一次折叠：时间 + 该轮用户问题首 FOLD_SNIPPET 字，追加并保留最近 MAX_FOLD_TRAIL 条。 */
    private void recordFold(Resolved r, String userText) {
        Path f = foldTrailFile(r);
        if (f == null) {
            return;
        }
        try {
            Path p = f.getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
            String snip = (userText == null ? "" : userText).replace('\n', ' ').replace('\t', ' ');
            if (snip.length() > FOLD_SNIPPET) {
                snip = snip.substring(0, FOLD_SNIPPET) + "…";
            }
            String line = System.currentTimeMillis() + "\t" + snip;
            java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
            if (Files.isRegularFile(f)) {
                for (String l : Files.readAllLines(f)) {
                    if (!l.isBlank()) {
                        lines.addLast(l);
                    }
                }
            }
            lines.addLast(line);
            while (lines.size() > MAX_FOLD_TRAIL) {
                lines.pollFirst();
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 折叠足迹落盘失败不阻断
        }
    }

    /** 读取某会话最近折叠足迹（新→旧）。无则空表。 */
    public java.util.List<java.util.Map<String, Object>> foldTrail(String key) {
        Path f = foldTrailFile(resolve(key));
        if (f == null || !Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            java.util.List<java.util.Map<String, Object>> out = new ArrayList<>();
            for (String l : Files.readAllLines(f)) {
                int t = l.indexOf('\t');
                out.add(0, java.util.Map.of(
                        "ts", t > 0 ? Long.parseLong(l.substring(0, t)) : 0L,
                        "snippet", t > 0 ? l.substring(t + 1) : l));
            }
            return out.stream().limit(MAX_FOLD_TRAIL).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /* ---- 记忆归档（可找回全文）：把被折叠/压缩的轮次整轮原文落盘，供 Agent 用工具按 id 召回 ----
     * 折叠只把摘句留进摘要，原文会被压缩洗掉；此处把整轮（user+assistant）原文按唯一 id 归档落盘，
     * 并在摘要里内嵌「可找回标记」（含 id/路径/摘句/召回工具），需要细节时 Agent 可调用工具取回全文。 */

    /** 归档文件路径：会话目录下 archive/<archiveId>.json（与官方 store 同根，随会话隔离）。 */
    private Path archiveFile(Resolved r, String archiveId) {
        if (storeRoot == null) {
            return null;
        }
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(r.userId().getBytes(StandardCharsets.UTF_8));
        return storeRoot.resolve("context").resolve(b64)
                .resolve(r.sessionId()).resolve("archive").resolve(archiveId + ".json");
    }

    /** 归档相对路径（相对会话目录），作为「可找回标记」里的回调锚点（标记从哪找回）。 */
    private static String archiveRelative(String archiveId) {
        return "archive/" + archiveId + ".json";
    }

    /** 把一轮（user+assistant）完整原文归档落盘并返回 archiveId；失败或无可落盘根返回 null。
     *  用 nano 时间戳作 id：折叠/压缩常在同一毫秒内连续折叠多轮，须保证每轮 id 唯一防覆盖。 */
    private String archiveRound(Resolved r, String userText, String asstText) {
        String id = Long.toString(System.nanoTime());
        Path f = archiveFile(r, id);
        if (f == null) {
            return null;
        }
        try {
            Path p = f.getParent();
            if (p != null) {
                Files.createDirectories(p);
            }
            String body = "【归档 id=" + id + "】该轮完整原文（用户/助手两段）：\n"
                    + "———— 用户 ————\n" + (userText == null ? "" : userText)
                    + "\n———— 助手 ————\n" + (asstText == null ? "" : asstText);
            Files.writeString(f, body, StandardCharsets.UTF_8);
            return id;
        } catch (Exception ignored) {
            return null; // 归档落盘失败不阻断折叠（无非这轮不可找回）
        }
    }

    /** 按 archiveId 读回某次折叠/压缩的完整原文；id 不存在返回明确提示。供 Agent 召回工具调用。 */
    public String findArchive(String key, String archiveId) {
        if (archiveId == null || archiveId.isEmpty()) {
            return "ERROR: 缺少归档 id，请提供折叠标记里的 archiveId 参数。";
        }
        Resolved r = resolve(key);
        Path f = archiveFile(r, archiveId);
        if (f == null || !Files.isRegularFile(f)) {
            return "ERROR: 未找到归档 id=" + archiveId + "，请确认该 id 来自某条【记忆归档】标记。";
        }
        try {
            return "已找回归档 " + archiveId + " 的完整原文：\n" + Files.readString(f);
        } catch (Exception e) {
            return "ERROR: 读取归档失败: " + e.getMessage();
        }
    }

    /** 会话隔离键（由 Controller 生成）：{workspaceId}/{agentId}/{sessionId}。 */
    public static String key(String workspaceId, String agentId, String sessionId) {
        return workspaceId + "/" + agentId + "/" + sessionId;
    }

    /** 当前会话的历史上下文块，按三层结构组织；无历史返回 null。
     *  <pre>
     *  【早期要旨】   → L3 早期索引：AgentState.summary
     *  【中段折叠】   → L2 中间占位：被折叠进要旨的更早轮次数
     *  【最近原文】   → L1 最近 KEEP_RECENT 轮完整 user/assistant
     *  </pre> */
    public String context(String key) {
        AgentState st = load(key);
        if (st == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<Msg> ctx = st.getContext();
        String summary = st.getSummary() == null ? "" : st.getSummary();
        if (!summary.isBlank()) {
            sb.append("【早期要旨】\n").append(summary).append("\n");
            // L2 中段折叠标记：更早轮已编译进要旨，提示模型早期细节到此为止
            sb.append("【中段折叠】\n更早的对话已折叠为上摘要，如需较早细节请明确追问。\n");
        }
        sb.append("【最近原文】\n");
        for (int i = 0; i < ctx.size(); i += 2) {
            sb.append("- 用户：").append(ctx.get(i).getTextContent()).append("\n");
            if (i + 1 < ctx.size()) {
                sb.append("  助手：").append(ctx.get(i + 1).getTextContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 只读快照：供前端记忆面板轮询展示（早期要旨/最近原文两层 + MEMORY 字符估算）。
     *  folded=||summary非空 表示发生过 L2 折叠（中段折叠已并进要旨）。 */
    public MemorySnapshot snapshot(String key) {
        Resolved r = resolve(key);
        AgentState st = load(r);
        if (st == null) {
            return new MemorySnapshot(false, "", 0, 0, false, 0, 0, loadTurns(r), loadToolChars(r), List.of());
        }
        String summary = st.getSummary() == null ? "" : st.getSummary();
        long summaryChars = summary.length();
        long recentChars = 0;
        List<RecentTurn> recent = new java.util.ArrayList<>();
        List<Msg> ctx = st.getContext();
        for (int i = 0; i < ctx.size(); i++) {
            String c = ctx.get(i).getTextContent();
            if (c != null) {
                recentChars += c.length();
            }
        }
        // 按 用户+助手 成对组织最近原文，供前端渲染成气泡（普通对话与经理派发均在此）
        for (int i = 0; i < ctx.size(); i += 2) {
            Msg u = ctx.get(i);
            Msg a = i + 1 < ctx.size() ? ctx.get(i + 1) : null;
            recent.add(new RecentTurn(
                    u == null || u.getTextContent() == null ? "" : u.getTextContent(),
                    a == null || a.getTextContent() == null ? "" : a.getTextContent()));
        }
        return new MemorySnapshot(true, summary, countUserTurns(ctx),
                summaryChars + recentChars, !summary.isBlank(), summaryChars, recentChars,
                // 累计轮次：优先读伴生文件；遗留会话未生成过 turns.json 时回退为最近原文轮次，避免显示 0
                loadTurns(r) > 0 ? loadTurns(r) : countUserTurns(ctx),
                loadToolChars(r), recent);
    }

    /** 最近原文中的一轮（user + assistant）。供前端在切换会话时把后端记忆（含工作区经理派发）渲染成气泡。 */
    public record RecentTurn(String user, String assistant) {
    }

    /** 记忆面板只读快照：exists/摘要/recentTurns/总字符 + 三层拆分（folded、要旨字符、原文字符）+ 累计轮次 + TOOL 累计字符。 */
    public record MemorySnapshot(boolean exists, String summary, int recentTurns, long estChars,
                                 boolean folded, long summaryChars, long recentChars, int totalTurns,
                                 long toolChars, List<RecentTurn> recent) {
    }

    /** 记录一轮对话。四轮监控：L1 单条超长先截断；超阈值（L2）把最旧一轮折叠进摘要，
     *  摘要累计达阈值（L3）才调模型重摘要；L4 由 {@link #clear} 主动清空触发。 */
    public void append(String key, String user, String assistant, ContextSummarizer summarizer) {
        Resolved r = resolve(key);
        synchronized (this) {
            AgentState st = load(r);
            if (st == null) {
                st = AgentState.builder().userId(r.userId()).sessionId(r.sessionId()).build();
            }
            List<Msg> ctx = st.contextMutable();
            // L1：写入前对单条超长做保头尾截断（存时压，落库即减）
            ctx.add(userMsg(truncateL1(user)));
            ctx.add(assistantMsg(truncateL1(assistant)));
            int userTurns = countUserTurns(ctx);
            // L2：超过保留轮数，每次把最旧一轮折叠进摘要，逐轮收敛
            while (userTurns > KEEP_RECENT) {
                Msg oldestUser = ctx.get(0);
                Msg oldestAsst = ctx.size() > 1 ? ctx.get(1) : null;
                // 轻量折叠足迹：记录该轮用户问题摘句，供面板溯源"何时折了哪几轮"
                recordFold(r, oldestUser == null ? "" : oldestUser.getTextContent());
                fold(st, r, oldestUser, oldestAsst, summarizer);
                ctx.remove(0);
                if (oldestAsst != null && ctx.size() > 0) {
                    ctx.remove(0);
                }
                userTurns = countUserTurns(ctx);
            }
            save(r, st);
        }
        // 累计会话轮次（伴生文件，持久化）：每完成一轮用户对话 +1
        synchronized (this) {
            saveTurns(r, loadTurns(r) + 1);
        }
    }

    /** 清空某会话上下文（L4：主动开新上下文，等价于删除该槽）。 */
    public void clear(String key) {
        Resolved r = resolve(key);
        try {
            store.delete(r.userId(), r.sessionId());
        } catch (Exception e) {
            log.warn("[ConversationMemory] 清空会话上下文失败（不阻断）: {}", e.toString());
        }
        try {
            Path f = turnFile(r);
            if (f != null) {
                Files.deleteIfExists(f);
            }
        } catch (Exception ignored) {
            // 清空轮次伴生文件失败不阻断
        }
    }

    /** 预算超限后触发「系统自收敛」：把当前会话 MEMORY 收紧到 targetMemChars 以内。
     *  压缩按「使用模型成本」由低到高分层（L1 最低 → L4 最高），宁可多叠规则裁剪、不提前花模型钱：
     *  <pre>
     *  L1  成本 0     先对单条超长做保头尾截断（复用 {@link #truncateL1}）；
     *  L2  成本 0     保留最近 KEEP_RECENT(5) 轮原文，把更早轮次逐轮折叠进摘要（纯规则，不调模型）；
     *  L3  成本 0     仍超限才对最近窗口从最旧一轮开始「一轮一轮」折叠进摘要（纯规则，不调模型）；
     *  L4  成本最高   规则已无空间时的唯一一次模型调用：对早期摘要做模型重摘要压平大头。
     *  </pre>
     *  任一层达标即提前返回；只有该层不足时才进入下一层。模型仅在 L4 触发一次，且必在所有规则层之后。
     *  最近 5 轮原文在不超限时始终完整保留，不再被整段清空。
     *  @return 收敛后 memSize ≤ targetMemChars 为 true */
    public boolean compressMem(String key, long targetMemChars, ContextSummarizer summarizer) {
        // 解析出该会话在 AgentStateStore 中的定位（读取/写入同源）
        Resolved r = resolve(key);
        // 加锁：压缩涉及“读-判-写”，与 append/fold 并发须互斥，避免双写丢记忆
        synchronized (this) {
            // 从状态库载入该会话的 AgentState（含 summary + context 原文）
            AgentState st = load(r);
            if (st == null) {
                return true; // 无历史即无记忆可压，视为已达标
            }
            // 记录压缩前的总字符数，供日志对比“压前/压后”
            long before = estMemChars(st);
            // 本调用是否真发生压缩；未压缩则跳过记日志
            boolean compressed = false;
            // 持当前 context 的可变引用，后续逐层就地折叠/截断
            List<Msg> ctx = st.contextMutable();
            // 关键前提：压缩只在轮次边界触发（本轮消息尚未 append），故 ctx 恒为严格
            //   [user, assistant] 成对序列、从 USER 起、无半截消息，无需处理配对错位；
            //   且 ctx 可能为空或不足 KEEP_RECENT 轮，此时各层 while/for 据此自然跳过。

            // ---------- L1（成本0）：对单条超长做保头尾截断 ----------
            // 即便最近 5 轮也必须先处理：一条 12k+ 的超长消息本身就能占掉大半窗口。
            // 边界：ctx 为空时 for 直接不执行（安全无压）；c 为 null 由下方 if 短路跳过。
            for (int i = 0; i < ctx.size(); i++) {
                Msg m = ctx.get(i);                          // 取当前消息
                String c = m.getTextContent();               // 取其文本
                if (c != null && c.length() > L1_MAX_TEXT) { // 超过 L1 阈值(12k)才需要压
                    // 保头尾：(前 L1_HEAD + 省略号 + 后 L1_TAIL)，保留两端语义
                    ctx.set(i, wrapMsg(m.getRole(), truncateL1(c)));
                    compressed = true;                       // 标记曾压缩（供记日志）
                }
            }
            // 落库后复测：已达预算则记一次成功日志并提前返回（省掉后续更高成本层）
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) {
                return true;
            }

            // ---------- L2（成本0）：保留最近5轮原文，仅折叠更早轮次（纯规则） ----------
            // 先无模型地建“最近窗口”，把超出 KEEP_RECENT 的旧轮折进摘要。
            // 边界：ctx 为空或不足 5 轮时 countUserTurns≤KEEP_RECENT，循环不进入，安全。
            while (countUserTurns(ctx) > KEEP_RECENT) {   // 还有第 6 轮更早的历史时
                Msg oldestUser = ctx.get(0);               // 最旧一轮的用户消息
                Msg oldestAsst = ctx.size() > 1 ? ctx.get(1) : null; // 其配对助手回复
                // 边界：若仅剩孤立 user 无配对（本实现不出现，但防御性处理）→ asst 为 null，
                //   fold 以空回复折叠，随后仅删 user 一条、不再误删第二个元素
                // 记录被折叠的用户提问，供记忆面板追溯“折进了什么”
                recordFold(r, oldestUser == null ? "" : oldestUser.getTextContent());
                // 纯规则折叠进摘要（forceFallback=true，不调模型）
                fold(st, r, oldestUser, oldestAsst, summarizer, true);
                ctx.remove(0);                             // 移除已折叠的用户消息
                if (oldestAsst != null && ctx.size() > 0) {
                    ctx.remove(0);                         // 同步移除其助手回复，保持 u/a 成对
                }
                compressed = true;
            }
            // 建窗后复测：通常这层就把预算压回目标内，直接返回
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) {
                return true;
            }

            // ---------- L3（成本0）：仍超限，对最近窗口逐轮折叠（纯规则） ----------
            // 第 5 轮原文都放进去了还不够 → 从最旧一轮开始“一轮一轮”折叠最近窗口
            while (estMemChars(st) > targetMemChars) {     // 未达到预算上限时继续
                if (ctx.size() < 2) {
                    break; // 只剩单条（无配对）或已为空，无法再折
                }
                Msg oldestUser = ctx.get(0);               // 取当前最旧一轮的用户消息
                Msg oldestAsst = ctx.get(1);               // 其配对助手回复
                recordFold(r, oldestUser.getTextContent()); // 记录此次折叠的提问
                fold(st, r, oldestUser, oldestAsst, summarizer, true); // 纯规则折叠（不调模型）
                ctx.remove(0);                             // 移除用户消息
                ctx.remove(0);                             // 移除助手回复（u/a 成对删除）
                compressed = true;
                // 每折一轮重新计量，达标即退出循环（减少不必要的折叠）
            }
            if (finishPhase(st, r, before, targetMemChars, compressed, key)) {
                return true;
            }

            // ---------- L4（成本最高）：规则已无空间，唯一一次模型重摘要 ----------
            // 至此 context 已空或规则折叠无法再减，唯一还能压的就是把早期摘要用模型重写。
            // 边界1：summarizer 为 null（未配模型）时整段跳过 → 可能就此未达标返回；
            // 边界2：摘要本身已压缩到极致、或 targetMemChars 极小，模型重写后仍可能不达标，
            //        此时返回 false，属“可压缩量的物理下限”，非逻辑缺陷。
            String sum0 = st.getSummary();
            if (summarizer != null && sum0 != null && !sum0.isBlank()) { // 有摘要且可调模型才重写
                try {
                    String sum = summarizer.summarize(sum0);   // 模型把既成摘要压缩重写
                    if (sum != null && !sum.isBlank()) {
                        st.setSummary(sum);                    // 覆盖早期摘要，压掉冗余
                        compressed = true;
                    }
                } catch (Exception ignored) {
                    // 摘要异常回退：保留原摘要（宁可不压，也不写坏记忆）
                }
            }

            if (compressed) {
                // 只有真正压过才落库 + 记日志；落在 L4 或任一层达标提前返回处
                save(r, st);
                long after = estMemChars(st);                  // 计算压后字符数
                // 记一条压缩记录（含压前/压后/目标/是否达标）
                recordCompression(key, before, after, targetMemChars, after <= targetMemChars);
                return after <= targetMemChars;                // 返回最终是否收敛到目标内
            }
            return estMemChars(st) <= targetMemChars;          // 未压缩：直接报告当前是否达标
        }
    }

    /** 落库并返回当前是否已达预算；达标时记一条成功日志（呼叫方据此提前返回）。
     *  未达标时只落库不记日志，最终由 L4 收敛后统一记取。 */
    private boolean finishPhase(AgentState st, Resolved r, long before, long target,
                                boolean compressed, String key) {
        save(r, st);
        long cur = estMemChars(st);
        if (cur <= target) {
            if (compressed) {
                recordCompression(key, before, cur, target, true);
            }
            return true;
        }
        return false;
    }

    /** 记忆面板用的压缩记录条（system 自收敛触发；仅记录真正发生过压缩的调用）。 */
    public record CompressionRecord(long ts, long memCharsBefore, long memCharsAfter,
                                    long targetChars, boolean achieved) {
    }

    private static final int MAX_COMPRESSION_LOG = 20;
    private final java.util.Map<String, java.util.ArrayDeque<CompressionRecord>>
            compressionLogs = new java.util.concurrent.ConcurrentHashMap<>();
    /** 待压缩标记集：用户点击「标记压缩」后置位，下一轮 Agent 运行时消费并执行压缩。 */
    private final java.util.Set<String> pendingCompress = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记某会话待压缩（仅置位，不立即执行；由下一轮 Agent 运行消费）。 */
    public void markCompress(String key) {
        pendingCompress.add(key);
    }

    /** 消费待压缩标记：置位则返回 true 并清除（调用方应随即执行压缩）。 */
    public boolean consumeCompress(String key) {
        return pendingCompress.remove(key);
    }

    /** 该会话当前是否有未消费的压缩标记（供前端标识）。 */
    public boolean isCompressMarked(String key) {
        return pendingCompress.contains(key);
    }

    /** 压缩日志需并发访问（SSE 消费线程写、HTTP 取读），用同步队列保顺序。 */
    private void recordCompression(String key, long before, long after,
                                   long target, boolean achieved) {
        ArrayDeque<CompressionRecord> q = compressionLogs.computeIfAbsent(key,
                k -> new ArrayDeque<>());
        synchronized (q) {
            q.offerFirst(new CompressionRecord(System.currentTimeMillis(),
                    before, after, target, achieved));
            while (q.size() > MAX_COMPRESSION_LOG) {
                q.pollLast();
            }
        }
    }

    /** 返回某会话最近 N 条压缩记录（新→旧）。无记录返回空表。 */
    public java.util.List<CompressionRecord> compressionLog(String key) {
        ArrayDeque<CompressionRecord> q = compressionLogs.get(key);
        if (q == null) {
            return List.of();
        }
        synchronized (q) {
            return List.copyOf(q);
        }
    }

    /** 估算当前会话 MEMORY 体积（字符）= 摘要长度 + context 各条文本长度之和。 */
    private static long estMemChars(AgentState st) {
        long n = st.getSummary() == null ? 0 : st.getSummary().length();
        for (Msg m : st.getContext()) {
            String c = m.getTextContent();
            if (c != null) {
                n += c.length();
            }
        }
        return n;
    }

    /** 按角色构造一条文本消息（压缩改写时保留原角色）。 */
    private static Msg wrapMsg(MsgRole role, String text) {
        return Msg.builderForRole(role).textContent(text).build();
    }

    /** 把最旧一轮（user + assistant）折叠进摘要。L3：累计摘要达阈值且非强制规则态才调模型重摘要，
     *  否则用规则累加并截断兜底，避免频繁调用 LLM。折叠同时把本轮原文归档落盘并内嵌「可找回标记」。 */
    private void fold(AgentState st, Resolved r, Msg user, Msg asst, ContextSummarizer summarizer) {
        fold(st, r, user, asst, summarizer, false);
    }

    /** 同 {@link #fold(AgentState, Resolved, Msg, Msg, ContextSummarizer)}，但 {@code forceFallback=true}
     *  时强制纯规则累加截断，绝不动用模型（用于压缩自收敛的低成本分层，模型只在最终 L4 调一次）。 */
    private void fold(AgentState st, Resolved r, Msg user, Msg asst, ContextSummarizer summarizer,
                      boolean forceFallback) {
        String userT = user.getTextContent();
        String asstT = asst != null ? asst.getTextContent() : "";
        // 归档本轮完整原文并取 id（原文随后被折叠洗掉，可经召回工具按 id 取回全文）
        String archiveId = archiveRound(r, userT, asstT);
        String cur = st.getSummary() == null ? "" : st.getSummary();
        String joined = cur.isBlank() ? userT + "\n" + asstT : cur + "\n" + userT + "\n" + asstT;
        String sum = null;
        // 仅在未强制规则态、累计摘要达阈值时才调模型重摘要(L3 自限流)，否则纯规则累加兜底
        if (!forceFallback && summarizer != null && joined.length() >= L3_MIN_CHARS) {
            try {
                sum = summarizer.summarize(joined);
            } catch (Exception ignored) {
                // 摘要调用异常回退截断
            }
        }
        String base = (sum != null && !sum.isBlank()) ? sum : fallback(cur, userT, asstT);
        // 摘要里追加「可找回标记」：标注从哪找(id/路径)、大概内容(摘句)、何时及用什么工具召回全文
        st.setSummary(base + marker(archiveId, r, userT));
    }

    /** 生成「可找回标记」文本，追加进摘要，让模型看见并能引用 id 与召回工具。
     *  archiveId 为 null（无根目录/落盘失败）时返回空串，不加标记也不报错。 */
    private String marker(String archiveId, Resolved r, String userText) {
        if (archiveId == null) {
            return "";
        }
        String snip = (userText == null ? "" : userText).replace('\n', ' ').replace('\t', ' ').trim();
        if (snip.length() > FOLD_SNIPPET) {
            snip = snip.substring(0, FOLD_SNIPPET) + "…";
        }
        return "\n【记忆归档 #" + archiveId + "】如需找回被压缩的该轮完整内容：\n"
                + "  从哪找回：会话目录下 " + archiveRelative(archiveId) + "（相对本会话 archive/ 目录）\n"
                + "  归档 id：#" + archiveId + "\n"
                + "  大概内容：「" + (snip.isEmpty() ? "(无文本摘句)" : snip) + "」\n"
                + "  什么情况召回：当用户需要该轮完整原文/细节、而上下文仅存摘要时\n"
                + "  使用方法：调用工具 memory_recall(archiveId=\"" + archiveId + "\") 读取全文。";
    }

    /** L1：单条文本超长时保头尾截断，保留原语义头尾。 */
    private static String truncateL1(String text) {
        if (text == null || text.length() <= L1_MAX_TEXT) {
            return text;
        }
        return text.substring(0, L1_HEAD)
                + "\n…[内容超长已截断]…\n"
                + text.substring(text.length() - L1_TAIL);
    }

    /** 摘要回退：原样拼接并截断，确保摘要不无限增长（规则态上限 MAX_SUMMARY_CHARS）。
     *  保头截断（保留最早的早期要旨），优先保住早期结论，丢弃最近折叠的冗余尾部。 */
    private static String fallback(String summary, String user, String asst) {
        String suffix = user + "\n" + asst;
        String joined = summary.isBlank() ? suffix : summary + "\n" + suffix;
        return joined.length() > MAX_SUMMARY_CHARS
                ? joined.substring(0, MAX_SUMMARY_CHARS) : joined;
    }

    private static int countUserTurns(List<Msg> ctx) {
        int n = 0;
        for (Msg m : ctx) {
            if (m.getRole() == MsgRole.USER) {
                n++;
            }
        }
        return n;
    }

    private static Msg userMsg(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistantMsg(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT).textContent(text).build();
    }

    private AgentState load(String key) {
        return load(resolve(key));
    }

    private AgentState load(Resolved r) {
        try {
            return store.get(r.userId(), r.sessionId(), STATE_KEY, AgentState.class).orElse(null);
        } catch (Exception e) {
            log.warn("[ConversationMemory] 读取会话上下文失败（回退空）: {}", e.toString());
            return null;
        }
    }

    private void save(Resolved r, AgentState st) {
        try {
            store.save(r.userId(), r.sessionId(), STATE_KEY, st);
        } catch (Exception e) {
            log.warn("[ConversationMemory] 写入会话上下文失败（不阻断）: {}", e.toString());
        }
    }

    /** 把工作台账面 key（{ws}/{agent}/{session}）拆成官方槽键 (userId=ws/agent, sessionId)。 */
    private static Resolved resolve(String key) {
        int i = key.lastIndexOf('/');
        if (i < 0) {
            return new Resolved("anon", key);
        }
        return new Resolved(key.substring(0, i), key.substring(i + 1));
    }

    /** 官方状态槽的用户/会话标识。 */
    private record Resolved(String userId, String sessionId) {
    }

    /* =========================================================================
     * ② 五区上下文预算模型（隔离 + 计量 + 判定）
     * ====================================================================== */

    /** 五区上下文预算模型：每区一个独立槽位，可单独计量字符/token 与占比，并据此做预算判定。
     *  分两层：① 隔离+计量（五区独立归位各可计量）；② 预算计算/判定博弈（按阈值规则产出
     *  {@link Decision} 各区状态 + 整体结论 + 是否需压缩），供运行时钩子消费。只做计量与
     *  判定，不负责改写模型输入。 */
    public static final class ContextBudget {

        /** 五区明细。 */
        public enum Zone { SYSTEM, TASK, MEMORY, TOOL, FREE }

        /** 各区的目标预算占比（编码场景示例值，非普适常数；正常应和=1.0）。 */
        public static final Map<Zone, Double> RATIO = Map.ofEntries(
                Map.entry(Zone.SYSTEM, 0.10),
                Map.entry(Zone.TASK, 0.05),
                Map.entry(Zone.MEMORY, 0.15),
                Map.entry(Zone.TOOL, 0.40),
                Map.entry(Zone.FREE, 0.30));

        /** 估算系数：粗略按每字符 1 token（中文约 1 字符≈1 token，英文可另配更小系数）。 */
        private static final double CHARS_PER_TOKEN = 1.0;

        /** 默认上下文窗口（字符）：FREE 区判定的绝对锚定基准。正常应=所配模型上下文上限，
         *  编码场景取常见 prompt 预算的近似值；非普适，可按需 {@link #window(long)} 覆写。 */
        public static final long DEFAULT_WINDOW = 1_000_000;

        /** FREE 区警戒/压缩阈值：FREE 占窗口低于此比例即判定需压缩。 */
        private static final double FREE_WARN = 0.15;
        private static final double FREE_COMPRESS = 0.10;

        /** 分区超配警戒系数：相对占比超过目标 ratio 的该倍率记为 WARN（超过则是 OVER）。 */
        private static final double OVER_RATIO = 1.2;

        private final Map<Zone, String> zones = new EnumMap<>(Zone.class);
        /** TOOL 区按「一条工具历史 = 一段」分段累积，便于超限时从最旧段回收。 */
        private final List<String> toolSegments = new ArrayList<>();
        private long window = DEFAULT_WINDOW;

        private ContextBudget() {
            for (Zone z : Zone.values()) {
                zones.put(z, "");
            }
        }

        public static ContextBudget of() {
            return new ContextBudget();
        }

        /** 写入某区内容并返回自身（链式）。null 视为空串。 */
        public ContextBudget zone(Zone zone, String text) {
            zones.put(zone, text == null ? "" : text);
            return this;
        }

        /** 设置上下文窗口（字符）并返回自身。低于已用长度时 FREE 恒为 0。 */
        public ContextBudget window(long window) {
            this.window = Math.max(window, 0);
            return this;
        }

        public long window() {
            return window;
        }

        /** 深拷贝（各槽文本 + 窗口 + 工具分段），避免共享实例被运行时钩子污染。 */
        public ContextBudget copy() {
            ContextBudget b = new ContextBudget();
            for (Zone z : Zone.values()) {
                b.zones.put(z, text(z));
            }
            b.window = window;
            b.toolSegments.addAll(toolSegments);
            return b;
        }

        /** 追加工具历史到 TOOL 区并返回自身（工具结果跨迭代累积，故用追加而非覆盖）。 */
        public ContextBudget appendTool(String toolResultText) {
            if (toolResultText == null || toolResultText.isEmpty()) return this;
            toolSegments.add(toolResultText);
            zones.put(Zone.TOOL, joinTools());
            return this;
        }

        /** 从运行时消息文本列表填充 TOOL 区（把多条工具结果拼进工具历史，覆盖式写入）。 */
        public ContextBudget toolFromHistory(List<String> histories) {
            toolSegments.clear();
            if (histories != null && !histories.isEmpty()) {
                for (String h : histories) {
                    if (h != null && !h.isEmpty()) toolSegments.add(h);
                }
            }
            zones.put(Zone.TOOL, joinTools());
            return this;
        }

        /** 回收超限 TOOL：超过 capChars 时从最旧（最前）逐段丢弃，直到不再超限或清空。
         *  @return 丢弃的段数（0 表示本就未超限） */
        public int capTool(long capChars) {
            if (toolSegments.isEmpty()) {
                return 0;
            }
            int dropped = 0;
            while (chars(Zone.TOOL) > capChars && !toolSegments.isEmpty()) {
                toolSegments.remove(0);
                dropped++;
                zones.put(Zone.TOOL, joinTools());
            }
            return dropped;
        }

        /** 把各工具段拼成 TOOL 区文本（每段以换行结尾，与原累加语义一致）。 */
        private String joinTools() {
            StringBuilder b = new StringBuilder();
            for (String s : toolSegments) {
                b.append(s).append('\n');
            }
            return b.toString();
        }

        public String text(Zone zone) {
            return zones.get(zone);
        }

        public int chars(Zone zone) {
            return text(zone).length();
        }

        public long estTokens(Zone zone) {
            return Math.round(chars(zone) / CHARS_PER_TOKEN);
        }

        /** 已用上下文（SYSTEM+TASK+MEMORY+TOOL）总字符，不含 FREE。 */
        public int usedChars() {
            return totalChars();
        }

        public int totalChars() {
            int sum = 0;
            for (Zone z : Zone.values()) {
                sum += chars(z);
            }
            return sum;
        }

        /** 空余区字符 = 窗口 − 已用（下限 0）。 */
        public long freeChars() {
            return Math.max(0, window - totalChars());
        }

        /** 空余区占窗口比例（0~1）；FREE 判定基准。 */
        public double freeRatio() {
            return window <= 0 ? 0 : (double) freeChars() / window;
        }

        /** 加载度：已用占窗口比例（0~1）。 */
        public double load() {
            return window <= 0 ? 0 : (double) totalChars() / window;
        }

        /** 某区当前实际占比（0~1，相对于其余已用区）。 */
        public double share(Zone zone) {
            int total = totalChars();
            return total == 0 ? 0 : (double) chars(zone) / total;
        }

        /** 某区的目标占比常数。 */
        public double targetRatio(Zone zone) {
            return RATIO.getOrDefault(zone, 0.0);
        }

        /** 分区判定状态：OK 达标 / WARN 接近超配 / OVER 超配。 */
        public enum Status { OK, WARN, OVER }

        /** 整体预算结论：SUFFICIENT 充足 / WARN 需留意 / NEED_COMPRESS 需压缩（FREE<10%）。 */
        public enum Verdict { SUFFICIENT, WARN, NEED_COMPRESS }

        /** 预算判定结果：各区状态 + 整体结论 + 加载度 + 问题清单。 */
        public static final class Decision {
            private final Verdict verdict;
            private final Map<Zone, Status> status;
            private final double load;
            private final String issues;

            Decision(Verdict verdict, Map<Zone, Status> status, double load, String issues) {
                this.verdict = verdict;
                this.status = status;
                this.load = load;
                this.issues = issues == null ? "" : issues;
            }

            public Verdict verdict() {
                return verdict;
            }

            public Status status(Zone zone) {
                return status.getOrDefault(zone, Status.OK);
            }

            public double load() {
                return load;
            }

            public boolean needCompress() {
                return verdict == Verdict.NEED_COMPRESS;
            }

            public String issues() {
                return issues;
            }

            /** 单行可读摘要，供运行时钩子日志使用。 */
            public String summary() {
                return String.format("加载度=%.1f%% 结论=%s 问题=[%s]",
                        load * 100, verdict, issues);
            }
        }

        /** 预算计算（判定博弈）：以当前各区计量为输入，按阈值产出结论。
         *  规则（书/记忆约定）：
         *  - SYSTEM 固定 10% 不可压缩，状态恒 OK；
         *  - MEMORY/TASK/TOOL 相对占比超过各自目标 ratio 即告警（超过其 1.2 倍记 OVER）；
         *  - TOOL 实际占比 >40% 时不挤压 SYSTEM，其超配就近占用 FREE；
         *  - FREE（窗口−已用）<15% 记 WARN，<10% 判定 NEED_COMPRESS，触发已存在的 L1-L4 逐级压缩；
         *  - MEMORY 体积由「存时压」自限，此处仅告警不处理。 */
        public Decision decide() {
            double freeRatio = freeRatio();
            double toolShare = share(Zone.TOOL);
            double memShare = share(Zone.MEMORY);
            double taskShare = share(Zone.TASK);

            Map<Zone, Status> st = new EnumMap<>(Zone.class);
            st.put(Zone.SYSTEM, Status.OK); // 固定不可压
            st.put(Zone.TASK, flag(taskShare, targetRatio(Zone.TASK)));
            st.put(Zone.MEMORY, flag(memShare, targetRatio(Zone.MEMORY)));
            st.put(Zone.TOOL, flag(toolShare, targetRatio(Zone.TOOL)));
            st.put(Zone.FREE,
                    freeRatio < FREE_COMPRESS ? Status.OVER
                            : freeRatio < FREE_WARN ? Status.WARN : Status.OK);

            List<String> issues = new ArrayList<>();
            if (freeRatio < FREE_COMPRESS) {
                issues.add("FREE<10% 已达压缩阈值");
            } else if (freeRatio < FREE_WARN) {
                issues.add("FREE<15% 接近压缩阈值");
            }
            if (toolShare > targetRatio(Zone.TOOL)) {
                issues.add("TOOL超配占用FREE(" + String.format("%.0f%%", toolShare * 100) + ")");
            }
            if (memShare > targetRatio(Zone.MEMORY)) issues.add("MEMORY超配");
            if (taskShare > targetRatio(Zone.TASK)) issues.add("TASK超配");

            Verdict v;
            if (freeRatio < FREE_COMPRESS) {
                v = Verdict.NEED_COMPRESS;
            } else if (!issues.isEmpty()) {
                v = Verdict.WARN;
            } else {
                v = Verdict.SUFFICIENT;
            }
            return new Decision(v, st, load(), String.join("；", issues));
        }

        private static Status flag(double share, double target) {
            if (share <= target) return Status.OK;
            return share <= target * OVER_RATIO ? Status.WARN : Status.OVER;
        }

        /** 生成多行可读报告，便于日志查看各区隔离与占比/加载度。 */
        public String report() {
            StringBuilder sb = new StringBuilder();
            int total = totalChars();
            for (Zone z : Zone.values()) {
                int c = chars(z);
                double pct = total == 0 ? 0 : (double) c / total * 100;
                sb.append(String.format("%-6s %6d 字符 (%5.1f%%)  预算=%4.0f%%  %s%n",
                        z.name(), c, pct, targetRatio(z) * 100, summarize(text(z))));
            }
            sb.append(String.format("TOTAL  %6d 字符   窗口 %6d   已用%5.1f%%  空余%5.1f%%%n",
                    total, window, load() * 100, freeRatio() * 100));
            return sb.toString();
        }

        private static String summarize(String s) {
            if (s == null || s.isEmpty()) {
                return "";
            }
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() <= 48 ? s : s.substring(0, 48) + "…";
        }
    }

    /* =========================================================================
     * ③ 五区预算运行时判定钩子（纯计量 + 判定）
     * ====================================================================== */

    /** 五区预算运行时钩子：seed 携装配期 SYSTEM/MEMORY，运行时补 TASK 与 TOOL，
     *  在每条工具结果落地后与 Turn 结束时对当前上下文做五区预算判定并日志输出。
     *  只加计量与决策，不做三层改写（区别于已删除的厚投影中间件）；每次调用持有各自
     *  闭包状 {@link ContextBudget#copy()}，跨调用天然隔离，并发安全。 */
    public static final class ContextBudgetMiddleware implements MiddlewareBase {

        private static final Logger log = LoggerFactory.getLogger(ContextBudgetMiddleware.class);

        /** 单条工具结果文本累积上限（字符），防超大输出撑爆内存。 */
        private static final int TOOL_BUF_CAP = 20_000;

        /** 装配期已隔离的 SYSTEM/MEMORY 基准（每次调用深拷贝使用，不共享变更）。 */
        private final ContextBudget seed;

        /** 记忆收敛目标（可为 null，缺省时仅判定不收敛）。 */
        private final ConversationMemory memory;
        private final String memKey;
        private final ContextSummarizer summarizer;
        /** [工具进度] 日志开关（对应 mo.agent.tool-progress-log）。 */
        private final boolean toolProgressLog;

        public ContextBudgetMiddleware(ContextBudget seed) {
            this(seed, null, null, null, true);
        }

        public ContextBudgetMiddleware(ContextBudget seed, ConversationMemory memory,
                                       String memKey, ContextSummarizer summarizer) {
            this(seed, memory, memKey, summarizer, true);
        }

        public ContextBudgetMiddleware(ContextBudget seed, ConversationMemory memory,
                                       String memKey, ContextSummarizer summarizer,
                                       boolean toolProgressLog) {
            this.seed = seed == null ? ContextBudget.of() : seed;
            this.memory = memory;
            this.memKey = memKey;
            this.summarizer = summarizer;
            this.toolProgressLog = toolProgressLog;
        }

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx,
                                        AgentInput input,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
            // 每次调用独立预算：seed 深拷贝 + TASK（本调用用户任务）+ 累积 TOOL（本调用工具历史）
            ContextBudget budget = seed.copy();
            budget.zone(ContextBudget.Zone.TASK, extractUserTask(input));
            Map<String, StringBuilder> toolBuf = new HashMap<>();
            // 工具进度追踪：callId → 开始时刻 / 工具名，用于输出「当前进行到哪个工具」的轻量日志
            Map<String, Long> toolStartNanos = new HashMap<>();
            Map<String, String> toolName = new HashMap<>();
            return next.apply(input)
                    .doOnNext(ev -> {
                        if (ev instanceof ToolCallStartEvent s) {
                            toolStartNanos.put(s.getToolCallId(), System.nanoTime());
                        } else if (ev instanceof ToolCallEndEvent c) {
                            String n = c.getToolCallName();
                            toolName.put(c.getToolCallId(), n);
                            logToolStart(agent, n);
                        } else if (ev instanceof ToolResultTextDeltaEvent t) {
                            accumulate(toolBuf, t.getToolCallId(), t.getDelta());
                        } else if (ev instanceof ToolResultEndEvent t) {
                            logToolDone(agent, toolName.get(t.getToolCallId()),
                                    t.getToolCallId(), toolStartNanos.remove(t.getToolCallId()));
                            finalizeTool(budget, toolBuf, t);
                            logDecision(agent, ctx, budget, null);
                        }
                    })
                    .doFinally(__ -> logDecision(agent, ctx, budget, "Turn 结束"));
        }

        /** 工具开始执行：输出「➤ 正在执行 tool=…」进度日志（受 tool-progress-log 开关控制）。 */
        private void logToolStart(Agent agent, String name) {
            if (!toolProgressLog || name == null || name.isBlank()) return;
            log.info("[工具进度] agent={} ➤ 正在执行 tool={}", agentName(agent), name);
        }

        /** 工具执行结束：输出「✓ tool=… 完成 Xms」（受 tool-progress-log 开关控制）。 */
        private void logToolDone(Agent agent, String name, String callId, Long startNanos) {
            if (!toolProgressLog) return;
            long elapsed = startNanos == null ? -1 : (System.nanoTime() - startNanos) / 1_000_000L;
            String n = (name == null || name.isBlank()) ? (callId == null ? "?" : callId) : name;
            log.info("[工具进度] agent={} ✓ tool={} 完成 {}{}",
                    agentName(agent), n, elapsed < 0 ? "（耗时未知）" : elapsed + "ms",
                    name == null ? "（工具名未捕获）" : "");
        }

        private static String agentName(Agent agent) {
            return (agent != null && agent.getName() != null) ? agent.getName() : "agent";
        }

        /** 把一条已累积完的工具结果文本并入 TOOL 区并按预算判定。 */
        private void finalizeTool(ContextBudget budget, Map<String, StringBuilder> toolBuf,
                                  ToolResultEndEvent end) {
            StringBuilder sb = (end.getToolCallId() == null)
                    ? null : toolBuf.remove(end.getToolCallId());
            budget.appendTool(sb == null ? "" : sb.toString());
        }

        /** 输出五区预算判定摘要（含当前分区块与整体结论）。失败仅 WARN，绝不阻塞主流程。 */
        private void logDecision(Agent agent, RuntimeContext ctx, ContextBudget budget, String phase) {
            try {
                // 先回收超限 TOOL（丢弃最旧工具历史）：TOOL 是本调用可丢弃的运行缓冲，
                // 优先于压缩持久化 MEMORY，避免工具历史膨胀占用 FREE 却错误转嫁给记忆收敛
                long toolCap = (long) (ContextBudget.RATIO.get(ContextBudget.Zone.TOOL) * budget.window());
                int dropped = budget.capTool(toolCap);
                ContextBudget.Decision d = budget.decide();
                String agentName = (agent != null && agent.getName() != null)
                        ? agent.getName() : "agent";
                String session = (ctx != null && ctx.getSessionId() != null)
                        ? ctx.getSessionId() : "";
                StringBuilder extra = new StringBuilder();
                if (dropped > 0) {
                    extra.append(" | TOOL回收丢弃").append(dropped).append("段");
                }
                // 预算超限 → 触发系统自收敛：把 MEMORY 压到「保证 FREE≥10% 窗口」的上限
                if (d.needCompress() && memory != null && memKey != null) {
                    long targetMem = Math.max(0, (long) (0.9 * budget.window()
                            - budget.chars(ContextBudget.Zone.SYSTEM)
                            - budget.chars(ContextBudget.Zone.TASK)
                            - budget.chars(ContextBudget.Zone.TOOL)));
                    boolean ok = memory.compressMem(memKey, targetMem, summarizer);
                    extra.append(String.format(" | 自收敛目标MEMORY=%d 收敛=%s",
                            targetMem, ok ? "达标" : "未达(四轮用尽)"));
                    budget.zone(ContextBudget.Zone.MEMORY, "");
                }
                log.info("五区上下文[运行时]判定  agent={}{}  {}: {} | TOOL={}%{}",
                        agentName, session.isBlank() ? "" : " session=" + session,
                        phase == null ? "工具结果" : phase, d.summary(),
                        String.format("%.0f", budget.share(ContextBudget.Zone.TOOL) * 100),
                        extra);
            } catch (Exception e) {
                log.warn("五区预算判定失败（不阻断）: {}", e.toString());
            }
        }

        /** 累积工具结果流式增量，上限 TOOL_BUF_CAP。 */
        private static void accumulate(Map<String, StringBuilder> buf, String key, String delta) {
            if (delta == null || delta.isBlank()) return;
            StringBuilder sb = buf.computeIfAbsent(key, k -> new StringBuilder());
            if (sb.length() >= TOOL_BUF_CAP) return;
            sb.append(delta);
        }

        /** 从本调用输入提取最后一条用户任务文本（TASK 区）。 */
        private static String extractUserTask(AgentInput input) {
            if (input == null || input.msgs() == null) return null;
            String found = null;
            for (Msg msg : input.msgs()) {
                if (msg != null && msg.getRole() == MsgRole.USER) {
                    String t = msg.getTextContent();
                    if (t != null && !t.isBlank()) found = t;
                }
            }
            return found;
        }
    }
}