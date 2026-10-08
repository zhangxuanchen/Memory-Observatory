package io.memobservatory.server.analytics;

import io.memobservatory.server.semantic.ConfirmKind;
import io.memobservatory.server.semantic.SemanticFilterService;
import io.memobservatory.server.storage.EventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阈值规则问题分析：把「可能问题」的判定从控制器里抽出来，供 REST 页面与告警推送两侧共用。
 *
 * <p>抽出来的理由是判定口径只能有一份。若通知路径另写一遍，
 * 「页面看到的」与「推送出去的」迟早分叉——分叉之后两边都不可信。
 *
 * <p>响应结构（{@code days / agent / windowFrom / windowTo / thresholds / problems}）
 * 与原 {@code ApiController.analyticsProblems} 逐字一致，抽取不改行为。
 */
@Service
public class AnalyticsProblemService {

    private final EventRepository repo;
    private final SemanticFilterService semanticFilter;

    // ===== 问题分析 · 阈值（application.yml: mo.analytics.problems.*）=====
    private final long probSkillSlowMs;
    private final long probSkillCount;
    private final long probToolToken;
    private final long probTurnToken;
    private final long probTurnCount;
    private final long probTurnToolCount;
    private final long probTurnLatencyMs;
    private final long probMemCompressions;
    private final double probMemFreeRatio;
    private final long probModelSlowMs;
    private final long probSessionToken;
    private final double probPromptRatio;
    private final long probKeyWrites;
    private final long probSkillRepeat;
    private final long probSkillErrorMin;

    public AnalyticsProblemService(
            EventRepository repo,
            SemanticFilterService semanticFilter,
            @Value("${mo.analytics.problems.skill-slow-ms:30000}") long probSkillSlowMs,
            @Value("${mo.analytics.problems.skill-count:200}") long probSkillCount,
            @Value("${mo.analytics.problems.tool-token:5000}") long probToolToken,
            @Value("${mo.analytics.problems.turn-token:5000}") long probTurnToken,
            @Value("${mo.analytics.problems.turn-count:20}") long probTurnCount,
            @Value("${mo.analytics.problems.turn-tool-count:8}") long probTurnToolCount,
            @Value("${mo.analytics.problems.turn-latency-ms:60000}") long probTurnLatencyMs,
            @Value("${mo.analytics.problems.memory-compressions:2}") long probMemCompressions,
            @Value("${mo.analytics.problems.memory-free-ratio:0.25}") double probMemFreeRatio,
            @Value("${mo.analytics.problems.model-slow-ms:5000}") long probModelSlowMs,
            @Value("${mo.analytics.problems.session-token:100000}") long probSessionToken,
            @Value("${mo.analytics.problems.prompt-share-ratio:0.75}") double probPromptRatio,
            @Value("${mo.analytics.problems.memory-key-writes:10}") long probKeyWrites,
            @Value("${mo.analytics.problems.skill-repeat-count:5}") long probSkillRepeat,
            @Value("${mo.analytics.problems.skill-error-count:0}") long probSkillErrorMin) {
        this.repo = repo;
        this.semanticFilter = semanticFilter;
        this.probSkillSlowMs = probSkillSlowMs;
        this.probSkillCount = probSkillCount;
        this.probToolToken = probToolToken;
        this.probTurnToken = probTurnToken;
        this.probTurnCount = probTurnCount;
        this.probTurnToolCount = probTurnToolCount;
        this.probTurnLatencyMs = probTurnLatencyMs;
        this.probMemCompressions = probMemCompressions;
        this.probMemFreeRatio = probMemFreeRatio;
        this.probModelSlowMs = probModelSlowMs;
        this.probSessionToken = probSessionToken;
        this.probPromptRatio = probPromptRatio;
        this.probKeyWrites = probKeyWrites;
        this.probSkillRepeat = probSkillRepeat;
        this.probSkillErrorMin = probSkillErrorMin;
    }

