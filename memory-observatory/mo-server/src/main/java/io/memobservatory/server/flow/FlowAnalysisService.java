/*******************************************************************************
 * 【模块】流程分析 Flow Analysis（评估层第一模块，docs/流程分析功能设计.md）
 * 【文件】FlowAnalysisService.java（io.memobservatory.server.flow）
 * 【核心功能】从 memory_events 的 trace 序列构建：流程模式（变体归并）/ 转移图
 *            （节点=operation×layer，边=相邻转移）/ 循环检测（滑动窗口 2-4 重复子序列）
 *            / 节点健康信号 S1/S2/S3/S7 + 预置坏味道 → 问题节点卡片。
 * 【设计要点】纯读、内存计算（单 Agent 窗口内万级事件毫秒完成）；阈值全部可配置，
 *            不硬编码；trace 缺失退化 session_id（契约见设计文档 §2）。
 * 【核心改动】2026-09-01 新增 MVP：S1 烧钱 / S2 慢 / S3 循环参与 / S7 失败率
 *            + 压缩风暴 / 检索打转 / 读写抖动 / 遗忘风暴四坏味道。
 *******************************************************************************/
package io.memobservatory.server.flow;

import io.memobservatory.server.storage.EventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 流程分析服务：把按时间排序的记忆操作序列归并为流程模式，
 * 产出转移图数据与「节点 + 信号 + 证据 + 建议」四元组问题卡片。
 * 归因用规则引擎（不上 LLM），证据具体到 trace 可回放。
 */
@Service
public class FlowAnalysisService {

    private final EventRepository repo;

    // ---- 阈值（设计文档 §3.4，全部可配置不硬编码）----
    @Value("${mo.analytics.flow.loop-min-repeats:3}")
    private int loopMinRepeats;
    @Value("${mo.analytics.flow.loop-window:4}")
    private int loopWindow;
    @Value("${mo.analytics.flow.s1-burn-token-ratio:0.40}")
    private double s1TokenRatio;
    @Value("${mo.analytics.flow.s2-slow-p95-ratio:2.0}")
    private double s2P95Ratio;
    @Value("${mo.analytics.flow.s3-loop-join-ratio:0.30}")
    private double s3JoinRatio;
    @Value("${mo.analytics.flow.s7-fail-ratio:0.10}")
    private double s7FailRatio;
    @Value("${mo.analytics.flow.smell-forget-ratio:0.30}")
    private double smellForgetRatio;

    /** operation×layer → 可读节点名（设计文档 §2 归一化命名表，—表示组合几乎不出现）。 */
    private static final Map<String, String> NODE_LABELS = Map.ofEntries(
            Map.entry("read@prompt", "提示词读取"), Map.entry("write@prompt", "提示词注入"),
            Map.entry("read@session", "会话记忆检索"), Map.entry("write@session", "会话记忆写入"),
            Map.entry("update@session", "会话压缩/整合"), Map.entry("expire@session", "会话遗忘"),
            Map.entry("read@skill", "技能检索"), Map.entry("write@skill", "技能写入"),
            Map.entry("update@skill", "技能更新"), Map.entry("expire@skill", "技能过期"),
            Map.entry("read@provider", "外部记忆检索"), Map.entry("write@provider", "外部记忆写入"),
            Map.entry("update@provider", "外部记忆整合"), Map.entry("expire@provider", "外部记忆过期"),
            Map.entry("update@prompt", "提示词更新"), Map.entry("expire@prompt", "提示词遗忘"));

    /** 窗口内单个记忆操作的轻量记录。 */
    private record Rec(String trace, String node, String key, long tokens, long latency, boolean failed) { }

    public FlowAnalysisService(EventRepository repo) {
        this.repo = repo;
    }

