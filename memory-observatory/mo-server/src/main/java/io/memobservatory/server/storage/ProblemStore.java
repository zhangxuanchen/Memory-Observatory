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
 * {@link EventRepository} 按域拆出的协作类（Prob 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class ProblemStore extends SqlRepositoryBase {

    ProblemStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    /** 流程分析 · Session 列表（Session 切换下拉）：按真实 session_id 分组，turn 数 = session 内 trace 数。 */
    public List<Map<String, Object>> queryFlowSessions(Instant from, Instant to, String agentId) {
        StringBuilder sql = new StringBuilder(
                "SELECT session_id, COUNT(DISTINCT COALESCE(NULLIF(trace_id,''), session_id)) AS turns, " +
                "COUNT(*) AS events, COALESCE(SUM(token_count), 0) AS tokens, " +
                "COUNT(*) FILTER (WHERE metadata->>'status' = 'failed') AS failures, " +
                "MIN(ts) AS start_ts, MAX(ts) AS end_ts " +
                "FROM memory_events WHERE agent_id = ?" + FLOW_LAYERS);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY session_id ORDER BY MAX(ts) DESC LIMIT 100");
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", rs.getString("session_id"));
            row.put("turns", rs.getLong("turns"));
            row.put("events", rs.getLong("events"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("failures", rs.getLong("failures"));
            row.put("startTs", rs.getTimestamp("start_ts").toInstant().toString());
            row.put("endTs", rs.getTimestamp("end_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 流程分析 · 事件序列（Session 过滤按真实 session_id；带 session_id 供单事件 trace 退化为 session 串联），供 Service 内存构建流程/转移图/循环。 */
    public List<Map<String, Object>> queryFlowEvents(Instant from, Instant to, String agentId, String sessionId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(NULLIF(trace_id,''), session_id) AS trace_id, session_id, ts, operation, layer, " +
                "memory_key, token_count, latency_ms, metadata->>'status' AS status, LEFT(memory_summary, 500) AS memory_summary, " +
                "LEFT(metadata->>'turn_user', 500) AS turn_user, " +
                "COUNT(*) OVER (PARTITION BY COALESCE(NULLIF(trace_id,''), session_id)) AS trace_size " +
                "FROM memory_events WHERE agent_id = ?" + FLOW_LAYERS);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        appendTime(sql, args, from, to);
        sql.append(" ORDER BY 1, ts LIMIT 50000");
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceId", rs.getString("trace_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("ts", rs.getTimestamp("ts").toInstant().toString());
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("tokens", rs.getLong("token_count"));
            row.put("latencyMs", rs.getLong("latency_ms"));
            row.put("status", rs.getString("status"));
            row.put("summary", rs.getString("memory_summary"));
            row.put("turnUser", rs.getString("turn_user"));
            row.put("traceSize", rs.getLong("trace_size"));
            return row;
        }, args.toArray());
    }

    /** P3. Tools 工具消耗 Token 过大：layer=provider（外部工具/API）Token 合计超阈值的 Agent。 */
    public List<Map<String, Object>> queryToolTokenByAgent(Instant from, Instant to, String agentId, long minTokens) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, COALESCE(SUM(token_count), 0) AS tokens, COUNT(*) AS events, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_events WHERE layer = 'provider' AND token_count > 0");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id HAVING SUM(token_count) > ? ORDER BY tokens DESC LIMIT 100");
        args.add(minTokens);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** P4/P5/P6/P8. 单 Turn 综合指标：按 turn_message_id 聚合每个 Turn 的 token/sum events/工具数/总耗时（墙钟跨度）。
     *  一次查询取全量，控制器按不同阈值切分命中。 */
    public List<Map<String, Object>> queryTurnMetrics(Instant from, Instant to, String agentId) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, session_id, metadata->>'turn_message_id' AS turn_id, " +
                "COALESCE(SUM(token_count), 0) AS tokens, COUNT(*) AS events, " +
                "COUNT(*) FILTER (WHERE layer = 'provider') AS tool_events, " +
                "ROUND(EXTRACT(EPOCH FROM (MAX(ts) - MIN(ts))) * 1000, 0)::bigint AS total_ms, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_events WHERE metadata->>'turn_message_id' IS NOT NULL");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, session_id, metadata->>'turn_message_id' " +
                   "ORDER BY tokens DESC LIMIT 2000");
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("turnId", rs.getString("turn_id"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("toolEvents", rs.getLong("tool_events"));
            row.put("totalMs", rs.getLong("total_ms"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** P7. 记忆膨胀 / 压缩过于频繁：memory_snapshots 中压缩次数过高或空闲比例过低（近满）的会话。 */
    public List<Map<String, Object>> queryMemoryPressure(Instant from, Instant to, String agentId,
                                                         long minCompressions, double minFreeRatio) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, session_id, COUNT(*) AS samples, MAX(compression_count) AS max_compressions, " +
                "MIN(CASE WHEN total_tokens > 0 THEN free_tokens * 1.0 / total_tokens END) AS min_free_ratio, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_snapshots");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        if (agentId != null && !agentId.isBlank()) { sql.append(" WHERE agent_id = ?"); args.add(agentId); first = false; }
        if (from != null) { sql.append(first ? " WHERE ts >= ?" : " AND ts >= ?"); args.add(ts(from)); first = false; }
        if (to != null) { sql.append(first ? " WHERE ts <= ?" : " AND ts <= ?"); args.add(ts(to)); first = false; }
        sql.append(" GROUP BY agent_id, session_id " +
                   "HAVING MAX(compression_count) >= ? OR " +
                   "MIN(CASE WHEN total_tokens > 0 THEN free_tokens * 1.0 / total_tokens END) < ? " +
                   "ORDER BY max_compressions DESC, min_free_ratio ASC NULLS LAST LIMIT 200");
        args.add(minCompressions);
        args.add(minFreeRatio);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("samples", rs.getLong("samples"));
            row.put("maxCompressions", rs.getLong("max_compressions"));
            Object ratio = rs.getObject("min_free_ratio");
            row.put("minFreeRatio", ratio == null ? null : ((Number) ratio).doubleValue());
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** H1. 模型推理延迟过高：layer=model 事件平均耗时超阈值的 Agent（harness 与模型交互僵）。 */
    public List<Map<String, Object>> queryModelSlowByAgent(Instant from, Instant to, String agentId, long minAvgMs) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, COUNT(*) AS events, " +
                "ROUND(AVG(latency_ms)::numeric, 0)::bigint AS avg_ms, " +
                "ROUND(MAX(latency_ms)::numeric, 0)::bigint AS max_ms, MAX(ts) AS last_ts " +
                "FROM memory_events WHERE layer = 'model' AND latency_ms > 0");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id HAVING AVG(latency_ms) > ? ORDER BY avg_ms DESC LIMIT 100");
        args.add(minAvgMs);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("events", rs.getLong("events"));
            row.put("avgMs", rs.getLong("avg_ms"));
            row.put("maxMs", rs.getLong("max_ms"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** H2. 单会话累计膨胀：整个会话（跨 turn）记忆事件 token 合计超阈值（harness 未能收敛会话）。 */
    public List<Map<String, Object>> querySessionTokenByAgent(Instant from, Instant to, String agentId, long minTokens) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, session_id, COALESCE(SUM(token_count), 0) AS tokens, COUNT(*) AS events, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_events WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, session_id HAVING SUM(token_count) > ? " +
                   "ORDER BY tokens DESC LIMIT 200");
        args.add(minTokens);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** H3. Prompt 上下文占比失衡：快照里 (系统提示+任务提示) 占 total 的平均比例超阈值，挤压记忆/工具空间。 */
    public List<Map<String, Object>> queryPromptSharePressure(Instant from, Instant to, String agentId, double minRatio) {
        String expr = "(system_tokens + task_tokens) * 1.0 / NULLIF(total_tokens, 0)";
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, session_id, COUNT(*) AS samples, " +
                "ROUND(AVG(" + expr + ")::numeric, 4)::double precision AS avg_share, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_snapshots WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, session_id HAVING AVG(" + expr + ") > ? " +
                   "ORDER BY avg_share DESC LIMIT 200");
        args.add(minRatio);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("samples", rs.getLong("samples"));
            row.put("avgShare", rs.getDouble("avg_share"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** H4. 记忆写入抖动：同一 memory_key 被反复 WRITE/UPDATE，记忆频繁覆写（churn）。 */
    public List<Map<String, Object>> queryMemoryKeyChurn(Instant from, Instant to, String agentId, long minWrites) {
        StringBuilder sql = new StringBuilder(
                "SELECT agent_id, memory_key, COUNT(*) AS writes, COALESCE(SUM(token_count), 0) AS tokens, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_events " +
                "WHERE operation IN ('WRITE','UPDATE') AND memory_key IS NOT NULL AND memory_key <> ''");
        List<Object> args = new ArrayList<>();
        appendAgent(sql, args, agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY agent_id, memory_key HAVING COUNT(*) > ? ORDER BY writes DESC LIMIT 200");
        args.add(minWrites);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("key", rs.getString("memory_key"));
            row.put("writes", rs.getLong("writes"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

}