    /**
     * 按阈值聚合 14 类可能问题。
     *
     * <p>semantic=true 时对每类命中做语义确认，剔除「任务本身就需要这么多资源」这类假阳性
     * （例如「跑测试的 Agent 用了 30s」不是问题，「客服 Agent 用了 30s」才是）。
     */
    public Map<String, Object> problems(int days, String agent, boolean semantic) {
        Instant now = Instant.now();
        Instant from = days > 0 ? now.minusSeconds(days * 86400L) : null;
        Instant to = now;
        String agentCond = (agent == null || agent.isBlank()) ? null : agent;

        List<Map<String, Object>> turns = repo.queryTurnMetrics(from, to, agentCond);

        List<Map<String, Object>> problems = new ArrayList<>();
        problems.add(problem("skill-slow", "skill 执行时间过长", probSkillSlowMs + " ms",
                repo.querySlowSkillByAgent(from, to, agentCond, probSkillSlowMs)));
        problems.add(problem("skill-count", "skill 执行次数过多", "> " + probSkillCount + " 次",
                repo.querySkillCountByAgent(from, to, agentCond, probSkillCount)));
        problems.add(problem("skill-repeat", "Skill 被反复调用（单 turn 循环/震荡）",
                "单 turn 同技能 > " + probSkillRepeat + " 次",
                repo.querySkillRepeat(from, to, agentCond, probSkillRepeat)));
        problems.add(problem("skill-error", "Skill 执行报错",
                "status = failed 且次数 > " + probSkillErrorMin,
                repo.querySkillErrors(from, to, agentCond, probSkillErrorMin)));
        problems.add(problem("tool-token", "tools 工具消耗 token 过大", "> " + probToolToken + " tokens",
                repo.queryToolTokenByAgent(from, to, agentCond, probToolToken)));

        List<Map<String, Object>> turnTokenHits = new ArrayList<>();
        List<Map<String, Object>> turnCountHits = new ArrayList<>();
        List<Map<String, Object>> turnToolHits = new ArrayList<>();
        List<Map<String, Object>> turnLatencyHits = new ArrayList<>();
        for (Map<String, Object> t : turns) {
            long tokens = ((Number) t.get("tokens")).longValue();
            long events = ((Number) t.get("events")).longValue();
            long tools = ((Number) t.get("toolEvents")).longValue();
            long ms = ((Number) t.get("totalMs")).longValue();
            if (tokens > probTurnToken) turnTokenHits.add(turnHit(t, "tokens", tokens));
            if (events > probTurnCount) turnCountHits.add(turnHit(t, "events", events));
            if (tools > probTurnToolCount) turnToolHits.add(turnHit(t, "toolEvents", tools));
            if (ms > probTurnLatencyMs) turnLatencyHits.add(turnHit(t, "totalMs", ms));
        }
        problems.add(problem("turn-token", "单 turn 消耗 token 过多",
                "> " + probTurnToken + " tokens", turnTokenHits));
        problems.add(problem("turn-count", "单 turn 执行次数过多",
                "> " + probTurnCount + " 次", turnCountHits));
        problems.add(problem("turn-tool", "单 turn 执行工具过多",
                "> " + probTurnToolCount + " 次", turnToolHits));
        problems.add(problem("turn-latency", "单 turn 整体延迟过高",
                "> " + probTurnLatencyMs + " ms", turnLatencyHits));
        problems.add(problem("memory", "记忆膨胀 / 压缩过于频繁",
                "压缩 ≥ " + probMemCompressions + " 次 或 空闲 < " + Math.round(probMemFreeRatio * 100) + "%",
                repo.queryMemoryPressure(from, to, agentCond, probMemCompressions, probMemFreeRatio)));
        // Harness 维度（H 组）
        problems.add(problem("model-slow", "模型推理延迟过高（harness-模型交互）",
                "model 层平均耗时 > " + probModelSlowMs + " ms",
                repo.queryModelSlowByAgent(from, to, agentCond, probModelSlowMs)));
        problems.add(problem("session-token", "单会话累计膨胀（harness 未收敛）",
                "会话内 token 合计 > " + probSessionToken + " tokens",
                repo.querySessionTokenByAgent(from, to, agentCond, probSessionToken)));
        problems.add(problem("prompt-share", "Prompt 上下文占比失衡",
                "系统+任务提示占比 > " + Math.round(probPromptRatio * 100) + "%",
                repo.queryPromptSharePressure(from, to, agentCond, probPromptRatio)));
        problems.add(problem("mem-churn", "记忆写入抖动（同一 key 反复写）",
                "同 key 写次数 > " + probKeyWrites + " 次",
                repo.queryMemoryKeyChurn(from, to, agentCond, probKeyWrites)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("agent", agentCond);
        result.put("windowFrom", from == null ? null : from.toString());
        result.put("windowTo", to.toString());
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("skillSlowMs", probSkillSlowMs);
        thresholds.put("skillCount", probSkillCount);
        thresholds.put("skillRepeatCount", probSkillRepeat);
        thresholds.put("skillErrorCount", probSkillErrorMin);
        thresholds.put("toolToken", probToolToken);
        thresholds.put("turnToken", probTurnToken);
        thresholds.put("turnCount", probTurnCount);
        thresholds.put("turnToolCount", probTurnToolCount);
        thresholds.put("turnLatencyMs", probTurnLatencyMs);
        thresholds.put("memoryCompressions", probMemCompressions);
        thresholds.put("memoryFreeRatio", probMemFreeRatio);
        thresholds.put("modelSlowMs", probModelSlowMs);
        thresholds.put("sessionToken", probSessionToken);
        thresholds.put("promptShareRatio", probPromptRatio);
        thresholds.put("memoryKeyWrites", probKeyWrites);
        result.put("thresholds", thresholds);
        result.put("problems", semantic ? semanticFilterProblems(problems) : problems);
        return result;
    }

    /**
     * 对问题分析的各类命中做语义确认。
     *
     * <p>命中行里通常只有数字指标（tokens / 延迟 / 次数），没有可判断的内容，
     * 因此按 (agentId, sessionId, turnId) 现查该 turn 的用户输入与事件摘要喂给 laya；
     * 查不到内容的候选直接保留（不猜）。同一个 turn 在一次请求内只查一次。
     */
    private List<Map<String, Object>> semanticFilterProblems(List<Map<String, Object>> problems) {
        ConfirmKind kind = ConfirmKind.PROBLEM;
        Map<String, Map<String, String>> turnCtx = new HashMap<>();
        for (Map<String, Object> p : problems) {
            Object raw = p.get("hits");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) list;
            String key = text(p.get("key"));
            String threshold = text(p.get("threshold"));
            var outcome = semanticFilter.filter(hits, kind,
                    hit -> problemState(hit, key, threshold, turnCtx));
            p.put("hits", outcome.hits());
            p.put("hitCount", outcome.hits().size());
            p.put("semantic", outcome.meta(kind));
        }
        return problems;
    }

    /** 构造问题分析命中行的 laya state；完全没有文本内容可判断时返回 null（该候选保留）。 */
    private Map<String, Object> problemState(Map<String, Object> hit, String hitType,
                                             String threshold, Map<String, Map<String, String>> cache) {
        String agentId = text(hit.get("agentId"));
        String sessionId = text(hit.get("sessionId"));
        String turnId = text(hit.get("turnId"));
        String turnUser = text(hit.get("turnUser"));
        String summary = text(hit.get("summary"));

        if (turnUser.isBlank() && summary.isBlank() && !turnId.isBlank()) {
            String ck = agentId + "|" + sessionId + "|" + turnId;
            Map<String, String> ctx = cache.computeIfAbsent(ck,
                    k -> repo.queryTurnText(agentId, sessionId, turnId));
            turnUser = ctx.getOrDefault("turnUser", "");
            summary = ctx.getOrDefault("summary", "");
        }
        if (turnUser.isBlank() && summary.isBlank()) {
            return null;      // 没有可判断的内容 → 保留，不猜
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("hit_type", hitType);
        state.put("threshold", threshold);
        state.put("observed", text(hit.get("metricValue")));
        state.put("node", text(hit.get("metric")));
        state.put("turn_user", truncate(turnUser, 300));
        state.put("summary", truncate(summary, 500));
        return state;
    }

    /** 组装单个问题结构：key / title / threshold / hitCount / hits。 */
    private static Map<String, Object> problem(String key, String title, String threshold,
                                               List<Map<String, Object>> hits) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", key);
        p.put("title", title);
        p.put("threshold", threshold);
        p.put("hitCount", hits.size());
        p.put("hits", hits);
        return p;
    }

    /** Turn 命中行：保留公共字段 + 当前问题关心的主指标值。 */
    private static Map<String, Object> turnHit(Map<String, Object> t, String valueKey, long value) {
        Map<String, Object> h = new LinkedHashMap<>();
        for (String k : new String[]{"agentId", "sessionId", "turnId", "tokens", "events", "toolEvents", "totalMs", "lastTs"}) {
            h.put(k, t.get(k));
        }
        h.put("metric", valueKey);
        h.put("metricValue", value);
        return h;
    }

    /** 安全取字符串（Map 里取出的值可能为 null）。 */
    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 截断到 laya 可处理的长度。
     * laya 的 max_len 为 512/1024 token，超出部分会在 build_sequence 里被静默截断——
     * 那会悄悄丢掉判断所需的上下文，所以宁可自己显式截断。
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}