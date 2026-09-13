package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.api.dto.EventQueryDTO;
import io.memobservatory.server.api.dto.SessionsQueryDTO;
import io.memobservatory.server.api.dto.SkillAnalysisQueryDTO;
import io.memobservatory.server.api.dto.TokenAnalyticsQueryDTO;
import io.memobservatory.server.api.dto.TokenHeatmapQueryDTO;
import io.memobservatory.server.api.dto.TokenStatsQueryDTO;
import io.memobservatory.server.api.dto.TraceListQueryDTO;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.storage.EventRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST 查询接口（Dashboard 调用）。
 *
 * GET /api/v1/agents/{id}/events      事件列表（记忆追踪）
 * GET /api/v1/sessions/{id}/timeline  会话时间线（记忆时间线）
 * GET /api/v1/agents/{id}/token-stats Token 统计（Token 分析）
 * GET /api/v1/agents/{id}/sessions    会话列表（时间线左侧）
 */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    @Autowired
    private EventRepository repo;

    @Autowired
    private io.memobservatory.server.flow.FlowAnalysisService flowService;

    // ===== 问题分析 · 阈值（application.yml: mo.analytics.problems.*）=====
    @Value("${mo.analytics.problems.skill-slow-ms:30000}")   private long probSkillSlowMs;
    @Value("${mo.analytics.problems.skill-count:200}")       private long probSkillCount;
    @Value("${mo.analytics.problems.tool-token:5000}")       private long probToolToken;
    @Value("${mo.analytics.problems.turn-token:5000}")       private long probTurnToken;
    @Value("${mo.analytics.problems.turn-count:20}")         private long probTurnCount;
    @Value("${mo.analytics.problems.turn-tool-count:8}")     private long probTurnToolCount;
    @Value("${mo.analytics.problems.turn-latency-ms:60000}") private long probTurnLatencyMs;
    @Value("${mo.analytics.problems.memory-compressions:2}") private long probMemCompressions;
    @Value("${mo.analytics.problems.memory-free-ratio:0.25}")private double probMemFreeRatio;
    @Value("${mo.analytics.problems.model-slow-ms:5000}")     private long probModelSlowMs;
    @Value("${mo.analytics.problems.session-token:100000}")   private long probSessionToken;
    @Value("${mo.analytics.problems.prompt-share-ratio:0.75}")private double probPromptRatio;
    @Value("${mo.analytics.problems.memory-key-writes:10}")   private long probKeyWrites;
    @Value("${mo.analytics.problems.skill-repeat-count:5}")   private long probSkillRepeat;
    @Value("${mo.analytics.problems.skill-error-count:0}")    private long probSkillErrorMin;

    /** 事件列表（记忆追踪）。 */
    @GetMapping("/agents/{agentId}/events")
    public Map<String, Object> events(
            EventQueryDTO query) {
        int safeLimit = Math.min(Math.max(query.getLimit(), 1), 500);
        int safeOffset = Math.max(query.getOffset(), 0);
        Instant tFrom = parseInstant(query.getFrom()), tTo = parseInstant(query.getTo());
        List<MemoryEvent> events = repo.queryEvents(query.getAgentId(), query.getSessionId(), query.getEventId(),
                query.getOp(), query.getLayer(), tFrom, tTo, safeLimit, safeOffset);
        long total = repo.countEvents(query.getAgentId(), query.getSessionId(), query.getEventId(),
                query.getOp(), query.getLayer(), tFrom, tTo);
        return Map.of(
                "agentId", query.getAgentId(),
                "total", total,
                "limit", safeLimit,
                "offset", safeOffset,
                "events", events
        );
    }

    /** 事件流筛选统计：按当前 agent / session / eventId / op / layer 范围返回事件总数、活跃会话数、Token 消耗合计。 */
    @GetMapping("/agents/{agentId}/events-stats")
    public Map<String, Object> eventStats(EventQueryDTO query) {
        Instant tFrom = parseInstant(query.getFrom()), tTo = parseInstant(query.getTo());
        Map<String, Object> stats = repo.eventStats(query.getAgentId(), query.getSessionId(), query.getEventId(),
                query.getOp(), query.getLayer(), tFrom, tTo);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        result.putAll(stats);
        return result;
    }

    /** 会话时间线（记忆时间线）：按 layer 分泳道。 */
    @GetMapping("/sessions/{sessionId}/timeline")
    public Map<String, Object> timeline(@PathVariable String sessionId) {
        Map<String, List<MemoryEvent>> lanes = repo.queryTimeline(sessionId);
        return Map.of(
                "sessionId", sessionId,
                "lanes", lanes
        );
    }

    /** Token 统计（Token 分析）：分层 + 按操作 + 趋势 + 五区占比。 */
    @GetMapping("/agents/{agentId}/token-stats")
    public Map<String, Object> tokenStats(
            TokenStatsQueryDTO query) {
        Instant f = parseInstant(query.getFrom());
        Instant t = parseInstant(query.getTo());
        String pgUnit = windowToPgUnit(query.getWindow());
        Map<String, Long> byLayer = repo.tokenByLayer(query.getAgentId(), f, t);
        Map<String, Long> byOp = repo.tokenByOp(query.getAgentId(), f, t);
        List<Map<String, Object>> trend = repo.tokenTrend(query.getAgentId(), f, t, pgUnit);
        Map<String, Object> zoneBudget = repo.zoneBudget(query.getAgentId(), f, t);
        double memPct = repo.memoryZonePct(query.getAgentId(), f, t);
        long total = byLayer.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> result = new HashMap<>();
        result.put("agentId", query.getAgentId());
        result.put("totalTokens", total);
        result.put("byLayer", byLayer);
        result.put("byOp", byOp);
        result.put("trend", trend);
        result.put("memoryZonePct", memPct);
        result.put("zoneBudget", zoneBudget);
        return result;
    }

    /** 会话列表（最近活动）。 */
    @GetMapping("/agents/{agentId}/sessions")
    public Map<String, Object> sessions(SessionsQueryDTO query) {
        return Map.of(
                "agentId", query.getAgentId(),
                "sessions", repo.querySessions(query.getAgentId(), Math.min(query.getLimit(), 200))
        );
    }

    /** Turn Token 热力图：按 Agent + Session 筛选，返回 行=Turn、列=时间桶 的 token 数据。 */
    @GetMapping("/agents/{agentId}/turn-heatmap")
    public Map<String, Object> turnHeatmap(
            @PathVariable String agentId,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "buckets", defaultValue = "12") int buckets) {
        int b = Math.max(2, Math.min(buckets, 60));
        return repo.turnHeatmap(agentId, sessionId == null ? "" : sessionId, b);
    }

    /** 单个 Turn 的相关事件（含主事件 + action 子事件）。柱子点击后下方列表用。 */
    @GetMapping("/agents/{agentId}/turn-message-events")
    public Map<String, Object> turnMessageEvents(
            @PathVariable String agentId,
            @RequestParam(name = "turnMessageId") String turnMessageId) {
        List<Map<String, Object>> events = repo.getTurnEvents(agentId, turnMessageId, "");
        return Map.of(
                "agentId", agentId,
                "turnMessageId", turnMessageId,
                "total", events.size(),
                "events", events
        );
    }

    /** 所有 Agent 列表（用于 Agent 选择下拉框）。 */
    @GetMapping("/agents")
    public Map<String, Object> agents(@RequestParam(defaultValue = "50") int limit) {
        return Map.of(
                "agents", repo.queryAgents(Math.min(limit, 200))
        );
    }

    /** 单事件详情（含完整 metadata）。前端抽屉显示用。 */
    @GetMapping("/events/{eventId}")
    public ResponseEntity<Map<String, Object>> eventDetail(@PathVariable String eventId) {
        Map<String, Object> event = repo.getEventById(eventId);
        if (event == null) {
            return ResponseEntity.status(404).body(Map.of("error", "event not found"));
        }
        // 同时查同 turn_message_id 的相关事件（actions 子事件 + 主事件）
        String agentId = (String) event.get("agentId");
        Map<String, String> meta = (Map<String, String>) event.getOrDefault("metadata", java.util.Collections.emptyMap());
        String turnMessageId = meta.get("turn_message_id");
        List<Map<String, Object>> turnEvents = (turnMessageId == null)
                ? java.util.Collections.emptyList()
                : repo.getTurnEvents(agentId, turnMessageId, eventId);
        Map<String, Object> result = new java.util.LinkedHashMap<>(event);
        result.put("turnEvents", turnEvents);
        return ResponseEntity.ok(result);
    }

    /** 事件详情页 turn 列表：支持按 Agent / Session / EventId / 关键字模糊查询。
     *  GET /api/v1/agents/{agentId}/turns?sessionId=&eventId=&q= */
    @SuppressWarnings("unchecked")
    @GetMapping("/agents/{agentId}/turns")
    public Map<String, Object> listTurns(
            @PathVariable String agentId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String eventId,
            @RequestParam(required = false) String q) {
        boolean hasEventId = eventId != null && !eventId.isBlank();
        if (hasEventId) {
            Map<String, Object> ev = repo.getEventById(eventId);
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("agentId", agentId);
            if (ev == null) {
                result.put("turns", List.of());
                result.put("totalEvents", 0L);
                result.put("totalTokens", 0L);
                return result;
            }
            Map<String, String> meta = (Map<String, String>) ev.getOrDefault("metadata", java.util.Collections.emptyMap());
            String turnMessageId = meta.get("turn_message_id");
            List<Map<String, Object>> acts = (turnMessageId == null)
                    ? java.util.Collections.emptyList()
                    : repo.getTurnEvents(agentId, turnMessageId, eventId);
            ev.put("actions", acts);
            ev.put("actionCount", acts.size());
            // 补齐前端 turn 渲染所需字段
            ev.put("turnMessageId", turnMessageId);
            ev.put("turnUser", meta.get("turn_user"));
            ev.put("turnOutcomePreview", meta.get("turn_outcome"));
            result.put("sessionId", ev.get("sessionId"));
            result.put("turns", List.of(ev));
            result.put("totalEvents", 1L);
            result.put("totalTokens", ((Number) ev.getOrDefault("tokenCount", 0)).longValue());
            return result;
        }
        List<Map<String, Object>> turns = repo.listTurns(agentId, sessionId, q);
        Map<String, Object> stats = repo.turnStats(agentId, sessionId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", agentId);
        result.put("sessionId", sessionId == null ? "" : sessionId);
        result.put("turns", turns);
        result.put("totalEvents", stats.get("total"));
        result.put("totalTokens", stats.get("totalTokens"));
        return result;
    }

    /** Token 多维度分析：聚合 8 个维度的数据一次返回。 */
    @GetMapping("/agents/{agentId}/token-analytics")
    public Map<String, Object> tokenAnalytics(TokenAnalyticsQueryDTO query) {
        Instant[] range = parseWindow(query.getWindow());
        Instant from = range[0], to = range[1];
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        result.put("window", query.getWindow());
        result.put("bySessionTop", repo.queryTopSessions(query.getAgentId(), from, to, 5));
        result.put("byHour", repo.queryByHour(query.getAgentId(), from, to));
        result.put("opLayerMatrix", repo.queryOpLayerMatrix(query.getAgentId(), from, to));
        result.put("latencyStats", repo.queryLatencyStats(query.getAgentId(), from, to, 5));
        result.put("topKeys", repo.queryTopKeys(query.getAgentId(), from, to, 10));
        result.put("ratios", repo.queryRatios(query.getAgentId(), from, to));
        result.put("histogram", repo.queryHistogram(query.getAgentId(), from, to));
        result.put("dailyTrend", repo.queryDailyTrend(query.getAgentId(), from, to));
        // KP 8.4 系列新维度：会话分布异常 / 燃烧速率 / memory_key 异常
        result.put("sessionDist", repo.querySessionTokenDistribution(query.getAgentId(), from, to));
        // burnRate 默认看最近 1h
        Instant now = Instant.now();
        result.put("burnRate", repo.queryBurnRate(query.getAgentId(), now.minusSeconds(3600), now));
        result.put("keyAbnormal", repo.queryKeyTokenAbnormal(query.getAgentId(), query.getSessionId(), from, to, 10));
        return result;
    }

    /** Token 消耗热力图：时间桶 × 记忆层。bucket 自适应 window 大小。 */
    @GetMapping("/agents/{agentId}/token-heatmap")
    public Map<String, Object> tokenHeatmap(TokenHeatmapQueryDTO query) {
        Instant[] range = parseWindow(query.getWindow());
        Instant from = range[0], to = range[1];
        // 自适应桶大小：<=1d→30m, <=3d→1h, <=14d→6h, >14d→1d
        long bucketSec;
        long windowSec = (from == null ? 86400 : (to.toEpochMilli() - from.toEpochMilli()) / 1000);
        if (windowSec <= 86400) bucketSec = 1800;          // 30m
        else if (windowSec <= 3 * 86400) bucketSec = 3600; // 1h
        else if (windowSec <= 14 * 86400) bucketSec = 6 * 3600; // 6h
        else bucketSec = 86400;                            // 1d

        List<Map<String, Object>> rows = repo.queryLayerTimeBuckets(query.getAgentId(), from, to, bucketSec);

        // 收集所有桶 + 所有层，构造矩阵
        java.util.TreeSet<Long> bucketSet = new java.util.TreeSet<>();
        java.util.TreeSet<String> layerSet = new java.util.TreeSet<>();
        java.util.Map<String, java.util.Map<Long, long[]>> matrix = new java.util.TreeMap<>();
        long maxTokens = 1;
        for (Map<String, Object> r : rows) {
            String layer = (String) r.get("layer");
            long bucket = ((Number) r.get("bucketEpoch")).longValue();
            long tokens = ((Number) r.get("tokens")).longValue();
            long events = ((Number) r.get("events")).longValue();
            bucketSet.add(bucket);
            layerSet.add(layer);
            matrix.computeIfAbsent(layer, k -> new java.util.HashMap<>())
                 .put(bucket, new long[]{tokens, events});
            if (tokens > maxTokens) maxTokens = tokens;
        }
        // bucket 标签：转 ISO 时间（UTC）
        java.util.List<String> bucketLabels = new java.util.ArrayList<>();
        for (long b : bucketSet) {
            bucketLabels.add(java.time.Instant.ofEpochSecond(b).toString());
        }
        // 矩阵：layer → [tokens per bucket]
        java.util.Map<String, java.util.List<long[]>> matrixOut = new java.util.LinkedHashMap<>();
        for (String layer : layerSet) {
            java.util.List<long[]> row = new java.util.ArrayList<>();
            java.util.Map<Long, long[]> layerData = matrix.getOrDefault(layer, java.util.Collections.emptyMap());
            for (long b : bucketSet) {
                long[] cell = layerData.get(b);
                row.add(cell == null ? new long[]{0, 0} : cell);
            }
            matrixOut.put(layer, row);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        result.put("window", query.getWindow());
        result.put("bucketSec", bucketSec);
        result.put("maxTokens", maxTokens);
        result.put("buckets", bucketLabels);
        result.put("layers", new java.util.ArrayList<>(layerSet));
        result.put("matrix", matrixOut);
        return result;
    }

    // —— 辅助 ——

    /** Agent 分析：全局对比 + Token 趋势。 */
    @GetMapping("/agent-analysis")
    public Map<String, Object> agentAnalysis(
            @RequestParam(required = false) String agents,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> comparison = repo.queryAgentComparison();
        List<String> agentIds;
        if (agents != null && !agents.isBlank()) {
            agentIds = java.util.Arrays.asList(agents.split(","));
        } else {
            agentIds = comparison.stream()
                    .map(m -> (String) m.get("agentId"))
                    .limit(5)
                    .toList();
        }
        List<Map<String, Object>> trend = repo.queryAgentTokenTrend(agentIds, days);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("comparison", comparison);
        result.put("trend", trend);
        result.put("trendAgents", agentIds);
        result.put("days", days);
        return result;
    }

    /** Agent 对比 · Session 维度明细：单个 Agent 内按 Session 分组（表格行内展开用）。 */
    @GetMapping("/agents/{agentId}/session-comparison")
    public Map<String, Object> agentSessionComparison(@PathVariable String agentId) {
        return Map.of(
                "agentId", agentId,
                "sessions", repo.queryAgentSessionComparison(agentId)
        );
    }

    /** Agent 级流程健康汇总：循环率(S3 汇总)/无效操作率(S7 失败汇总)/健康分——流程分析按节点算的指标汇总回 Agent 维度。
     *  GET /api/v1/agents/flow-summary?days=30 */
    @GetMapping("/agents/flow-summary")
    public Map<String, Object> agentsFlowSummary(@RequestParam(defaultValue = "30") int days) {
        return flowService.flowSummary(days);
    }

    /** 问题分析：按阈值聚合 8 类可能问题（后端阈值聚合，后续可叠 Agent 精读）。
     *  GET /api/v1/analytics/problems?days=7 */
    @GetMapping("/analytics/problems")
    public Map<String, Object> analyticsProblems(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(name = "agent", required = false) String agent) {
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

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("days", days);
        result.put("agent", agentCond);
        result.put("windowFrom", from == null ? null : from.toString());
        result.put("windowTo", to.toString());
        Map<String, Object> thresholds = new java.util.LinkedHashMap<>();
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
        result.put("problems", problems);
        return result;
    }

    /** 组装单个问题结构：key / title / threshold / hitCount / hits。 */
    private static Map<String, Object> problem(String key, String title, String threshold,
                                               List<Map<String, Object>> hits) {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("key", key);
        p.put("title", title);
        p.put("threshold", threshold);
        p.put("hitCount", hits.size());
        p.put("hits", hits);
        return p;
    }

    /** Turn 命中行：保留公共字段 + 当前问题关心的主指标值。 */
    private static Map<String, Object> turnHit(Map<String, Object> t, String valueKey, long value) {
        Map<String, Object> h = new java.util.LinkedHashMap<>();
        for (String k : new String[]{"agentId", "sessionId", "turnId", "tokens", "events", "toolEvents", "totalMs", "lastTs"}) {
            h.put(k, t.get(k));
        }
        h.put("metric", valueKey);
        h.put("metricValue", value);
        return h;
    }

    /** Skill 分析：单个 Agent 的 Skill 使用情况。 */
    @GetMapping("/agents/{agentId}/skill-analysis")
    public Map<String, Object> skillAnalysis(SkillAnalysisQueryDTO query) {
        Instant now = Instant.now();
        Instant from = query.getDays() > 0 ? now.minusSeconds(query.getDays() * 86400L) : null;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        result.put("days", query.getDays());
        result.put("topKeys", repo.querySkillTopKeys(query.getAgentId(), 10));
        result.put("opDistribution", repo.querySkillOpDistribution(query.getAgentId()));
        result.put("dailyTrend", repo.querySkillDailyTrend(query.getAgentId(), query.getDays()));
        result.put("crossAgent", repo.querySkillCrossAgent());
        // 六指标聚合：调用次数 / Token 消耗 / 引用 Agent 数 / 报错数 / 平均耗时
        result.put("metrics", repo.querySkillMetrics(from, now, query.getAgentId()));
        return result;
    }

    /** Skill 全局分析：跨 Agent 使用 + 全局 Top keys + 指标聚合（不依赖 agentId）。 */
    @GetMapping("/skill-analysis")
    public Map<String, Object> skillGlobalAnalysis(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int days) {
        Instant now = Instant.now();
        Instant from = days > 0 ? now.minusSeconds(days * 86400L) : null;
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("days", days);
        result.put("crossAgent", repo.querySkillCrossAgent());
        // 全局 Top skill keys（跨所有 Agent）
        result.put("globalTopKeys", repo.querySkillGlobalTopKeys(limit));
        result.put("metrics", repo.querySkillMetrics(from, now, null));
        return result;
    }

    // ==================== 流程分析（Flow Analysis）====================

    /** 流程模式列表：trace 变体归并，按频次排序。 */
    @GetMapping("/agents/{agentId}/flows")
    public Map<String, Object> flows(@PathVariable String agentId,
                                     @RequestParam(defaultValue = "30") int days,
                                     @RequestParam(required = false) String sessionId,
                                     @RequestParam(defaultValue = "10") int limit) {
        Map<String, Object> r = flowService.patterns(agentId, days, sessionId, limit);
        r.put("days", days);
        return r;
    }

    /** 转移图：节点（含健康信号 S1/S2/S3/S7）+ 边（转移频次）。 */
    @GetMapping("/agents/{agentId}/flow-graph")
    public Map<String, Object> flowGraph(@PathVariable String agentId,
                                         @RequestParam(defaultValue = "30") int days,
                                         @RequestParam(required = false) String sessionId) {
        Map<String, Object> r = flowService.graph(agentId, days, sessionId);
        r.put("days", days);
        return r;
    }

    /** 问题节点卡片：节点 + 信号 + 证据（可回放 trace）+ 建议。 */
    @GetMapping("/agents/{agentId}/flow-issues")
    public Map<String, Object> flowIssues(@PathVariable String agentId,
                                          @RequestParam(defaultValue = "30") int days,
                                          @RequestParam(required = false) String sessionId) {
        Map<String, Object> r = flowService.issues(agentId, days, sessionId);
        r.put("days", days);
        return r;
    }

    /** 问题节点详情：定位到具体 turn（哪几个 turn、什么问题、操作上下文），供滑块展示。 */
    @GetMapping("/agents/{agentId}/flow-issue-detail")
    public Map<String, Object> flowIssueDetail(@PathVariable String agentId,
                                               @RequestParam(defaultValue = "30") int days,
                                               @RequestParam(required = false) String sessionId,
                                               @RequestParam String node,
                                               @RequestParam(required = false) Integer span) {
        Map<String, Object> r = flowService.issueDetail(agentId, days, sessionId, node, span);
        r.put("days", days);
        return r;
    }

    /** 内容风险监测：扫描会话内容是否透出 AK/密码/Token 等敏感信息。 */
    @Autowired
    private io.memobservatory.server.risk.RiskScanService riskService;

    @GetMapping("/agents/{agentId}/risk-scan")
    public Map<String, Object> riskScan(@PathVariable String agentId,
                                        @RequestParam(defaultValue = "30") int days) {
        return riskService.scan(agentId, days);
    }

    /**
     * 问题计数（供流程分析 / 内容风险监测页的 Agent 下拉展示每个 Agent 的问题量）。
     * flowIssues = 流程分析问题节点数；riskHits = 敏感信息命中数。
     */
    @GetMapping("/problem-counts")
    public Map<String, Object> problemCounts(@RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> counts = new ArrayList<>();
        for (Map<String, Object> a : repo.queryAgents(200)) {
            String id = String.valueOf(a.get("agentId"));
            Map<String, Object> row = new HashMap<>();
            row.put("agentId", id);
            try {
                row.put("flowIssues", flowService.issues(id, days, null).get("issues"));
            } catch (Exception e) {
                row.put("flowIssues", java.util.List.of());
            }
            try {
                Object total = riskService.scan(id, days).get("total");
                row.put("riskHits", total instanceof Number ? ((Number) total).intValue() : 0);
            } catch (Exception e) {
                row.put("riskHits", 0);
            }
            counts.add(row);
        }
        return Map.of("counts", counts);
    }

    /** Session（trace）切换列表：供工具栏下拉。 */
    @GetMapping("/agents/{agentId}/flow-sessions")
    public Map<String, Object> flowSessions(@PathVariable String agentId,
                                            @RequestParam(defaultValue = "30") int days) {
        return flowService.sessions(agentId, days);
    }

    // ==================== Trace API（input-contracts §6.4）====================

    /** 拉取单个 Trace 的完整详情：flat spans + 预构建树 + statistics。 */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        List<Map<String, Object>> spans = repo.queryTraceSpans(traceId);
        if (spans.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "trace not found",
                    "traceId", traceId));
        }
        // 树 + 统计
        List<Map<String, Object>> tree = buildTraceTree(spans);
        Map<String, Object> stats = buildTraceStatistics(spans, tree);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("traceId", traceId);
        // 取第一个 span 的 agentId / sessionId 作为 trace 归属
        result.put("agentId", spans.get(0).get("agentId"));
        result.put("sessionId", spans.get(0).get("sessionId"));
        result.put("spans", spans);
        result.put("tree", tree);
        result.put("statistics", stats);
        return ResponseEntity.ok(result);
    }

    /** 从事件反查 Trace：等价先查 event.trace_id 再返回完整 Trace（方便事件流详情跳转）。 */
    @GetMapping("/events/{eventId}/trace")
    public ResponseEntity<Map<String, Object>> getTraceByEvent(@PathVariable String eventId) {
        String traceId = repo.findTraceIdByEventId(eventId);
        if (traceId == null || traceId.isBlank()) {
            return ResponseEntity.status(404).body(Map.of("error", "event has no trace_id",
                    "eventId", eventId));
        }
        return getTrace(traceId);
    }

    /** Agent 维度 Trace 列表（按 last_ts DESC 分页）。支持 ?sessionId=xxx 过滤。 */
    @GetMapping("/agents/{agentId}/traces")
    public Map<String, Object> listAgentTraces(
            TraceListQueryDTO query) {
        int safeLimit = Math.min(Math.max(query.getLimit(), 1), 200);
        int safeOffset = Math.max(query.getOffset(), 0);
        List<Map<String, Object>> traces = repo.queryAgentTraces(query.getAgentId(), query.getSessionId(),
                safeLimit, safeOffset);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        if (query.getSessionId() != null) result.put("sessionId", query.getSessionId());
        result.put("limit", safeLimit);
        result.put("offset", safeOffset);
        result.put("traces", traces);
        return result;
    }

    // —— Trace 辅助：flat spans → children 树 ——

    /**
     * 根据 parentSpanId 在一次遍历中构建树。
     * 孤儿 span（parent 不在集合内或 parentSpanId == null 且 parent 不存在）提升为根。
     */
    private static List<Map<String, Object>> buildTraceTree(List<Map<String, Object>> spans) {
        // spanId → node（children 递归）
        java.util.Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> s : spans) {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("span", s);
            node.put("children", new java.util.ArrayList<Map<String, Object>>());
            byId.put((String) s.get("spanId"), node);
        }
        // 挂子
        java.util.List<Map<String, Object>> roots = new java.util.ArrayList<>();
        for (Map<String, Object> s : spans) {
            String pid = (String) s.get("parentSpanId");
            Map<String, Object> self = byId.get(s.get("spanId"));
            if (pid != null && byId.containsKey(pid)) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> siblings =
                        (java.util.List<Map<String, Object>>) byId.get(pid).get("children");
                siblings.add(self);
            } else {
                // 根节点：Turn Root Span 或 孤儿
                roots.add(self);
            }
        }
        return roots;
    }

    /** Trace statistics：spans 总数、turns、critical path、失败、慢 span、记忆有效性估算。 */
    private static Map<String, Object> buildTraceStatistics(
            List<Map<String, Object>> spans, List<Map<String, Object>> tree) {
        long total = spans.size();
        long turns = spans.stream()
                .map(s -> (Map<String, String>) s.getOrDefault("metadata", java.util.Collections.emptyMap()))
                .map(m -> m.get("turn_message_id"))
                .filter(java.util.Objects::nonNull)
                .distinct().count();
        if (turns == 0) {
            // 没 turn_message_id 时按 session_id 兜底为 1 个 turn
            turns = 1;
        }
        // 关键路径：树中从根到叶子的最长 duration 路径
        double criticalMs = criticalPath(tree);

        long failed = 0;
        long orphan = 0;
        long reads = 0, writes = 0;
        long itemsReturned = 0;
        java.util.List<Map<String, Object>> slowList = new java.util.ArrayList<>();

        for (Map<String, Object> s : spans) {
            Map<String, String> meta = (Map<String, String>) s.getOrDefault("metadata",
                    java.util.Collections.emptyMap());
            String status = meta.get("status");
            String error  = meta.get("error");
            String orphanFlag = meta.get("trace_orphan");
            if ("failed".equalsIgnoreCase(status) || error != null) failed++;
            if ("true".equals(orphanFlag) || "1".equals(orphanFlag)) orphan++;

            String evt = (String) s.get("eventType");
            if (evt != null) {
                if ("READ".equalsIgnoreCase(evt)) reads++;
                if ("WRITE".equalsIgnoreCase(evt) || "UPDATE".equalsIgnoreCase(evt)) writes++;
            }
            // items_returned（memory.read 常见 metadata 字段）
            String ir = meta.get("items_returned");
            if (ir != null) {
                try { itemsReturned += Long.parseLong(ir); }
                catch (NumberFormatException ignored) {}
            }
            Double dur = (Double) s.get("durationMs");
            if (dur != null && dur > 0) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", s.get("name"));
                row.put("durationMs", dur);
                row.put("status", ("failed".equalsIgnoreCase(status) || error != null) ? "failed" : "ok");
                if (error != null) row.put("error", error);
                slowList.add(row);
            }
        }
        // Top 5 慢 spans（按 duration desc）
        slowList.sort((a, b) -> Double.compare(
                ((Number) b.get("durationMs")).doubleValue(),
                ((Number) a.get("durationMs")).doubleValue()));
        List<Map<String, Object>> topSlow = slowList.subList(0, Math.min(5, slowList.size()));

        // 记忆有效性粗略估算：（读返回条数 - 失败）/ max(读返回条数,1)
        double effectiveness = 0.0;
        String hint = "";
        if (reads + itemsReturned > 0) {
            long inferredUsed = Math.max(0, itemsReturned - failed * 2);
            long base = Math.max(1, itemsReturned);
            effectiveness = Math.min(1.0, (double) inferredUsed / base);
            hint = String.format("检索 %d 条，推断使用 %d 条（%.0f%% 利用率）",
                    itemsReturned, inferredUsed, effectiveness * 100);
        } else {
            hint = "此 Trace 内无 memory.read span";
        }
        Map<String, Object> eff = new java.util.LinkedHashMap<>();
        eff.put("reads", reads);
        eff.put("writes", writes);
        eff.put("itemsReturned", itemsReturned);
        eff.put("retrievalEffectiveness", effectiveness);
        eff.put("hint", hint);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalSpans", total);
        result.put("turns", turns);
        result.put("criticalPathMs", criticalMs);
        result.put("failedSpans", failed);
        result.put("orphanSpans", orphan);
        result.put("topSlowSpans", topSlow);
        result.put("memoryEffectiveness", eff);
        return result;
    }

    /** 从根出发递归求关键路径（最长 duration 累加和）。 */
    @SuppressWarnings("unchecked")
    private static double criticalPath(List<Map<String, Object>> nodes) {
        double max = 0;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> span = (Map<String, Object>) node.get("span");
            double d = span.get("durationMs") == null ? 0 : ((Number) span.get("durationMs")).doubleValue();
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            double sub = children == null ? 0 : criticalPath(children);
            if (d + sub > max) max = d + sub;
        }
        return max;
    }

    /** 把 '1d'/'7d'/'30d' 等 window 字符串解析为 [from, to] Instant。 */
    private static Instant[] parseWindow(String window) {
        if (window == null || window.isBlank()) {
            return new Instant[]{null, Instant.now()};
        }
        String s = window.toLowerCase().trim();
        try {
            if (s.endsWith("d")) {
                long days = Long.parseLong(s.substring(0, s.length() - 1));
                return new Instant[]{Instant.now().minusSeconds(days * 86400), Instant.now()};
            } else if (s.endsWith("h")) {
                long hours = Long.parseLong(s.substring(0, s.length() - 1));
                return new Instant[]{Instant.now().minusSeconds(hours * 3600), Instant.now()};
            } else if (s.equals("all") || s.equals("*")) {
                return new Instant[]{null, Instant.now()};
            }
        } catch (NumberFormatException ignored) {}
        return new Instant[]{Instant.now().minusSeconds(86400), Instant.now()};
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 数据导入（读侧）====================

    private static final ObjectMapper IMPORT_MAPPER = new ObjectMapper();

    /** 导入模板列顺序（与写侧解析行保持一致）。 */
    private static final List<String> IMPORT_HEADERS = List.of(
            "event_id", "agent_id", "session_id", "operation", "layer",
            "memory_key", "memory_summary", "token_count", "latency_ms",
            "timestamp", "trace_id", "parent_span_id", "metadata");

    /** 下载固定模板：?format=xlsx|json。 */
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> importTemplate(
            @RequestParam(name = "format", defaultValue = "xlsx") String format) {
        try {
            if ("json".equalsIgnoreCase(format)) return importTemplateJson();
            return importTemplateXlsx();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(("template generation failed: " + e.getMessage()).getBytes());
        }
    }

    private ResponseEntity<byte[]> importTemplateXlsx() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("memory_events");
            Row header = sheet.createRow(0);
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) header.createCell(i).setCellValue(IMPORT_HEADERS.get(i));
            Row example = sheet.createRow(1);
            Map<String, Object> ex = importExampleRow();
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) {
                Object v = ex.get(IMPORT_HEADERS.get(i));
                example.createCell(i).setCellValue(v == null ? "" : String.valueOf(v));
            }
            for (int i = 0; i < IMPORT_HEADERS.size(); i++) sheet.setColumnWidth(i, 6800);
            buildImportGuideSheet(wb);
            wb.write(out);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"memory-import-template.xlsx\"")
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(out.toByteArray());
        }
    }

    /** 生成 Excel 第二个 Sheet「参数说明·Harness 场景」，解释枚举值、字段与 harness 特有 metadata。 */
    private static void buildImportGuideSheet(XSSFWorkbook wb) {
        Sheet g = wb.createSheet("参数说明·Harness 场景");
        String[][] guide = {
                {"字段", "必填", "说明"},
                {"event_id", "否", "事件主键，留空自动生成16位id"},
                {"agent_id", "是", "Agent 标识，下拉框里该值会作为一条 Agent 展示"},
                {"session_id", "是", "会话标识；相同 session_id 的事件归为同一会话/时间线"},
                {"operation", "是", "枚举：READ=读取写入记忆 / WRITE=新增记忆 / UPDATE=更新记忆 / EXPIRE=过期遗忘（写侧 MemoryOp 归一化）"},
                {"layer", "是", "枚举：prompt=提示词相关 / session=会话汇总(turn) / skill=技能调用 / provider=外部工具/API"},
                {"memory_key", "否", "记忆键，形如 config/price、skill:do_pricing；同名 key 可做更新/统计"},
                {"memory_summary", "否", "摘要预览，事件详情里展示的一句话说明"},
                {"token_count", "否", "涉及 token 数，整数，用于 Token 分析与预算统计"},
                {"latency_ms", "否", "耗时毫秒（数字），用于延迟分位与慢操作分析"},
                {"timestamp", "否", "严格格式：yyyy-MM-dd HH:mm:ss（如 2026-08-22 11:25:50），按 Asia/Shanghai 本地时间解析；留空用当前时间"},
                {"trace_id", "否", "链路 Trace ID(32hex)；与 parent_span_id 一起构成调用链路，供 Skill/调用下钻"},
                {"parent_span_id", "否", "父 Span ID(16hex)；组合成父子调用树"},
                {"metadata", "否", "可选 JSON 附加字段，见下方 Harness 特有键说明"},
                {"", "", "---------------- Harness 特有场景：turn ---------------"},
                {"metadata", "否", "turn_message_id：一批事件属于同一轮用户对话的标识（用户问题+若干操作步骤）"},
                {"metadata", "否", "turn_user：本条 turn 的用户问题原文"},
                {"metadata", "否", "turn_actions：本条 turn 的操作步骤（可数组/文本），前端按同一 turn_message_id 展示完整步骤"},
                {"metadata", "否", "turn_outcome：turn 的总结产出"},
                {"", "", "---------------- Harness 特有场景：action 步骤子事件 ---------------"},
                {"metadata", "否", "action_idx：0,1,2... 表示该 turn 内的第几步。主事件(turn 汇总)不填此键"},
                {"metadata", "否", "action_full：该步骤的完整内容（如执行的命令/调用），便于下钻查看"},
                {"", "", "---------------- Harness 特有场景：skill 技能 ---------------"},
                {"metadata", "否", "skill_name：技能名，配合 layer=skill 用于技能分析回放"},
                {"metadata", "否", "skill_status：技能结果，如 success / failed / running"},
                {"metadata", "否", "span_id：当前 span 标识（16hex）；trace_id+span_id 唯一标记一次调用"},
                {"", "", "---------------- 说明 --------------------"},
                {"source", "否", "系统自动写入 source=import；手动上报(mcp/脚本)会写 mcp/realtime，仅用于溯源"},
        };
        for (int r = 0; r < guide.length; r++) {
            Row row = g.createRow(r);
            for (int c = 0; c < guide[r].length; c++) row.createCell(c).setCellValue(guide[r][c]);
        }
        g.setColumnWidth(0, 8400);
        g.setColumnWidth(1, 2600);
        g.setColumnWidth(2, 26000);
    }

    private ResponseEntity<byte[]> importTemplateJson() throws Exception {
        List<Map<String, Object>> rows = List.of(importExampleRow());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"memory-import-template.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(IMPORT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows));
    }

    private Map<String, Object> importExampleRow() {
        Map<String, Object> ex = new java.util.LinkedHashMap<>();
        ex.put("event_id", "");
        // 示例 timestamp 必须用导入支持的严格格式：yyyy-MM-dd HH:mm:ss（按 Asia/Shanghai 本地时间）
        String nowStr = LocalDateTime.ofInstant(
                Instant.now(), ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ex.put("agent_id", "my-agent");
        ex.put("session_id", "session-001");
        ex.put("operation", "WRITE");
        ex.put("layer", "skill");
        ex.put("memory_key", "skill:do_pricing");
        ex.put("memory_summary", "示例：技能执行完成，定价脚本产出结果");
        ex.put("token_count", 128);
        ex.put("latency_ms", 42.0);
        ex.put("timestamp", nowStr);
        ex.put("trace_id", "");
        ex.put("parent_span_id", "");
        // metadata 里示范 harness 特有字段：turn（一组用户问题→操作步骤）、skill（技能调用）
        ex.put("metadata", "{\"turn_message_id\":\"t-001\",\"turn_user\":\"给主套餐定价\","
                + "\"turn_actions\":\"[读取价格表, 计算折扣, 写回]\",\"turn_outcome\":\"已完成\","
                + "\"skill_name\":\"pricing\",\"skill_status\":\"success\",\"source\":\"import\"}");
        return ex;
    }

    /** 下载技能/MCP 接入包：skill.md + report_mcp_server.py + 按平台的 MCP 配置 + README（zip）。
     *  ?platform=claude|cursor|codex|windsurf|traecode|generic，缺省 generic（含全部平台说明）。 */
    @GetMapping("/import/skill-package")
    public ResponseEntity<byte[]> importSkillPackage(
            @RequestParam(name = "platform", required = false, defaultValue = "generic") String platform) {
        String p = (platform == null || platform.isBlank()) ? "generic" : platform.toLowerCase();
        String[] cfg = PLATFORM_MCP.get(p);
        if (cfg == null) cfg = PLATFORM_MCP.get("generic");
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            addZipEntry(zip, "skill.md", SKILL_MD.getBytes());
            addZipEntry(zip, "report_mcp_server.py", REPORT_MCP_SERVER.getBytes());
            addZipEntry(zip, cfg[0], cfg[1].getBytes());
            addZipEntry(zip, "README.md", buildPackageReadme(p).getBytes());
            zip.finish();
            String fname = "generic".equals(p)
                    ? "mo-skill-package.zip"
                    : "mo-skill-package-" + p + ".zip";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(out.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                    .body(("package generation failed: " + e.getMessage()).getBytes());
        }
    }

    /** 平台 → [配置文件名, MCP 配置内容]。common 为各平台通用配置体。 */
    private static final String MCP_CFG_COMMON = """
            {
              "mcpServers": {
                "mo-report": {
                  "command": "python3",
                  "args": ["/绝对/路径/report_mcp_server.py"],
                  "env": {
                    "MO_SERVER": "http://127.0.0.1:8080",
                    "MO_API_KEY": "服务端启用鉴权时填 MO_API_KEY，否则留空或删掉此行"
                  }
                }
              }
            }
            """;

    private static final Map<String, String[]> PLATFORM_MCP = Map.of(
            "claude", new String[]{"claude_desktop_config.json", MCP_CFG_COMMON},
            "cursor", new String[]{".cursor_mcp.json", MCP_CFG_COMMON},
            "codex", new String[]{"codex_mcp.json", MCP_CFG_COMMON},
            "windsurf", new String[]{"windsurf_config.json", MCP_CFG_COMMON},
            "traecode", new String[]{"traecode_mcp.json", MCP_CFG_COMMON},
            "generic", new String[]{"mcp.json", MCP_CFG_COMMON});

    private static final Map<String, String> PLATFORM_INSTRUCTIONS = Map.of(
            "claude", "打开 Claude Desktop：Settings → Developer → Edit Config，将 claude_desktop_config.json 的内容合并进配置后重启。",
            "cursor", "在项目根目录创建 .cursor/mcp.json 并写入本配置，或把生成的 .cursor_mcp.json 内容粘贴进去；重启 Cursor 后到 MCP 面板确认 mo-report 已连接。",
            "codex", "在项目根目录 Codex MCP 配置中登记 codex_mcp.json；或运行 codex mcp add mo-report python3 /绝对/路径/report_mcp_server.py。",
            "windsurf", "打开 Windsurf：Settings → MCP Servers，导入 windsurf_config.json 中的 mo-report 服务。",
            "traecode", "TraeCode 不支持注册自定义 stdio MCP；改用项目内脚本/curl 走 POST /api/v1/events 上报（见 README 与 examples/trae_report_event.py）。",
            "generic", "按目标 Agent 的 MCP 配置方式导入 mcp.json 中的 mo-report 服务（stdio：python3 report_mcp_server.py，环境变量 MO_SERVER=http://127.0.0.1:8080）。");

    /** 生成该平台的完整安装步骤 README。 */
    private static String buildPackageReadme(String platform) {
        String name = PLATFORM_MCP.containsKey(platform) ? platform : "generic";
        String step = PLATFORM_INSTRUCTIONS.getOrDefault(name, PLATFORM_INSTRUCTIONS.get("generic"));
        if ("traecode".equals(name)) {
            return "# mo-report 安装步骤（TraeCode）\n\n"
                    + "TraeCode 不允许注册自定义 stdio MCP，因此本包不用于 TraeCode。\n"
                    + "改用项目内现成脚本直报（仓库 examples/trae_report_event.py），零依赖：\n\n"
                    + "## 一键安装并启动 MemoryObservatory\n"
                    + "    ./install.sh\n\n"
                    + "## 上报一条记忆事件\n"
                    + "    python3 examples/trae_report_event.py --operation WRITE --layer prompt \\\n"
                    + "        --memory-key 记忆/键 --summary 做了什么 --token-count 60 \\\n"
                    + "        --session-id trae-session-20260822\n\n"
                    + "参数：operation=READ/WRITE/UPDATE/EXPIRE；layer=prompt/session/skill/provider。\n\n"
                    + "## 校验\n"
                    + "脚本返回 status=200 即成功，约 5 秒后刷新 Dashboard 事件详情页（Agent=trae-code）可见。\n"
                    + "批量导入历史聊天：python3 examples/trae_importer.py --since 20260801\n";
        }
        String cfgFile = name.equals("generic") ? "mcp.json" : PLATFORM_MCP.get(name)[0];
        return ("# mo-report 记忆上报 MCP 接入包（完整安装步骤）\n\n"
                + "让 Agent 侧自动上报记忆事件到 MemoryObservatory Dashboard。目标平台：%s\n\n"
                + "## 压缩包包含\n"
                + "- report_mcp_server.py   stdio MCP Server，暴露 report_memory_event 工具（真正上报的进程）\n"
                + "- skill.md               技能描述，交给 Agent 让它学会何时上报\n"
                + "- %s                     %s 平台的 MCP 配置\n"
                + "- README.md              本文件\n\n"
                + "## 安装步骤\n"
                + "1. 解压：把本包解压到任意固定目录（路径不要包含中文/空格）。\n"
                + "2. 改路径：用编辑器打开 %s，把 args 里的 \"report_mcp_server.py\" 改成它的绝对路径，例如 \"C:/Users/you/mo/result_mcp_server.py\" 或 \"/Users/you/mo/report_mcp_server.py\"。\n"
                + "3. 指地址：确保脚本进程有环境变量 MO_SERVER=http://127.0.0.1:8080（指向 MemoryObservatory；远程部署则改成服务器地址）。\n"
                + "4. 注册到平台：%s\n"
                + "5. 喂技能：把 skill.md 内容复制为 Agent 的技能/系统提示词，它会在每次记忆操作（读/写/更新/过期）时自动调用 report_memory_event。\n"
                + "6. 校验：重启 Agent 后让它调用一次 report_memory_event，若返回 eventId 即接入成功，约 5 秒后刷新 Dashboard 即可看到。\n\n"
                + "## report_memory_event 参数\n"
                + "| 参数 | 必填 | 说明 |\n"
                + "|---|---|---|\n"
                + "| agentId | 是 | Agent 标识（Dashboard 里据此聚合展示） |\n"
                + "| operation | 是 | read / write / update / expire |\n"
                + "| layer | 是 | prompt / session / skill / provider |\n"
                + "| memoryKey | 否 | 记忆键 |\n"
                + "| memorySummary | 否 | 摘要 |\n"
                + "| tokenCount | 否 | Token 数 |\n"
                + "| latencyMs | 否 | 耗时 ms |\n"
                + "| sessionId | 否 | 会话 ID（缺省 default-session） |\n\n"
                + "## 常见问题（Q&A）\n"
                + "- 返回 agentId is required：step2 的 args 路径没配对，或调用时没传 agentId。\n"
                + "- 上报失败/连不上：检查 MO_SERVER 是否可达、Firewall 是否放行 8080。\n"
                + "- 不想用 MCP：直接 POST /api/v1/events 上报单条，或 POST /api/v1/import?format=json 批量导入（模板可在 Dashboard 下载）。\n"
                ).formatted(name.toUpperCase(), cfgFile, name.toUpperCase(), cfgFile, step);
    }

    private static void addZipEntry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    // ==================== 数据导入（读侧）接入包内容 ====================

    private static final String SKILL_MD = """
            # MemoryObservatory 记忆上报技能

            让本 Agent 能在执行任务时，把「记忆相关操作」实时上报到 MemoryObservatory Dashboard，
            从而获得记忆读写、Token 消耗、延迟等可观测指标。

            ## 何时触发
            只要本次对话/任务涉及以下任一行为，就调用 `report_memory_event` 工具上报一条事件：
              - 读取了项目文件、配置、文档（operation=READ，layer=prompt）
              - 写入/修改了代码或配置（operation=WRITE，layer=prompt）
              - 调用了工具、外部 API、跑测试/构建（operation=WRITE，layer=skill 或 provider）
              - 总结/整理了一轮对话（operation=UPDATE，layer=session）

            ## 上报参数
              - operation: read | write | update | expire
              - layer:     prompt | session | skill | provider
              - memory_key / memory_summary: 简述这步做了什么
              - token_count: 估算消耗（约 字符数/4）
              - latency_ms:  可选，耗时毫秒数

            ## 要求
              1. 每完成一次重要操作就上报，不要批量堆积到结束。
              2. 上报失败不要阻塞主流程，继续执行任务即可。
            """;

    private static final String MCP_CONFIG = """
            {
              "mcpServers": {
                "mo-report": {
                  "command": "python3",
                  "args": ["report_mcp_server.py"],
                  "env": {
                    "MO_SERVER": "http://127.0.0.1:8080",
                    "MO_API_KEY": "服务端启用鉴权时填 MO_API_KEY，否则留空或删掉此行"
                  }
                }
              }
            }
            """;

    private static final String REPORT_MCP_SERVER = """
            #!/usr/bin/env python3
            # -*- coding: utf-8 -*-
            \"\"\"
            mo-report MCP 服务：向 MemoryObservatory 上报记忆事件。
            作为 stdio MCP Server，暴露 report_memory_event 工具。
            配置见 mcp-config.json（command=python3, args=[report_mcp_server.py]）。
            启动前请设置环境变量 MO_SERVER（默认 http://127.0.0.1:8080）；
            服务端启用鉴权时还需设置 MO_API_KEY（作为 Authorization: Bearer 发送）。
            \"\"\"
            import json, os, sys
            try:
                import requests
            except ImportError:
                import urllib.request

            MO_SERVER = os.environ.get("MO_SERVER", "http://127.0.0.1:8080").rstrip("/")
            MO_API_KEY = os.environ.get("MO_API_KEY", "").strip()

            def _headers():
                h = {"Content-Type": "application/json"}
                if MO_API_KEY:
                    h["Authorization"] = "Bearer " + MO_API_KEY
                return h

            def http_post(url, payload):
                try:
                    r = requests.post(url, json=payload, headers=_headers(), timeout=5)
                except NameError:
                    data = json.dumps(payload).encode("utf-8")
                    req = urllib.request.Request(url, data=data, headers=_headers())
                    with urllib.request.urlopen(req, timeout=5) as resp:
                        return resp.status, resp.read().decode("utf-8")
                return r.status_code, r.text

            def _get(params, *names):
                low = {str(k).lower(): v for k, v in (params or {}).items()}
                for n in names:
                    if n in low:
                        return low[n]
                return None

            def report_memory_event(params):
                payload = {
                    "agentId": _get(params, "agentid", "agent_id", "agent"),
                    "sessionId": _get(params, "sessionid", "session_id", "session") or "default-session",
                    "operation": _get(params, "operation") or "write",
                    "layer": _get(params, "layer") or "provider",
                    "memoryKey": _get(params, "memorykey", "memory_key"),
                    "memorySummary": _get(params, "memorysummary", "memory_summary"),
                    "tokenCount": int(_get(params, "tokencount", "token_count", "token") or 0),
                    "latencyMs": float(_get(params, "latencyms", "latency_ms") or 0),
                }
                if not payload.get("agentId"):
                    return {"isError": True, "content": [{"type": "text", "text": "agentId is required"}]}
                status, body = http_post(f"{MO_SERVER}/api/v1/events", payload)
                if status >= 400:
                    return {"isError": True, "content": [{"type": "text", "text": body or f"HTTP {status}"}]}
                return {"content": [{"type": "text", "text": f"ok: {body}"}]}

            def handle(inp):
                req = json.loads(inp)
                rid = req.get("id")
                name = (req.get("params") or {}).get("name", "")
                if req.get("method") == "initialize":
                    out = {"jsonrpc": "2.0", "id": rid, "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {"tools": {}},
                        "serverInfo": {"name": "mo-report", "version": "0.1.0"},
                    }}
                elif req.get("method") == "tools/list":
                    out = {"jsonrpc": "2.0", "id": rid, "result": {"tools": [{
                        "name": "report_memory_event",
                        "description": "向 MemoryObservatory 上报一条记忆事件（读/写/更新/过期）。",
                        "inputSchema": {
                            "type": "object",
                            "properties": {
                                "agentId": {"type": "string", "description": "Agent 标识（必填）"},
                                "sessionId": {"type": "string"},
                                "operation": {"type": "string", "enum": ["read", "write", "update", "expire"]},
                                "layer": {"type": "string", "enum": ["prompt", "session", "skill", "provider"]},
                                "memoryKey": {"type": "string", "description": "记忆 key"},
                                "memorySummary": {"type": "string", "description": "做了什么"},
                                "tokenCount": {"type": "integer"},
                                "latencyMs": {"type": "number"},
                            },
                            "required": ["agentId", "operation", "layer"],
                        },
                    }]}}
                elif req.get("method") == "tools/call":
                    args = (req.get("params") or {}).get("arguments", {}) or {}
                    out = report_memory_event(args)
                    out = {"jsonrpc": "2.0", "id": rid, "result": out}
                else:
                    out = {"jsonrpc": "2.0", "id": rid, "result": {}}
                sys.stdout.write(json.dumps(out) + "\\n")
                sys.stdout.flush()

            if __name__ == "__main__":
                for line in sys.stdin:
                    line = line.strip()
                    if line:
                        try:
                            handle(line)
                        except Exception as e:
                            err = {"jsonrpc": "2.0", "error": {"code": -32603, "message": str(e)}}
                            sys.stdout.write(json.dumps(err) + "\\n")
                            sys.stdout.flush()
            """;

    private static final String IMPORT_SKILL_README = """
            # MemoryObservatory 技能/MCP 接入包

            ## 三个文件
            - skill.md                 : 技能描述，复制给目标 Agent 即可让它学会上报
            - report_mcp_server.py     : 一个 stdio MCP Server，暴露 `report_memory_event` 工具
            - mcp-config.json          : 目标 Agent 客户端的 MCP 配置片段

            ## 快速开始
            1. 下载并解压本包到任意目录。
            2. 在目标 Agent 客户端的 MCP 配置里，把 `mcp-config.json` 中 `mcpServers.mo-report`
               片段合入（或复制为 `mcp.json`），确保 `command` / `args` 指向本目录下的
               `report_mcp_server.py`。
            3. 设置环境变量 `MO_SERVER=http://127.0.0.1:8080`（指向 MemoryObservatory 服务）。
            4. 把 `skill.md` 内容作为技能提示词交给 Agent。
            5. 之后 Agent 每次记忆操作都会实时上报，Dashboard 刷新可见。

            ## 说明
            - 若 Agent 不支持 stdio MCP，也可直接调 POST /api/v1/events 上报单条事件，
              或用 POST /api/v1/import 批量导入模板文件。
            """;

    /** window: 10m / 1h / 1d → PG date_trunc 单位。 */
    private static String windowToPgUnit(String window) {
        if (window == null) return "hour";
        return switch (window) {
            case "10m" -> "minute";
            case "1d" -> "day";
            default -> "hour";
        };
    }
}
