package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.contract.EventQueryPort;
import io.memobservatory.contract.EventStreamPort;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemoryOp;
import io.memobservatory.server.model.MemorySnapshot;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EventRepository} 按域拆出的协作类（Ana 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class AnalyticsStore extends SqlRepositoryBase {

    AnalyticsStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    /** 16. 全局 Agent 对比：事件数、会话数、Token、读写比、更新率、遗忘率、Skill 占比。 */
    public List<Map<String, Object>> queryAgentComparison() {
        String sql = """
                SELECT agent_id,
                    COUNT(*) AS events,
                    COUNT(DISTINCT session_id) AS sessions,
                    COALESCE(SUM(token_count), 0) AS total_tokens,
                    COALESCE(AVG(token_count), 0) AS avg_token_per_event,
                    COALESCE(AVG(CASE WHEN latency_ms > 0 THEN latency_ms END), 0) AS avg_latency,
                    COUNT(*) FILTER (WHERE operation = 'READ') AS reads,
                    COUNT(*) FILTER (WHERE operation = 'WRITE') AS writes,
                    COUNT(*) FILTER (WHERE operation = 'UPDATE') AS updates,
                    COUNT(*) FILTER (WHERE operation = 'EXPIRE') AS expires,
                    COUNT(*) FILTER (WHERE layer = 'skill') AS skill_events,
                    COUNT(*) FILTER (WHERE layer = 'prompt') AS prompt_events,
                    COUNT(*) FILTER (WHERE layer = 'provider') AS provider_events,
                    MAX(ts) AS last_active
                FROM memory_events
                GROUP BY agent_id
                ORDER BY events DESC
                """;
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("events", rs.getLong("events"));
            row.put("sessions", rs.getLong("sessions"));
            row.put("totalTokens", rs.getLong("total_tokens"));
            row.put("avgTokenPerEvent", rs.getDouble("avg_token_per_event"));
            row.put("avgLatency", rs.getDouble("avg_latency"));
            row.put("reads", rs.getLong("reads"));
            row.put("writes", rs.getLong("writes"));
            row.put("updates", rs.getLong("updates"));
            row.put("expires", rs.getLong("expires"));
            row.put("skillEvents", rs.getLong("skill_events"));
            row.put("promptEvents", rs.getLong("prompt_events"));
            row.put("providerEvents", rs.getLong("provider_events"));
            row.put("lastActive", rs.getTimestamp("last_active") != null
                    ? rs.getTimestamp("last_active").toInstant().toString() : null);
            return row;
        });
    }

    /** Agent 对比 · Session 维度明细：单个 Agent 内按 session 分组统计（行内展开用）。 */
    public List<Map<String, Object>> queryAgentSessionComparison(String agentId) {
        String sql = """
                SELECT session_id,
                    COUNT(*) AS events,
                    COALESCE(SUM(token_count), 0) AS total_tokens,
                    COALESCE(AVG(token_count), 0) AS avg_token_per_event,
                    COALESCE(AVG(CASE WHEN latency_ms > 0 THEN latency_ms END), 0) AS avg_latency,
                    COUNT(*) FILTER (WHERE operation = 'READ') AS reads,
                    COUNT(*) FILTER (WHERE operation = 'WRITE') AS writes,
                    COUNT(*) FILTER (WHERE operation = 'UPDATE') AS updates,
                    COUNT(*) FILTER (WHERE operation = 'EXPIRE') AS expires,
                    COUNT(*) FILTER (WHERE layer = 'skill') AS skill_events,
                    COUNT(*) FILTER (WHERE layer = 'prompt') AS prompt_events,
                    COUNT(*) FILTER (WHERE layer = 'provider') AS provider_events,
                    MAX(ts) AS last_active
                FROM memory_events
                WHERE agent_id = ?
                GROUP BY session_id
                ORDER BY events DESC, total_tokens DESC
                """;
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", rs.getString("session_id"));
            row.put("events", rs.getLong("events"));
            row.put("totalTokens", rs.getLong("total_tokens"));
            row.put("avgTokenPerEvent", rs.getDouble("avg_token_per_event"));
            row.put("avgLatency", rs.getDouble("avg_latency"));
            row.put("reads", rs.getLong("reads"));
            row.put("writes", rs.getLong("writes"));
            row.put("updates", rs.getLong("updates"));
            row.put("expires", rs.getLong("expires"));
            row.put("skillEvents", rs.getLong("skill_events"));
            row.put("promptEvents", rs.getLong("prompt_events"));
            row.put("providerEvents", rs.getLong("provider_events"));
            row.put("lastActive", rs.getTimestamp("last_active") != null
                    ? rs.getTimestamp("last_active").toInstant().toString() : null);
            return row;
        }, agentId);
    }

    /** 17. 多 Agent Token 趋势对比（按天聚合）。 */
    public List<Map<String, Object>> queryAgentTokenTrend(List<String> agentIds, int days) {
        if (agentIds == null || agentIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(agentIds.size(), "?"));
        String sql = "SELECT agent_id, date_trunc('day', ts) AS day, " +
                "COALESCE(SUM(token_count), 0) AS tokens, COUNT(*) AS events " +
                "FROM memory_events WHERE agent_id IN (" + placeholders + ") " +
                "AND ts >= NOW() - ? * INTERVAL '1 day' " +
                "GROUP BY agent_id, day ORDER BY day ASC, agent_id";
        List<Object> params = new java.util.ArrayList<>(agentIds);
        params.add(days);
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("day", rs.getDate("day").toLocalDate().toString());
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            return row;
        }, params.toArray());
    }

    /** 18. Skill memory_key 分布 Top N（调用次数 + Token）。 */
    public List<Map<String, Object>> querySkillTopKeys(String agentId, int limit) {
        String sql = "SELECT memory_key, COUNT(*) AS calls, " +
                "COALESCE(SUM(token_count), 0) AS tokens, " +
                "COALESCE(AVG(CASE WHEN latency_ms > 0 THEN latency_ms END), 0) AS avg_latency " +
                "FROM memory_events WHERE agent_id = ? AND layer = 'skill' " +
                "GROUP BY memory_key ORDER BY calls DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("calls", rs.getLong("calls"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("avgLatency", rs.getDouble("avg_latency"));
            return row;
        }, agentId, limit);
    }

    /** 19. Skill 操作分布（READ/WRITE/UPDATE/EXPIRE 占比）。 */
    public List<Map<String, Object>> querySkillOpDistribution(String agentId) {
        String sql = "SELECT operation, COUNT(*) AS cnt, COALESCE(SUM(token_count), 0) AS tokens " +
                "FROM memory_events WHERE agent_id = ? AND layer = 'skill' " +
                "GROUP BY operation ORDER BY cnt DESC";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", rs.getString("operation"));
            row.put("count", rs.getLong("cnt"));
            row.put("tokens", rs.getLong("tokens"));
            return row;
        }, agentId);
    }

    /** 20. Skill 日趋势。 */
    public List<Map<String, Object>> querySkillDailyTrend(String agentId, int days) {
        String sql = "SELECT date_trunc('day', ts) AS day, COUNT(*) AS calls, " +
                "COALESCE(SUM(token_count), 0) AS tokens " +
                "FROM memory_events WHERE agent_id = ? AND layer = 'skill' " +
                "AND ts >= NOW() - ? * INTERVAL '1 day' " +
                "GROUP BY day ORDER BY day ASC";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", rs.getDate("day").toLocalDate().toString());
            row.put("calls", rs.getLong("calls"));
            row.put("tokens", rs.getLong("tokens"));
            return row;
        }, agentId, days);
    }

    /** 21. Skill 跨 Agent 使用对比：每个 Agent 的 skill 事件数和 Token。 */
    public List<Map<String, Object>> querySkillCrossAgent() {
        String sql = "SELECT agent_id, COUNT(*) AS skill_calls, " +
                "COALESCE(SUM(token_count), 0) AS skill_tokens, " +
                "COUNT(DISTINCT memory_key) AS unique_keys " +
                "FROM memory_events WHERE layer = 'skill' " +
                "GROUP BY agent_id ORDER BY skill_calls DESC";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("skillCalls", rs.getLong("skill_calls"));
            row.put("skillTokens", rs.getLong("skill_tokens"));
            row.put("uniqueKeys", rs.getLong("unique_keys"));
            return row;
        });
    }

    /** 22. 全局 Skill Top keys（跨所有 Agent）。 */
    public List<Map<String, Object>> querySkillGlobalTopKeys(int limit) {
        String sql = "SELECT memory_key, COUNT(*) AS calls, " +
                "COALESCE(SUM(token_count), 0) AS tokens, " +
                "COUNT(DISTINCT agent_id) AS agent_count " +
                "FROM memory_events WHERE layer = 'skill' " +
                "GROUP BY memory_key ORDER BY calls DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("calls", rs.getLong("calls"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("agentCount", rs.getLong("agent_count"));
            return row;
        }, limit);
    }

    /** P1. Skill 执行时间过长：layer=skill 中单事件 latency 超阈值的 Agent 聚合（按最大耗时倒序）。 */
    public List<Map<String, Object>> querySlowSkillByAgent(Instant from, Instant to, String agentId, long minLatencyMs) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, COUNT(*) AS events, " +
                "ROUND(MAX(latency_ms)::numeric, 0)::bigint AS max_ms, " +
                "ROUND(AVG(latency_ms)::numeric, 0)::bigint AS avg_ms, MAX(ts) AS last_ts " +
                "FROM memory_events WHERE layer = 'skill' AND latency_ms > ?");
        List<Object> args = new ArrayList<>();
        args.add(minLatencyMs);
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id ORDER BY max_ms DESC LIMIT 100");
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("events", rs.getLong("events"));
            row.put("maxMs", rs.getLong("max_ms"));
            row.put("avgMs", rs.getLong("avg_ms"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** P2. Skill 执行次数过多：layer=skill 事件数超阈值的 Agent。 */
    public List<Map<String, Object>> querySkillCountByAgent(Instant from, Instant to, String agentId, long minCount) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, COUNT(*) AS events, MAX(ts) AS last_ts " +
                "FROM memory_events WHERE layer = 'skill'");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id HAVING COUNT(*) > ? ORDER BY events DESC LIMIT 100");
        args.add(minCount);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** P-SR. Skill 被反复调用：单 turn 内同一技能调用次数超阈值（循环/震荡检测）。 */
    public List<Map<String, Object>> querySkillRepeat(Instant from, Instant to, String agentId, long minCount) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, metadata->>'turn_message_id' AS turn_id, " +
                "SUBSTRING(memory_key FROM 6) AS skill, COUNT(*) AS cnt, MAX(ts) AS last_ts " +
                "FROM memory_events WHERE " + SKILL_CALL_FILTER +
                " AND metadata->>'turn_message_id' IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, turn_id, skill HAVING COUNT(*) > ? ORDER BY cnt DESC LIMIT 50");
        args.add(minCount);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("turnId", rs.getString("turn_id"));
            row.put("skill", rs.getString("skill"));
            row.put("count", rs.getLong("cnt"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** P-SE. Skill 执行报错：metadata.status='failed'（或 error 字段）的技能调用聚合。 */
    public List<Map<String, Object>> querySkillErrors(Instant from, Instant to, String agentId, long minCount) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, session_id, SUBSTRING(memory_key FROM 6) AS skill, " +
                "COUNT(*) AS err_count, MAX(ts) AS last_ts, " +
                "MAX(memory_summary) AS sample_summary " +
                "FROM memory_events WHERE layer = 'skill' " +
                "AND (metadata->>'status' = 'failed' OR metadata->>'error' IS NOT NULL)");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, session_id, skill HAVING COUNT(*) > ? ORDER BY err_count DESC LIMIT 50");
        args.add(minCount);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("skill", rs.getString("skill"));
            row.put("errCount", rs.getLong("err_count"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            row.put("sample", shorten(rs.getString("sample_summary"), 120));
            return row;
        }, args.toArray());
    }

    /** 技能指标聚合（Skill 分析页）：每技能 调用次数 / Token 消耗 / 引用 Agent 数 / 报错数 / 平均耗时，支持时间窗口。 */
    public List<Map<String, Object>> querySkillMetrics(Instant from, Instant to, String agentId) {
        StringBuilder sql = new StringBuilder(
                "SELECT SUBSTRING(memory_key FROM 6) AS skill, COUNT(*) AS call_count, " +
                "COALESCE(SUM(token_count), 0) AS total_tokens, " +
                "COUNT(DISTINCT agent_id) AS agent_refs, " +
                "COUNT(*) FILTER (WHERE metadata->>'status' = 'failed') AS error_count, " +
                "COALESCE(AVG(CASE WHEN latency_ms > 0 THEN latency_ms END), 0) AS avg_latency, " +
                "MAX(ts) AS last_ts, " +
                "STRING_AGG(DISTINCT agent_id, ', ') AS agent_ids " +
                "FROM memory_events WHERE " + SKILL_CALL_FILTER);
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY skill ORDER BY total_tokens DESC LIMIT 50");
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skill", rs.getString("skill"));
            row.put("callCount", rs.getLong("call_count"));
            row.put("totalTokens", rs.getLong("total_tokens"));
            row.put("agentRefs", rs.getLong("agent_refs"));
            row.put("errorCount", rs.getLong("error_count"));
            row.put("avgLatencyMs", rs.getDouble("avg_latency"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            row.put("agentIds", rs.getString("agent_ids"));
            return row;
        }, args.toArray());
    }

}
