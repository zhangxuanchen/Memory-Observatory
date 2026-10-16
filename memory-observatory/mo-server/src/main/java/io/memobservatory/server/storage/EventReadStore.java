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
 * {@link EventRepository} 按域拆出的协作类（Read 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class EventReadStore extends SqlRepositoryBase {

    EventReadStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    /** 事件列表（记忆追踪）。 */
    public List<MemoryEvent> queryEvents(String agentId, String sessionId, String eventId, String op,
                                         String layer, Instant from, Instant to,
                                         int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT event_id, agent_id, session_id, operation, layer, memory_key, " +
                "memory_summary, token_count, latency_ms, ts, metadata, trace_id, parent_span_id " +
                "FROM memory_events WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (eventId != null && !eventId.isBlank()) { sql.append(" AND event_id = ?"); args.add(eventId); }
        if (sessionId != null && !sessionId.isBlank()) { sql.append(" AND session_id = ?"); args.add(sessionId); }
        if (op != null && !op.isBlank()) { sql.append(" AND operation = ?"); args.add(op.toUpperCase()); }
        if (layer != null && !layer.isBlank()) { sql.append(" AND layer = ?"); args.add(layer); }
        // PG driver 42.7 不认裸 Instant（"Can't infer the SQL type"），必须走 ts() 转 Timestamp
        if (from != null) { sql.append(" AND ts >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(ts(to)); }
        sql.append(" ORDER BY ts DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), EVENT_MAPPER, args.toArray());
    }

    /** 事件总数（用于分页计数，过滤条件同 queryEvents）。 */
    public long countEvents(String agentId, String sessionId, String eventId, String op,
                            String layer, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM memory_events WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (eventId != null && !eventId.isBlank()) { sql.append(" AND event_id = ?"); args.add(eventId); }
        if (sessionId != null && !sessionId.isBlank()) { sql.append(" AND session_id = ?"); args.add(sessionId); }
        if (op != null && !op.isBlank()) { sql.append(" AND operation = ?"); args.add(op.toUpperCase()); }
        if (layer != null && !layer.isBlank()) { sql.append(" AND layer = ?"); args.add(layer); }
        if (from != null) { sql.append(" AND ts >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(ts(to)); }
        Long n = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return n == null ? 0L : n;
    }

    /** 按查询范围聚合的统计：事件总数 / 活跃会话数 / Token 消耗合计（内存事件流筛选统计）。 */
    public Map<String, Object> eventStats(String agentId, String sessionId, String eventId, String op,
                                          String layer, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS cnt, COUNT(DISTINCT session_id) AS sess, COALESCE(SUM(token_count),0) AS tokens " +
                "FROM memory_events WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (eventId != null && !eventId.isBlank()) { sql.append(" AND event_id = ?"); args.add(eventId); }
        if (sessionId != null && !sessionId.isBlank()) { sql.append(" AND session_id = ?"); args.add(sessionId); }
        if (op != null && !op.isBlank()) { sql.append(" AND operation = ?"); args.add(op.toUpperCase()); }
        if (layer != null && !layer.isBlank()) { sql.append(" AND layer = ?"); args.add(layer); }
        if (from != null) { sql.append(" AND ts >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(ts(to)); }
        Map<String, Object> row = jdbc.queryForMap(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ((Number) row.get("cnt")).longValue());
        out.put("activeSessions", ((Number) row.get("sess")).longValue());
        out.put("totalTokens", ((Number) row.get("tokens")).longValue());
        return out;
    }

    /** 会话时间线（记忆时间线）：按 layer 分泳道。 */
    public Map<String, List<MemoryEvent>> queryTimeline(String sessionId) {
        String sql = "SELECT event_id, agent_id, session_id, operation, layer, memory_key, " +
                "memory_summary, token_count, latency_ms, ts, metadata, trace_id, parent_span_id " +
                "FROM memory_events WHERE session_id = ? ORDER BY ts ASC";
        List<MemoryEvent> all = jdbc.query(sql, EVENT_MAPPER, sessionId);
        Map<String, List<MemoryEvent>> lanes = new HashMap<>();
        for (MemoryEvent e : all) {
            lanes.computeIfAbsent(e.layer(), k -> new ArrayList<>()).add(e);
        }
        return lanes;
    }

    /** Token 统计（Token 分析）：按 layer 聚合。 */
    public Map<String, Long> tokenByLayer(String agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT layer, COALESCE(SUM(token_count),0) AS t FROM memory_events WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY layer");
        return groupQuery(sql.toString(), args);
    }

    /** Token 统计：按 operation 聚合。 */
    public Map<String, Long> tokenByOp(String agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT operation, COALESCE(SUM(token_count),0) AS t FROM memory_events WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        appendTime(sql, args, from, to);
        sql.append(" GROUP BY operation");
        return groupQuery(sql.toString(), args);
    }

    /** Token 趋势：按时间桶。 */
    public List<Map<String, Object>> tokenTrend(String agentId, Instant from, Instant to, String pgUnit) {
        // pgUnit: 'hour' / 'day' / 'minute'（对应 date_trunc 单位）
        String sql = "SELECT date_trunc(?, ts) AS bucket, COALESCE(SUM(token_count),0) AS tokens " +
                "FROM memory_events WHERE agent_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(pgUnit);
        args.add(agentId);
        StringBuilder sb = new StringBuilder(sql);
        appendTime(sb, args, from, to);
        sb.append(" GROUP BY bucket ORDER BY bucket ASC");
        return jdbc.query(sb.toString(), (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("bucket", rs.getTimestamp("bucket").toInstant().toString());
            row.put("tokens", rs.getLong("tokens"));
            return row;
        }, args.toArray());
    }

    /** 五区预算占比（来自 snapshots）。 */
    public double memoryZonePct(String agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(AVG(memory_tokens)::float / NULLIF(AVG(total_tokens),0), 0) AS pct " +
                "FROM memory_snapshots WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        appendTime(sql, args, from, to);
        return jdbc.queryForObject(sql.toString(), Double.class, args.toArray());
    }

    /**
     * 五区 Token 预算：来自 memory_snapshots 的 5 区字段求 AVG，
     * 返回 system / task / memory / toolHistory / free 五区均值 + 压缩信息。
     * 若无快照，所有值为 0。
     */
    public Map<String, Object> zoneBudget(String agentId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT " +
                " COALESCE(AVG(total_tokens), 0) AS total, " +
                " COALESCE(AVG(system_tokens), 0) AS system_t, " +
                " COALESCE(AVG(task_tokens), 0) AS task_t, " +
                " COALESCE(AVG(memory_tokens), 0) AS memory_t, " +
                " COALESCE(AVG(tool_history_tokens), 0) AS tool_history_t, " +
                " COALESCE(AVG(free_tokens), 0) AS free_t, " +
                " COALESCE(AVG(compression_count), 0) AS comp_count, " +
                " COALESCE(AVG(last_compression_ratio), 0) AS comp_ratio, " +
                " COUNT(*) AS snap_count, " +
                " MAX(ts) AS last_ts " +
                "FROM memory_snapshots WHERE agent_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        appendTime(sql, args, from, to);
        return jdbc.queryForObject(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("total", rs.getLong("total"));
            row.put("system", rs.getLong("system_t"));
            row.put("task", rs.getLong("task_t"));
            row.put("memory", rs.getLong("memory_t"));
            row.put("toolHistory", rs.getLong("tool_history_t"));
            row.put("free", rs.getLong("free_t"));
            row.put("compressionCount", rs.getLong("comp_count"));
            row.put("lastCompressionRatio", rs.getDouble("comp_ratio"));
            row.put("snapshotCount", rs.getLong("snap_count"));
            java.sql.Timestamp last = rs.getTimestamp("last_ts");
            row.put("snapshotTs", last == null ? null : last.toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 会话列表（最近活动）。 */
    public List<Map<String, Object>> querySessions(String agentId, int limit) {
        String sql = "SELECT session_id, MAX(ts) AS last_ts, COUNT(*) AS event_count " +
                "FROM memory_events WHERE agent_id = ? GROUP BY session_id " +
                "ORDER BY last_ts DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("sessionId", rs.getString("session_id"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            row.put("eventCount", rs.getLong("event_count"));
            return row;
        }, agentId, limit);
    }

    /** 所有 Agent 列表（按最近活跃排序）。 */
    public List<Map<String, Object>> queryAgents(int limit) {
        String sql = "SELECT agent_id, MAX(ts) AS last_ts, COUNT(*) AS event_count " +
                "FROM memory_events GROUP BY agent_id ORDER BY last_ts DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("agentId", rs.getString("agent_id"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            row.put("eventCount", rs.getLong("event_count"));
            return row;
        }, limit);
    }

    public Map<String, Object> getEventById(String eventId) {
        String sql = "SELECT event_id, agent_id, session_id, operation, layer, memory_key, " +
                "memory_summary, token_count, latency_ms, ts, metadata, " +
                "trace_id, parent_span_id FROM memory_events WHERE event_id = ?";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", rs.getString("event_id"));
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("memorySummary", rs.getString("memory_summary"));
            row.put("tokenCount", rs.getInt("token_count"));
            row.put("latencyMs", rs.getDouble("latency_ms"));
            row.put("ts", rs.getTimestamp("ts").toInstant().toString());
            row.put("traceId", rs.getString("trace_id"));
            row.put("parentSpanId", rs.getString("parent_span_id"));
            // metadata：jsonb 读出来是 PGobject，转字符串再用 Jackson 解析
            Object metaObj = rs.getObject("metadata");
            Map<String, String> meta = new LinkedHashMap<>();
            if (metaObj != null) {
                String metaStr = metaObj.toString();
                try {
                    meta = MAPPER.readValue(metaStr, Map.class);
                } catch (Exception e) {
                    // 解析失败用空 map
                }
            }
            row.put("metadata", meta);
            return row;
        }, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 25. 导出用：按过滤条件读取原始事件行。
     *
     * <p>用 {@link RowCallbackHandler} 逐行回调而不是返回 List：导出窗口动辄几十万行，
     * 先物化成 List 再序列化会把内存打满。调用方在回调里边读边写，内存占用与行数无关。
     *
     * <p>只按 ts 升序返回；LIMIT 由调用方按 {@code mo.export.max-rows} 传入，是导出量的硬上限。
     */
    public void streamEventsForExport(Instant from, Instant to, String agentId, String sessionId,
                                      String traceId, int limit, EventStreamPort.RowSink sink) {
        StringBuilder sql = new StringBuilder("""
                SELECT event_id, agent_id, session_id, operation, layer, memory_key, memory_summary,
                       token_count, latency_ms, ts, metadata, trace_id, parent_span_id
                FROM memory_events WHERE 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (agentId != null && !agentId.isBlank()) {
            sql.append(" AND agent_id = ?");
            args.add(agentId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = ?");
            args.add(traceId);
        }
        appendTime(sql, args, from, to);
        sql.append(" ORDER BY ts ASC LIMIT ?");
        args.add(limit);
        jdbc.query(sql.toString(), (RowCallbackHandler) sink::accept, args.toArray());
    }

}
