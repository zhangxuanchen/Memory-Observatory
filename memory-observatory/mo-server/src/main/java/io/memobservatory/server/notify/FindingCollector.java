package io.memobservatory.server.notify;

import io.memobservatory.server.analytics.AnalyticsProblemService;
import io.memobservatory.server.flow.FlowAnalysisService;
import io.memobservatory.server.risk.RiskScanService;
import io.memobservatory.server.storage.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把三类问题源归一化成 {@link AlertCandidate}。
 *
 * <p>三类源的数据结构完全不同（阈值规则的命中行按规则而异、流程问题是节点卡片、
 * 风险命中是脱敏片段），归一化放在这里，{@link NotifyScheduler} 只管状态机与发送。
 *
 * <p>逐 agent try-catch：单个 agent 的数据源抛错不该拖垮整轮评估。
 */
@Slf4j
@Component
public class FindingCollector {

    private final EventRepository repo;
    private final AnalyticsProblemService problemService;
    private final FlowAnalysisService flowService;
    private final RiskScanService riskService;

    public FindingCollector(EventRepository repo,
                           AnalyticsProblemService problemService,
                           FlowAnalysisService flowService,
                           RiskScanService riskService) {
        this.repo = repo;
        this.problemService = problemService;
        this.flowService = flowService;
        this.riskService = riskService;
    }

    /**
     * 阈值规则的严重度。
     *
     * <p>命中行里没有 severity 字段（那是纯数字聚合的结果），所以在这里静态指定。
     * 分档依据是「坏了没有」：报错是功能已坏，烧钱/卡住是正在致损，趋势性异常尚未致损。
     */
    private static final Set<String> CRITICAL_RULES = Set.of("skill-error");

    private static final Set<String> MEDIUM_RULES =
            Set.of("skill-count", "tool-token", "mem-churn");