    /** 流程模式列表：变体按出现次数排序（点击左栏条目 → 中栏高亮路径）。 */
    public Map<String, Object> patterns(String agentId, int days, String sessionId, int limit) {
        Model m = analyze(agentId, days, sessionId);
        List<Map<String, Object>> rows = m.patterns.stream()
                .sorted(Comparator.comparingLong((Pattern p) -> p.count).reversed())
                .limit(limit)
                .map(p -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("path", p.seq);
                    r.put("count", p.count);
                    r.put("totalTokens", p.tokens);
                    r.put("avgLatencyMs", p.count == 0 ? 0 : p.latency / p.count);
                    r.put("hasLoop", p.loop);
                    r.put("smell", p.smell);
                    r.put("sampleTraceIds", p.samples);
                    return r;
                }).collect(Collectors.toList());
        return base(m, "patterns", rows);
    }

    /** 转移图：节点（含健康信号）+ 边（含转移频次与 token）。 */
    public Map<String, Object> graph(String agentId, int days, String sessionId) {
        Model m = analyze(agentId, days, sessionId);
        return base(m, "graph", Map.of("nodes", m.nodes, "edges", m.edges));
    }

    /** 问题节点卡片：节点 + 命中信号 + 证据（可回放 trace）+ 建议动作。 */
    public Map<String, Object> issues(String agentId, int days, String sessionId) {
        Model m = analyze(agentId, days, sessionId);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (Map<String, Object> n : m.nodes) {
            List<String> signals = (List<String>) n.get("signals");
            if (signals.isEmpty()) {
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("node", n.get("id"));
            card.put("label", n.get("label"));
            card.put("signals", signals);
            card.put("severity", ((List<String>) n.get("highSignals")).isEmpty() ? "medium" : "high");
            card.put("evidence", n.get("evidence"));
            card.put("recommendation", recommendation(signals));
            card.put("sampleTraceIds", n.get("sampleTraceIds"));
            card.put("turnCount", n.get("traceCount"));
            cards.add(card);
        }
        return base(m, "issues", cards);
    }

    /** 问题节点详情：定位到具体 turn —— 哪几个 turn 出了什么问题，附每个 turn 的完整操作上下文（供滑块展示）。 */
    public Map<String, Object> issueDetail(String agentId, int days, String sessionId, String node, Integer span) {
        Model m = analyze(agentId, days, sessionId);
        Map<String, Object> info = m.nodes.stream()
                .filter(n -> node.equals(n.get("id"))).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("agentId", agentId);
        r.put("node", node);
        if (info != null) {
            r.put("label", info.get("label"));
            r.put("signals", info.get("signals"));
            r.put("evidence", info.get("evidence"));
            r.put("recommendation", recommendation((List<String>) info.get("signals")));
            r.put("occurrences", info.get("count"));
            r.put("tokens", info.get("tokens"));
            r.put("turnCount", info.get("traceCount"));
        } else {
            r.put("label", NODE_LABELS.getOrDefault(node, node));
            r.put("signals", List.of());
            r.put("evidence", Map.of());
            r.put("recommendation", "");
            r.put("occurrences", 0);
            r.put("tokens", 0);
            r.put("turnCount", 0);
        }
        // 按 turn 分组（单事件 trace 退化为 session 串联），找出包含该节点的 turn，附完整事件序列作上下文
        List<String> signals = (List<String>) r.getOrDefault("signals", List.of());
        String sigs = String.join("|", signals).toLowerCase();
        boolean sigBurn = sigs.contains("s1") || sigs.contains("burn");
        boolean sigSlow = sigs.contains("s2") || sigs.contains("slow");
        boolean sigLoop = sigs.contains("s3") || sigs.contains("loop") || signals.stream().anyMatch(s -> s.contains("循环"));
        boolean sigForget = sigs.contains("forget") || signals.stream().anyMatch(s -> s.contains("遗忘"));
        // 链上判定基准：问题节点平均单次 token / P95 延迟（超过才算"高消耗/延迟高"，倍数标注）
        long avgToken = 0, p95Lat = 0;
        if (info != null) {
            long occ = info.get("count") instanceof Number n ? n.longValue() : 0;
            long ntk = info.get("tokens") instanceof Number n ? n.longValue() : 0;
            if (occ > 0) avgToken = ntk / occ;
            p95Lat = info.get("p95LatencyMs") instanceof Number n ? n.longValue() : 0;
        }
        r.put("avgToken", avgToken);
        r.put("p95LatencyMs", p95Lat);
        Instant from = days > 0 ? Instant.now().minusSeconds(days * 86400L) : null;
        Instant to = Instant.now();
        List<Map<String, Object>> raw = repo.queryFlowEvents(from, to, agentId, sessionId);
        Map<String, List<Map<String, Object>>> byTrace = groupTurns(raw);
        // 段 = 真实 turn：正常多事件 trace 链即一个 turn；退化 session 串联链（全为单事件 trace）按 turn 边界切分——
        // 每个带用户输入的 WRITE@session 主事件开启新一轮交互（转移图的连通性仍由 groupTurns 的 session 串联保证，互不影响）
        List<List<Map<String, Object>>> segs = new ArrayList<>();
        for (List<Map<String, Object>> chain : byTrace.values()) {
            boolean allSingle = chain.size() > 1 && chain.stream()
                    .allMatch(e -> ((Number) e.getOrDefault("traceSize", 1)).longValue() == 1);
            if (!allSingle) {
                segs.add(chain);
                continue;
            }
            List<Map<String, Object>> cur = new ArrayList<>();
            for (Map<String, Object> e : chain) {
                boolean boundary = !cur.isEmpty()
                        && "WRITE".equalsIgnoreCase(String.valueOf(e.get("operation")))
                        && "session".equalsIgnoreCase(String.valueOf(e.get("layer")))
                        && e.get("turnUser") != null && !String.valueOf(e.get("turnUser")).isBlank();
                if (boundary) {
                    segs.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(e);
            }
            if (!cur.isEmpty()) {
                segs.add(cur);
            }
        }
        List<Map<String, Object>> spans = new ArrayList<>(); // 全量 turn 分布（锚点条）
        List<Map<String, Object>> turns = new ArrayList<>(); // 含问题节点的典型 turn 详情
        long turnCount = 0;
        for (List<Map<String, Object>> seg : segs) {
            List<Map<String, Object>> events = new ArrayList<>(seg.size());
            boolean contains = false, problemFailed = false;
            long problemTokens = 0, problemLatency = 0;
            for (Map<String, Object> e : seg) {
                String id = String.valueOf(e.get("operation")).toLowerCase()
                        + "@" + String.valueOf(e.get("layer")).toLowerCase();
                boolean problem = id.equals(node);
                if (problem) {
                    contains = true;
                    problemFailed |= "failed".equals(e.get("status"));
                    problemTokens += (Long) e.get("tokens");
                    problemLatency += (Long) e.get("latencyMs");
                }
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("ts", e.get("ts"));
                ev.put("node", id);
                ev.put("operation", e.get("operation"));
                ev.put("layer", e.get("layer"));
                ev.put("memoryKey", e.get("memoryKey"));
                ev.put("tokens", e.get("tokens"));
                ev.put("latencyMs", e.get("latencyMs"));
                ev.put("failed", "failed".equals(e.get("status")));
                ev.put("problem", problem);
                // 问题点原因：结合节点信号与该实例指标判定，让标签说清楚「为什么是问题」
                String reasonType = "";
                Long reasonValue = null;
                double reasonMult = 0;
                if (problem) {
                    long tk = (Long) e.get("tokens"), lm = (Long) e.get("latencyMs");
                    if ("failed".equals(e.get("status"))) {
                        reasonType = "failed";
                    } else if (sigBurn && avgToken > 0 && tk > avgToken) {
                        reasonType = "burn"; reasonValue = tk; reasonMult = Math.round(tk / (double) avgToken * 10) / 10.0;
                    } else if (sigSlow && p95Lat > 0 && lm > p95Lat) {
                        reasonType = "slow"; reasonValue = lm; reasonMult = Math.round(lm / (double) p95Lat * 10) / 10.0;
                    } else if (sigLoop) {
                        reasonType = "loop";
                    } else if (sigForget && "expire".equalsIgnoreCase(String.valueOf(e.get("operation")))) {
                        reasonType = "forget";
                    } else {
                        reasonType = "context"; // 仅属于问题节点，本实例没有可量化的问题表现
                    }
                } else if (avgToken > 0 && (Long) e.get("tokens") > avgToken) {
                    // 链上其它操作消耗超过问题节点均值 → 高消耗点（不限问题节点，一眼看到钱烧在哪）
                    reasonType = "hotspot";
                    reasonValue = (Long) e.get("tokens");
                    reasonMult = Math.round(reasonValue / (double) avgToken * 10) / 10.0;
                }
                ev.put("reasonType", reasonType);
                ev.put("reasonValue", reasonValue);
                ev.put("reasonMult", reasonMult);
                ev.put("summary", e.get("summary")); // 内容摘要（memory_summary，截断 500），说明该操作具体干了什么
                ev.put("turnUser", e.get("turnUser")); // 用户输入（仅 WRITE@session 主事件带）
                events.add(ev);
            }
            // 段状态：bad=有问题点 / hot=仅高消耗点 / norm=普通（锚点条分布用）
            // 只要触碰问题节点即标 bad（节点本身已被判定有问题）；具体原因由事件级标签解释，
            // 不能用「实例指标超基准」做 bad 门槛——burn 相对均值、slow 相对 P95 会把绝大多数 turn 排除成 context，锚点条只剩一个红点
            boolean hasBad = contains;
            boolean hasHot = events.stream().anyMatch(x -> "hotspot".equals(x.get("reasonType")));
            Map<String, Object> sp = new LinkedHashMap<>();
            sp.put("st", hasBad ? "bad" : hasHot ? "hot" : "norm");
            sp.put("ts", events.get(0).get("ts"));
            spans.add(sp);
            if (!contains) {
                // 不含问题节点的段也构建 turn 详情（锚点条点击任意点可定位），只是不进典型列表、不计数
                String userInput0 = events.stream()
                        .map(x -> (String) x.get("turnUser"))
                        .filter(s -> s != null && !s.isBlank())
                        .findFirst().orElse(null);
                Map<String, Object> t0 = new LinkedHashMap<>();
                t0.put("traceId", seg.get(0).get("traceId"));
                t0.put("startTs", events.get(0).get("ts"));
                t0.put("endTs", events.get(events.size() - 1).get("ts"));
                t0.put("tokens", events.stream().mapToLong(x -> (Long) x.get("tokens")).sum());
                t0.put("failures", events.stream().filter(x -> (Boolean) x.get("failed")).count());
                t0.put("problemFailed", false);
                t0.put("problemTokens", 0L);
                t0.put("problemLatencyMs", 0L);
                t0.put("userInput", userInput0);
                t0.put("events", events);
                sp.put("ref", t0);
                turns.add(t0); // 全量列表：普通 turn 也展示（无红色问题标记），与锚点条一一对应
                continue;
            }
            turnCount++;
            String userInput = events.stream()
                    .map(x -> (String) x.get("turnUser"))
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst().orElse(null);
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("traceId", seg.get(0).get("traceId"));
            t.put("startTs", events.get(0).get("ts"));
            t.put("endTs", events.get(events.size() - 1).get("ts"));
            t.put("tokens", events.stream().mapToLong(x -> (Long) x.get("tokens")).sum());
            t.put("failures", events.stream().filter(x -> (Boolean) x.get("failed")).count());
            t.put("problemFailed", problemFailed);
            t.put("problemTokens", problemTokens);
            t.put("problemLatencyMs", problemLatency);
            t.put("userInput", userInput);
            t.put("events", events);
            sp.put("ref", t); // 锚点条跳转映射用
            turns.add(t);
        }
        // 全量分布按时间排序，并给典型 turn 标记时间序位置（spanIdx）
        spans.sort(Comparator.comparing(x -> String.valueOf(x.get("ts"))));
        for (int i = 0; i < spans.size(); i++) {
            Object ref = spans.get(i).get("ref");
            if (ref instanceof Map<?, ?> refMap) {
                ((Map<String, Object>) refMap).put("spanIdx", i);
            }
        }
        // 锚点条定位：span=时间序第 N 段 → 返回该 turn 的完整详情（任意点可点击跳转）
        if (span != null && span >= 0 && span < spans.size()) {
            r.put("turnDetail", spans.get(span).get("ref"));
        }
        // 列表展示所有 turn，按时间序排列，与锚点条（同样时间序）一一对应：点红点滚动到对应卡片
        turns.sort(Comparator.comparing(x -> String.valueOf(x.get("startTs"))));
        r.put("turnCount", turnCount); // 真实含问题节点的 turn 数（覆盖节点统计里的链数）
        r.put("turns", turns);
        r.put("turnSpans", spans);
        return r;
    }

    /** Session（trace）切换下拉：按事件量排序，带失败数供预警标红。 */
    public Map<String, Object> sessions(String agentId, int days) {
        Instant from = days > 0 ? Instant.now().minusSeconds(days * 86400L) : null;
        Instant to = Instant.now();
        Map<String, Object> r = base(null, "sessions", repo.queryFlowSessions(from, to, agentId));
        r.put("agentId", agentId);
        r.put("days", days);
        return r;
    }

    // ==================== 核心分析 ====================

    /**
     * Agent 级流程健康汇总：把按节点算的循环参与(S3)/失败率(S7)/健康度汇总回 Agent 维度，
     * 供 agent-analysis 对比表扩展「循环率 / 无效操作率 / 健康分」三列。
     * 口径：循环率 = 循环参与操作数 / 总操作数；无效操作率 = 失败操作数 / 总操作数；
     * 健康分 = 100 − 12×bad 节点 − 4×warn 节点 − min(18, 循环率×60) − min(15, 失败率×75)，
     * ≥85 优(ok) / 60-84 中(warn) / <60 差(bad)；窗口内无事件返回 null（前端显示 —）。
     */
    public Map<String, Object> flowSummary(int days) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : repo.queryAgentComparison()) {
            String agentId = String.valueOf(a.get("agentId"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("agentId", agentId);
            Model m = analyze(agentId, days, null);
            long ops = m.totalOps;
            if (ops == 0) {
                r.put("loopRate", null);
                r.put("invalidRate", null);
                r.put("healthScore", null);
                r.put("grade", null);
            } else {
                double loopRate = m.loopJoinOps / (double) ops;
                double invalidRate = m.failOps / (double) ops;
                double score = 100
                        - m.healthBad * 12.0 - m.healthWarn * 4.0
                        - Math.min(18, loopRate * 100 * 0.6)
                        - Math.min(15, invalidRate * 100 * 0.75);
                score = Math.max(0, Math.min(100, score));
                r.put("loopRate", Math.round(loopRate * 1000) / 1000.0);
                r.put("invalidRate", Math.round(invalidRate * 1000) / 1000.0);
                r.put("healthScore", (int) Math.round(score));
                r.put("grade", score >= 85 ? "ok" : score >= 60 ? "warn" : "bad");
            }
            r.put("nodeOk", m.healthOk);
            r.put("nodeWarn", m.healthWarn);
            r.put("nodeBad", m.healthBad);
            r.put("turns", m.turnTotal);
            r.put("totalOps", ops);
            out.add(r);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("days", days);
        res.put("agents", out);
        return res;
    }

    private record Pattern(String seq, long count, long tokens, long latency, boolean loop, String smell,
                           List<String> samples) { }

    private static final class Model {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        String agentId;
        // agent 级聚合（flowSummary 用）：总操作数 / 失败操作数 / 循环参与操作数 / 节点健康计数 / turn 数
        long totalOps, failOps, loopJoinOps, turnTotal;
        int healthOk, healthWarn, healthBad;
    }

    private Model analyze(String agentId, int days, String sessionId) {
        Instant from = days > 0 ? Instant.now().minusSeconds(days * 86400L) : null;
        Instant to = Instant.now();
        List<Map<String, Object>> raw = repo.queryFlowEvents(from, to, agentId, sessionId);

        // 1. turn 分组（优先按 trace；单事件 trace 退化为 session 串联）并归一化节点
        Map<String, List<Rec>> traces = new LinkedHashMap<>();
        groupTurns(raw).forEach((k, v) -> traces.put(k, toRecs(v)));

        Model m = new Model();
        m.agentId = agentId;
        if (traces.isEmpty()) {
            return m;
        }

        // 2. 节点统计：count / tokens / p95 延迟 / 失败数 / 参与循环占比 / 证据
        Map<String, Long> nCount = new LinkedHashMap<>(), nTokens = new LinkedHashMap<>(),
                nFail = new LinkedHashMap<>();
        Map<String, List<Long>> nLat = new LinkedHashMap<>();
        Map<String, Integer> loopJoin = new LinkedHashMap<>();   // S3：节点出现在循环中的次数
        int totalLoopJoin = 0;
        long totalTokens = 0;
        Map<String, LinkedHashSet<String>> nodeTraces = new LinkedHashMap<>();

        // 3. 变体归并 + 边统计
        Map<String, long[]> patStat = new LinkedHashMap<>();      // seq → [count, tokens, latency]
        Map<String, Boolean> patLoop = new LinkedHashMap<>();
        Map<String, String> patSmell = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> patSamples = new LinkedHashMap<>();
        Map<String, long[]> edgeStat = new LinkedHashMap<>();     // from|to → [count, tokens]
        Map<String, LinkedHashSet<String>> loopTraceIds = new LinkedHashMap<>(); // loopKey → traces
        Map<String, Integer> loopKeyCount = new LinkedHashMap<>();

        for (Map.Entry<String, List<Rec>> e : traces.entrySet()) {
            List<Rec> seq = e.getValue();
            if (seq.isEmpty()) {
                continue;
            }
            String pathKey = compressPath(seq); // 连续重复节点游程压缩（node×N），避免长链堆出满屏重复文字
            boolean[] inLoop = markLoopOps(seq); // agent 级循环率：标记参与循环的操作位置（与 detectLoops 同规则）
            long tk = seq.stream().mapToLong(r -> r.tokens).sum();
            long lt = seq.stream().mapToLong(r -> r.latency).sum();
            patStat.computeIfAbsent(pathKey, k -> new long[3])[0]++;
            patStat.get(pathKey)[1] += tk;
            patStat.get(pathKey)[2] += lt;
            patSamples.computeIfAbsent(pathKey, k -> new LinkedHashSet<>());
            if (patSamples.get(pathKey).size() < 5) {
                patSamples.get(pathKey).add(e.getKey());
            }
            for (int i = 0; i < seq.size(); i++) {
                Rec r = seq.get(i);
                nCount.merge(r.node, 1L, Long::sum);
                nTokens.merge(r.node, r.tokens, Long::sum);
                nFail.merge(r.node, r.failed ? 1L : 0L, Long::sum);
                if (inLoop[i]) {
                    m.loopJoinOps++; // agent 级循环率分母对齐：每个操作步最多计一次，保证 loopJoinOps ≤ totalOps
                }
                if (r.latency > 0) {
                    nLat.computeIfAbsent(r.node, k -> new ArrayList<>()).add(r.latency);
                }
                nodeTraces.computeIfAbsent(r.node, k -> new LinkedHashSet<>()).add(e.getKey());
                totalTokens += r.tokens;
                if (i > 0) {
                    String[] ek = {seq.get(i - 1).node, r.node};
                    edgeStat.computeIfAbsent(ek[0] + "|" + ek[1], k -> new long[2])[0]++;
                    edgeStat.get(ek[0] + "|" + ek[1])[1] += r.tokens;
                }
            }
            // 4. 循环检测：长度 1（同节点连续重复）及长度 2-4 的连续子序列出现 ≥ loopMinRepeats 次
            for (String loopKey : detectLoops(seq)) {
                patLoop.put(pathKey, true);
                loopTraceIds.computeIfAbsent(loopKey, k -> new LinkedHashSet<>()).add(e.getKey());
                loopKeyCount.merge(loopKey, 1, Integer::sum);
                for (String nd : loopKey.split("→")) {
                    loopJoin.merge(nd, 1, Integer::sum);
                    totalLoopJoin++;
                }
            }
            // 5. 坏味道标记
            String smell = detectSmell(seq);
            if (smell != null) {
                patSmell.merge(pathKey, smell, (a, b) -> a.equals(b) ? a : a + "+" + b);
            }
        }

        // 6. 全节点 P95 均值（S2 基线）
        double avgP95 = nLat.entrySet().stream()
                .mapToDouble(x -> percentile(x.getValue(), 95)).average().orElse(0);
        long loopTotal = Math.max(totalLoopJoin, 1);

        // 7. 节点 + 信号
        for (String id : nCount.keySet()) {
            List<Long> lats = nLat.getOrDefault(id, List.of());
            double p95 = percentile(lats, 95);
            long tk = nTokens.getOrDefault(id, 0L);
            long fail = nFail.getOrDefault(id, 0L);
            double burn = totalTokens == 0 ? 0 : (double) tk / totalTokens;
            double join = loopTotal == 0 ? 0 : (loopJoin.getOrDefault(id, 0) / (double) loopTotal);
            double failRatio = nCount.get(id) == 0 ? 0 : (double) fail / nCount.get(id);
            List<String> signals = new ArrayList<>();
            List<String> high = new ArrayList<>();
            Map<String, Object> ev = new LinkedHashMap<>();
            if (burn > s1TokenRatio) {
                signals.add("S1 烧钱");
                high.add("S1");
                ev.put("tokenRatio", Math.round(burn * 100) + "%");
            }
            if (avgP95 > 0 && p95 > avgP95 * s2P95Ratio) {
                signals.add("S2 慢");
                ev.put("p95Ms", Math.round(p95));
                ev.put("avgP95Ms", Math.round(avgP95));
            }
            if (join > s3JoinRatio) {
                signals.add("S3 循环参与");
                high.add("S3");
                ev.put("loopJoin", Math.round(join * 100) + "%");
            }
            if (failRatio > s7FailRatio) {
                signals.add("S7 失败率");
                high.add("S7");
                ev.put("failRatio", Math.round(failRatio * 100) + "%");
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", id);
            node.put("label", NODE_LABELS.getOrDefault(id, id));
            node.put("operation", id.split("@")[0]);
            node.put("layer", id.split("@")[1]);
            node.put("count", nCount.get(id));
            node.put("tokens", tk);
            node.put("p95LatencyMs", Math.round(p95));
            node.put("failures", fail);
            node.put("signals", signals);
            node.put("highSignals", high);
            node.put("evidence", ev);
            node.put("health", high.isEmpty() ? (signals.isEmpty() ? "ok" : "warn") : "bad");
            switch ((String) node.get("health")) {
                case "bad" -> m.healthBad++;
                case "warn" -> m.healthWarn++;
                default -> m.healthOk++;
            }
            m.totalOps += nCount.get(id);
            m.failOps += fail;
            List<String> samples = new ArrayList<>(nodeTraces.getOrDefault(id, new LinkedHashSet<>()));
            node.put("sampleTraceIds", samples.subList(0, Math.min(5, samples.size())));
            node.put("traceCount", samples.size()); // 含该节点的 turn 数（问题详情滑块用）
            m.nodes.add(node);
        }
        m.nodes.sort(Comparator.comparingLong(x -> -(Long) x.get("count")));

        // 8. 边
        for (Map.Entry<String, long[]> e : edgeStat.entrySet()) {
            String[] p = e.getKey().split("\\|");
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", p[0]);
            edge.put("to", p[1]);
            edge.put("count", e.getValue()[0]);
            edge.put("tokens", e.getValue()[1]);
            m.edges.add(edge);
        }
        m.edges.sort(Comparator.comparingLong(x -> -(Long) x.get("count")));
        m.turnTotal = traces.size();

        // 9. 流程模式
        for (Map.Entry<String, long[]> e : patStat.entrySet()) {
            m.patterns.add(new Pattern(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                    patLoop.getOrDefault(e.getKey(), false),
                    patSmell.getOrDefault(e.getKey(), ""),
                    new ArrayList<>(patSamples.getOrDefault(e.getKey(), new LinkedHashSet<>()))));
        }
        return m;
    }

    /** 路径游程压缩：连续相同节点合并为 node×N（流程变体展示用，避免长链堆出满屏重复文字；相同压缩结构仍归并为同一模式）。 */
    private String compressPath(List<Rec> seq) {
        StringBuilder sb = new StringBuilder();
        String prev = null;
        int n = 0;
        for (Rec r : seq) {
            if (r.node.equals(prev)) {
                n++;
            } else {
                appendRun(sb, prev, n);
                prev = r.node;
                n = 1;
            }
        }
        appendRun(sb, prev, n);
        return sb.toString();
    }

    private void appendRun(StringBuilder sb, String node, int n) {
        if (node == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("→");
        }
        sb.append(node);
        if (n > 1) {
            sb.append("×").append(n);
        }
    }

    /** turn 分组：优先按 trace；所有 trace 都只有 1 个事件（埋点按操作发 trace_id）时退化为按 session 串联（ts 排序），否则转移图全是孤立节点。 */
    private Map<String, List<Map<String, Object>>> groupTurns(List<Map<String, Object>> raw) {
        Map<String, List<Map<String, Object>>> byTrace = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            byTrace.computeIfAbsent(String.valueOf(r.get("traceId")), k -> new ArrayList<>()).add(r);
        }
        boolean allSingle = byTrace.size() > 1
                && byTrace.values().stream().allMatch(l -> l.size() <= 1);
        if (!allSingle) {
            return byTrace;
        }
        Map<String, List<Map<String, Object>>> bySession = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            bySession.computeIfAbsent(String.valueOf(r.get("sessionId")), k -> new ArrayList<>()).add(r);
        }
        bySession.values().forEach(l -> l.sort(Comparator.comparing(r -> String.valueOf(r.get("ts")))));
        return bySession;
    }

    /** 事件行 → 轻量记录（按 ts 排序，保证 session 串联时序正确）。 */
    private List<Rec> toRecs(List<Map<String, Object>> events) {
        List<Map<String, Object>> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(r -> String.valueOf(r.get("ts"))));
        List<Rec> out = new ArrayList<>(sorted.size());
        for (Map<String, Object> r : sorted) {
            String op = String.valueOf(r.get("operation")).toLowerCase();
            String layer = String.valueOf(r.get("layer")).toLowerCase();
            out.add(new Rec((String) r.get("traceId"), op + "@" + layer,
                    String.valueOf(r.get("memoryKey")),
                    (Long) r.get("tokens"), (Long) r.get("latencyMs"),
                    "failed".equals(r.get("status"))));
        }
        return out;
    }

    /** 循环检测：长度 1（同节点连续重复）及长度 2..loopWindow 的子序列出现 ≥ loopMinRepeats 次。
     *  返回该链内命中的全部循环 key（去重）——不能只返回第一个，否则长链里先命中的循环会掩盖其余循环。 */
    private List<String> detectLoops(List<Rec> seq) {
        List<String> out = new ArrayList<>();
        // 长度 1：同一节点连续重复 ≥ loopMinRepeats 次（如技能写入连续 2000 次）
        int run = 1;
        for (int i = 1; i <= seq.size(); i++) {
            if (i < seq.size() && seq.get(i).node.equals(seq.get(i - 1).node)) {
                run++;
            } else {
                if (run >= loopMinRepeats && !out.contains(seq.get(i - 1).node)) {
                    out.add(seq.get(i - 1).node);
                }
                run = 1;
            }
        }
        // 长度 2..loopWindow：滑窗计数
        for (int len = 2; len <= loopWindow; len++) {
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (int i = 0; i + len <= seq.size(); i++) {
                String k = seq.subList(i, i + len).stream().map(r -> r.node).collect(Collectors.joining("→"));
                if (seen.merge(k, 1, Integer::sum) >= loopMinRepeats && !out.contains(k)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /**
     * 标记 seq 中参与循环的操作位置（agent 级循环率专用，比 detectLoops 更严格）：
     * 长度 1：同节点连续游程 ≥ loopMinRepeats → 整段均为空转操作；
     * 长度 2..loopWindow：同一模式「背靠背连续重复」≥ loopMinRepeats 次 → 标记全部覆盖位置。
     * 要求连续相邻是为了把「分散的正常重复调用」与「陷入循环的空转操作」区分开，
     * 保证比率反映真实病态空转占比（每步最多计一次，恒 ≤ 1）。
     */
    private boolean[] markLoopOps(List<Rec> seq) {
        boolean[] mark = new boolean[seq.size()];
        // 长度 1 游程
        int run = 1;
        for (int i = 1; i <= seq.size(); i++) {
            if (i < seq.size() && seq.get(i).node.equals(seq.get(i - 1).node)) {
                run++;
            } else {
                if (run >= loopMinRepeats) {
                    for (int k = i - run; k < i; k++) {
                        mark[k] = true;
                    }
                }
                run = 1;
            }
        }
        // 长度 2..loopWindow：模式背靠背连续重复 ≥ loopMinRepeats 次
        for (int len = 2; len <= loopWindow; len++) {
            int i = 0;
            while (i + len <= seq.size()) {
                String k = seq.subList(i, i + len).stream().map(r -> r.node).collect(Collectors.joining("→"));
                int rep = 1;
                while (rep < loopMinRepeats && i + (rep + 1) * len <= seq.size()
                        && seq.subList(i + rep * len, i + (rep + 1) * len).stream().map(r -> r.node)
                                .collect(Collectors.joining("→")).equals(k)) {
                    rep++;
                }
                if (rep >= loopMinRepeats) {
                    for (int p = i; p < i + rep * len; p++) {
                        mark[p] = true;
                    }
                    i += rep * len; // 跳过已标记段
                } else {
                    i++;
                }
            }
        }
        return mark;
    }

    /** 预置四条坏味道（设计文档 §3.3）：压缩风暴 / 检索打转 / 读写抖动 / 遗忘风暴。 */
    private String detectSmell(List<Rec> seq) {
        // 遗忘风暴：EXPIRE 占比超阈值
        long expires = seq.stream().filter(r -> r.node.startsWith("expire")).count();
        if (seq.size() > 3 && expires / (double) seq.size() > smellForgetRatio) {
            return "遗忘风暴";
        }
        // 压缩风暴：UPDATE@session 连续 ≥ loopMinRepeats 次
        int run = 0;
        for (Rec r : seq) {
            run = r.node.equals("update@session") ? run + 1 : 0;
            if (run >= loopMinRepeats) {
                return "压缩风暴";
            }
        }
        // 检索打转：同 key READ ≥3 且中间无 WRITE；读写抖动：READ(k)→WRITE(k) ≥2 次
        String readKey = null;
        int readRun = 0, shake = 0;
        for (Rec r : seq) {
            if (r.node.equals("write@session") || r.node.equals("update@session")) {
                readKey = null;
                readRun = 0;
                continue;
            }
            if (r.node.equals("read@session") || r.node.equals("read@skill") || r.node.equals("read@provider")) {
                if (r.key != null && r.key.equals(readKey)) {
                    if (++readRun >= loopMinRepeats) {
                        return "检索打转";
                    }
                } else {
                    readKey = r.key;
                    readRun = 1;
                }
            } else if ((r.node.equals("write@skill") || r.node.equals("write@provider"))
                    && r.key != null && r.key.equals(readKey)) {
                if (++shake >= 2) {
                    return "读写抖动";
                }
            }
        }
        return null;
    }

    /** 信号 → 建议动作（喂给治理柱，设计文档 §3.4）。 */
    private String recommendation(List<String> signals) {
        List<String> act = new ArrayList<>();
        if (signals.contains("S1 烧钱")) {
            act.add("成本告警：优化该节点实现的 token 用量");
        }
        if (signals.contains("S2 慢")) {
            act.add("性能告警：检查该节点外部依赖延迟");
        }
        if (signals.contains("S3 循环参与")) {
            act.add("压缩/检索时机接管，打断循环");
        }
        if (signals.contains("S7 失败率")) {
            act.add("排查该节点工具/外部服务失败原因");
        }
        return String.join("；", act);
    }

    private Map<String, Object> base(Model m, String key, Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (m != null) {
            r.put("agentId", m.agentId);
        }
        r.put(key, data);
        return r;
    }

    /** P95（最近的秩统计，无需精确排序大数组）。 */
    private double percentile(List<Long> vals, double p) {
        if (vals.isEmpty()) {
            return 0;
        }
        List<Long> s = vals.stream().sorted().collect(Collectors.toList());
        int idx = (int) Math.ceil(p / 100.0 * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }
}
