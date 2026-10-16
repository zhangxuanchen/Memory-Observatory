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

    /** 阈值规则问题分析：判定口径与告警推送共用同一份实现。 */
    @Autowired
    private io.memobservatory.server.analytics.AnalyticsProblemService problemService;

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
     *  GET /api/v1/analytics/problems?days=7&amp;semantic=true
     *
     *  <p>semantic=true（默认）时对每类命中做语义确认，剔除「任务本身就需要这么多资源」
     *  这类假阳性（例如「跑测试的 Agent 用了 30s」不是问题，「客服 Agent 用了 30s」才是）。
     *
     *  <p><b>为什么默认开</b>：这是实测中唯一确实产生收益的落点。判据「任务本身复杂」
     *  vs「病态空转」是纯语义判断，规则/阈值做不到。实测 2/2 正确
     *  （复杂任务 pFalse=0.805 → 剔除；病态空转 pFalse=0.312 → 保留，阈值 0.65）。
     *  详见 LAYA-INTEGRATION.md 附·实测记录 D。 */
    @GetMapping("/analytics/problems")
    public Map<String, Object> analyticsProblems(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(defaultValue = "true") boolean semantic) {
        return problemService.problems(days, agent, semantic);
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