    /**
     * 阈值规则的实体字段。
     *
     * <p>不能写一条通用的「turnId &gt; sessionId &gt; memoryKey」优先级链：
     * skill-repeat 与 skill-error 的命中行里同时带 skill，只取 turnId/sessionId
     * 会把「同一 turn 里两个不同技能各自循环」压成一条告警，丢掉一半信息。
     * 只有 agent 级聚合的 4 条规则用 {@code -} 占位。
     */
    private static final Map<String, String[]> RULE_ENTITY_FIELDS = Map.of(
            "skill-repeat", new String[]{"turnId", "skill"},
            "skill-error", new String[]{"sessionId", "skill"},
            "turn-token", new String[]{"turnId"},
            "turn-count", new String[]{"turnId"},
            "turn-tool", new String[]{"turnId"},
            "turn-latency", new String[]{"turnId"},
            "memory", new String[]{"sessionId"},
            "session-token", new String[]{"sessionId"},
            "prompt-share", new String[]{"sessionId"},
            "mem-churn", new String[]{"key"});

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "critical", 0, "high", 1, "medium", 2);

    /**
     * 一轮采集的结果。
     *
     * @param candidates 归一化后的候选
     * @param failures   抛错的数据源次数。&gt;0 表示本轮采集不完整，
     *                   调用方据此跳过「恢复」判定——否则「这次没查到」会被误判成「问题已经好了」
     */
    public record Collected(List<AlertCandidate> candidates, int failures) {
        public boolean degraded() {
            return failures > 0;
        }
    }

    /**
     * 收集全部候选。
     *
     * @param windowDays  评估窗口（天）
     * @param semantic    阈值与风险两类源是否走语义过滤
     * @param agentsLimit 最多评估几个 agent
     * @param topHits     载荷里最多带几条命中明细
     * @param minSeverity 低于该级别直接丢弃
     */
    public Collected collect(int windowDays, boolean semantic, int agentsLimit,
                             int topHits, String minSeverity) {
        int floor = SEVERITY_RANK.getOrDefault(minSeverity, 2);
        List<AlertCandidate> out = new ArrayList<>();
        List<Map<String, Object>> agents;
        try {
            agents = repo.queryAgents(agentsLimit);
        } catch (Exception e) {
            log.warn("notify 取 agent 列表失败，本轮跳过 cause={}", e.toString(), e);
            return new Collected(out, 1);
        }

        int failures = 0;
        for (Map<String, Object> a : agents) {
            String agentId = String.valueOf(a.get("agentId"));
            failures += collectThreshold(agentId, windowDays, semantic, topHits, floor, out);
            failures += collectFlow(agentId, windowDays, topHits, floor, out);
            failures += collectRisk(agentId, windowDays, semantic, topHits, floor, out);
        }
        return new Collected(out, failures);
    }

    // ==================== 源一：阈值规则 ====================

    /** @return 抛错次数（0 或 1），供 {@link Collected#failures()} 累计 */
    private int collectThreshold(String agentId, int windowDays, boolean semantic,
                                 int topHits, int floor, List<AlertCandidate> out) {
        Map<String, Object> result;
        try {
            result = problemService.problems(windowDays, agentId, semantic);
        } catch (Exception e) {
            log.warn("notify 阈值规则评估失败 agent={} cause={}", agentId, e.toString(), e);
            return 1;
        }
        Object raw = result.get("problems");
        if (!(raw instanceof List<?> list)) {
            return 0;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> p)) {
                continue;
            }
            String ruleId = text(p.get("key"));
            String severity = thresholdSeverity(ruleId);
            if (SEVERITY_RANK.getOrDefault(severity, 2) > floor) {
                continue;
            }
            Object hitsRaw = p.get("hits");
            if (!(hitsRaw instanceof List<?> hits) || hits.isEmpty()) {
                continue;
            }
            // 一条规则可能有多个实体命中（不同 turn / 不同 session），逐个成了自己的候选
            for (Object h : hits) {
                if (!(h instanceof Map<?, ?> hit)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> hitMap = (Map<String, Object>) hit;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("source", "threshold");
                payload.put("agentId", agentId);
                payload.put("type", ruleId);
                payload.put("severity", severity);
                payload.put("title", text(p.get("title")));
                payload.put("threshold", text(p.get("threshold")));
                payload.put("hitCount", 1);
                payload.put("hits", List.of(copyHit(hitMap)));
                payload.put("detectedAt", Instant.now().toString());
                out.add(new AlertCandidate(thresholdKey(ruleId, agentId, hitMap), "threshold",
                        agentId, severity, text(p.get("title")), payload));
            }
        }
        return 0;
    }

    /** 阈值规则的严重度：命中行没有该字段，在这里按规则静态指定；未知规则回落 medium。 */
    public static String thresholdSeverity(String ruleId) {
        if (CRITICAL_RULES.contains(ruleId)) {
            return "critical";
        }
        if (MEDIUM_RULES.contains(ruleId)) {
            return "medium";
        }
        return HIGH_FALLBACK.contains(ruleId) ? "high" : "medium";
    }

    /** 已知的高档规则；不在表里的规则名（新增或拼错）回落 medium。 */
    private static final Set<String> HIGH_FALLBACK = Set.of(
            "skill-slow", "skill-repeat", "turn-token", "turn-count", "turn-tool",
            "turn-latency", "memory", "session-token", "model-slow", "prompt-share");

    // ==================== 源二：流程分析问题 ====================

    /** @return 抛错次数（0 或 1），供 {@link Collected#failures()} 累计 */
    private int collectFlow(String agentId, int windowDays, int topHits, int floor,
                            List<AlertCandidate> out) {
        Map<String, Object> result;
        try {
            result = flowService.issues(agentId, windowDays, null);
        } catch (Exception e) {
            log.warn("notify 流程分析评估失败 agent={} cause={}", agentId, e.toString(), e);
            return 1;
        }
        Object raw = result.get("issues");
        if (!(raw instanceof List<?> list)) {
            return 0;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> c)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> card = (Map<String, Object>) c;
            String severity = text(card.get("severity"));
            if (!SEVERITY_RANK.containsKey(severity) || SEVERITY_RANK.get(severity) > floor) {
                continue;
            }
            String node = text(card.get("node"));
            List<String> signals = stringList(card.get("signals"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "flow");
            payload.put("agentId", agentId);
            payload.put("type", node);
            payload.put("severity", severity);
            payload.put("title", text(card.get("label")));
            payload.put("label", text(card.get("label")));
            payload.put("signals", signals);
            payload.put("recommendation", text(card.get("recommendation")));
            payload.put("sampleTraceIds", stringList(card.get("sampleTraceIds")));
            payload.put("turnCount", card.get("turnCount"));
            payload.put("detectedAt", Instant.now().toString());
            out.add(new AlertCandidate(flowKey(agentId, node, signals), "flow",
                    agentId, severity, text(card.get("label")), payload));
        }
        return 0;
    }

    // ==================== 源三：内容风险 ====================

    /** @return 抛错次数（0 或 1），供 {@link Collected#failures()} 累计 */
    private int collectRisk(String agentId, int windowDays, boolean semantic,
                            int topHits, int floor, List<AlertCandidate> out) {
        Map<String, Object> result;
        try {
            result = riskService.scan(agentId, windowDays, semantic);
        } catch (Exception e) {
            log.warn("notify 风险扫描失败 agent={} cause={}", agentId, e.toString(), e);
            return 1;
        }
        Object raw = result.get("hits");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return 0;
        }
        // 按 (type, field) 归并：同一个泄漏的密钥出现在 100 个事件里，只产生一条告警
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> h)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) h;
            grouped.computeIfAbsent(riskKey(agentId, text(hit.get("type")), text(hit.get("field"))),
                    k -> new ArrayList<>()).add(hit);
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            List<Map<String, Object>> hits = e.getValue();
            Map<String, Object> first = hits.get(0);
            String severity = text(first.get("severity"));
            if (!SEVERITY_RANK.containsKey(severity) || SEVERITY_RANK.get(severity) > floor) {
                continue;
            }
            List<Map<String, Object>> shown = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                if (shown.size() >= topHits) {
                    break;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("eventId", h.get("eventId"));
                one.put("ts", h.get("ts"));
                one.put("sessionId", h.get("sessionId"));
                one.put("traceId", h.get("traceId"));
                one.put("layer", h.get("layer"));
                one.put("operation", h.get("operation"));
                one.put("field", h.get("field"));
                // snippet 已在 RiskScanService 内脱敏（命中密钥替换为掩码）；此处不带原文
                one.put("snippet", h.get("snippet"));
                shown.add(one);
            }
            String type = text(first.get("type"));
            String name = text(first.get("name"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "risk");
            payload.put("agentId", agentId);
            payload.put("type", type);
            payload.put("severity", severity);
            payload.put("title", name);
            payload.put("name", name);
            payload.put("field", text(first.get("field")));
            payload.put("hitCount", hits.size());
            payload.put("hits", shown);
            payload.put("detectedAt", Instant.now().toString());
            out.add(new AlertCandidate(e.getKey(), "risk", agentId, severity, name, payload));
        }
        return 0;
    }

    // ==================== 去重键 ====================

    /** 阈值规则键：{@code thr|{ruleId}|{agentId}|{entity}}。 */
    public static String thresholdKey(String ruleId, String agentId, Map<String, Object> hit) {
        return "thr|" + ruleId + "|" + agentId + "|" + thresholdEntity(ruleId, hit);
    }

    /** 取该规则对应的实体串；agent 级聚合规则用 {@code -} 占位。 */
    private static String thresholdEntity(String ruleId, Map<String, Object> hit) {
        String[] fields = RULE_ENTITY_FIELDS.get(ruleId);
        if (fields == null) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (String f : fields) {
            String v = text(hit.get(f));
            if (v.isBlank()) {
                return "-";     // 字段缺失时退到 agent 级，至少保证键稳定
            }
            parts.add(v);
        }
        return String.join("/", parts);
    }

    /**
     * 流程问题键：{@code flow|{agentId}|{node}|{signals 排序后拼接}}。
     *
     * <p>signals 必须排序后再拼：不排序的话，同一条问题每轮的信号顺序可能不同，
     * 会被算成新问题而反复重推。
     */
    public static String flowKey(String agentId, String node, List<String> signals) {
        List<String> sorted = new ArrayList<>(signals);
        Collections.sort(sorted);
        return "flow|" + agentId + "|" + node + "|" + String.join("/", sorted);
    }

    /**
     * 内容风险键：{@code risk|{agentId}|{type}|{field}}。
     *
     * <p>用规则的 {@code type}（稳定的 ASCII 分组键）而不是 {@code name}（中文展示名）：
     * 展示名一旦改文案，所有历史键都会失效并触发一轮重推。
     *
     * <p>这里有意做粗——不哈希、也不落库 snippet 或原文。
     */
    public static String riskKey(String agentId, String type, String field) {
        return "risk|" + agentId + "|" + type + "|" + field;
    }

    // ==================== 小工具 ====================

    /** 命中行里只保留标量字段，避免把整行原样塞进载荷。 */
    private static Map<String, Object> copyHit(Map<String, Object> hit) {
        Map<String, Object> one = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : hit.entrySet()) {
            Object v = e.getValue();
            if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean) {
                one.put(e.getKey(), v);
            }
        }
        return one;
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object v : list) {
                out.add(text(v));
            }
        }
        return out;
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}