package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemoryOp;
import io.memobservatory.server.model.MemorySnapshot;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 事件存储与查询（JdbcTemplate）。
 * 写：批量插入事件/快照；读：事件列表、时间线泳道、Token 聚合、会话列表。
 *
 * 时序分桶用 date_trunc（PG 通用），TimescaleDB 的 time_bucket 可后续优化。
 */
@Repository
public class EventRepository {

    private static final String SQL_INSERT_EVENT = """
            INSERT INTO memory_events (event_id, agent_id, session_id, operation, layer,
                memory_key, memory_summary, token_count, latency_ms, ts, metadata,
                trace_id, parent_span_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SQL_INSERT_SNAPSHOT = """
            INSERT INTO memory_snapshots (snapshot_id, agent_id, session_id, total_tokens,
                system_tokens, task_tokens, memory_tokens, tool_history_tokens, free_tokens,
                compression_count, last_compression_ratio, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (snapshot_id) DO NOTHING
            """;

    private static final String SQL_IMPORT_LOG_FIND = """
            SELECT md5, file_name, llm, total, inserted, skipped, error_count, imported_at
            FROM import_log_files WHERE md5 = ?
            """;

    private static final String SQL_IMPORT_LOG_UPSERT = """
            INSERT INTO import_log_files (md5, file_name, llm, total, inserted, skipped, error_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (md5) DO UPDATE SET
                file_name = EXCLUDED.file_name, llm = EXCLUDED.llm,
                total = EXCLUDED.total, inserted = EXCLUDED.inserted,
                skipped = EXCLUDED.skipped, error_count = EXCLUDED.error_count,
                imported_at = now()
            """;

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ObjectMapper mapper;

    public EventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /** 按 MD5 查已导入的 .log 记录；不存在返回 null。 */
    public Map<String, Object> findImportedLogByMd5(String md5) {
        try {
            return jdbc.queryForMap(SQL_IMPORT_LOG_FIND, md5);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 记录（或刷新）一个已导入 .log 的 MD5 指纹与导入统计。 */
    public void upsertImportedLog(String md5, String fileName, boolean llm,
                                  int total, int inserted, int skipped, int errorCount) {
        jdbc.update(SQL_IMPORT_LOG_UPSERT, md5, fileName, llm, total, inserted, skipped, errorCount);
    }

    public void batchInsertEvents(List<MemoryEvent> events) {
        if (events.isEmpty()) return;
        jdbc.batchUpdate(SQL_INSERT_EVENT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MemoryEvent e = events.get(i);
                ps.setString(1, e.eventId());
                ps.setString(2, e.agentId());
                ps.setString(3, e.sessionId());
                ps.setString(4, e.operation().name());
                ps.setString(5, e.layer());
                ps.setString(6, e.memoryKey());
                ps.setString(7, e.memorySummary());
                ps.setInt(8, e.tokenCount());
                ps.setDouble(9, e.latencyMs());
                ps.setTimestamp(10, Timestamp.from(e.timestamp()));
                ps.setObject(11, jsonb(e.metadata()));
                ps.setString(12, e.traceId());
                ps.setString(13, e.parentSpanId());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    public void batchInsertSnapshots(List<MemorySnapshot> snaps) {
        if (snaps.isEmpty()) return;
        jdbc.batchUpdate(SQL_INSERT_SNAPSHOT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MemorySnapshot s = snaps.get(i);
                ps.setString(1, s.snapshotId());
                ps.setString(2, s.agentId());
                ps.setString(3, s.sessionId());
                ps.setInt(4, s.totalTokens());
                ps.setInt(5, s.systemTokens());
                ps.setInt(6, s.taskTokens());
                ps.setInt(7, s.memoryTokens());
                ps.setInt(8, s.toolHistoryTokens());
                ps.setInt(9, s.freeTokens());
                ps.setInt(10, s.compressionCount());
                ps.setDouble(11, s.lastCompressionRatio());
                ps.setTimestamp(12, Timestamp.from(s.timestamp()));
            }

            @Override
            public int getBatchSize() {
                return snaps.size();
            }
        });
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

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
        if (from != null) { sql.append(" AND ts >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(to); }
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
        if (from != null) { sql.append(" AND ts >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(to); }
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
        if (from != null) { sql.append(" AND ts >= ?"); args.add(from); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(to); }
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

    // ------------------------------------------------------------------
    // Token 分析扩展维度（8 个聚合查询）
    // 注意：所有时间参数都转 Timestamp.from(instant) 再加到 args，
    // 否则 PG JDBC 不能绑定 Instant 类型。
    // ------------------------------------------------------------------

    private static java.sql.Timestamp ts(Instant i) {
        return i == null ? null : java.sql.Timestamp.from(i);
    }

    /** 1. Top N 会话（按 token 总数排序）。 */
    public List<Map<String, Object>> queryTopSessions(String agentId, Instant from, Instant to, int limit) {
        String sql = "SELECT session_id, SUM(token_count) AS tokens, COUNT(*) AS events, " +
                "MIN(ts) AS first_ts, MAX(ts) AS last_ts " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY session_id ORDER BY tokens DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        args.add(limit);
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("sessionId", rs.getString("session_id"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("firstTs", rs.getTimestamp("first_ts").toInstant().toString());
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 2. 24 小时分布（按 UTC 小时聚合）。 */
    public List<Map<String, Object>> queryByHour(String agentId, Instant from, Instant to) {
        String sql = "SELECT EXTRACT(HOUR FROM ts AT TIME ZONE 'UTC') AS hour, " +
                "SUM(token_count) AS tokens, COUNT(*) AS events " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY hour ORDER BY hour";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("hour", rs.getInt("hour"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            return row;
        }, args.toArray());
    }

    /** 3. 操作×层矩阵：行=操作，列=层，单元格=token 数 + 事件数。 */
    public List<Map<String, Object>> queryOpLayerMatrix(String agentId, Instant from, Instant to) {
        String sql = "SELECT operation, layer, SUM(token_count) AS tokens, COUNT(*) AS events " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY operation, layer";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            return row;
        }, args.toArray());
    }

    /** 工作区日志分类报表：按 agent_id 分组聚合（行 = agent × operation × layer）。
     *  返回各 agent 的事件数、token 合计/均值、延迟均值、首次/末次时间。 */
    public List<Map<String, Object>> queryAgentClassify(List<String> agentIds, Instant from, Instant to) {
        if (agentIds == null || agentIds.isEmpty()) return List.of();
        String inClause = String.join(",", java.util.Collections.nCopies(agentIds.size(), "?"));
        String sql = "SELECT agent_id, operation, layer, COUNT(*) AS events, " +
                "SUM(token_count) AS tokens, COALESCE(AVG(token_count),0) AS avg_tokens, " +
                "COALESCE(AVG(latency_ms),0) AS avg_latency, " +
                "MIN(ts) AS time_from, MAX(ts) AS time_to " +
                "FROM memory_events WHERE agent_id IN (" + inClause + ")" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY agent_id, operation, layer";
        List<Object> args = new ArrayList<>(agentIds);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("agent_id", rs.getString("agent_id"));
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("events", rs.getLong("events"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("avg_tokens", rs.getDouble("avg_tokens"));
            row.put("avg_latency", rs.getDouble("avg_latency"));
            row.put("time_from", rs.getTimestamp("time_from") == null ? null
                    : rs.getTimestamp("time_from").toInstant().toString());
            row.put("time_to", rs.getTimestamp("time_to") == null ? null
                    : rs.getTimestamp("time_to").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 4. 延迟分位 P50/P90/P99 + Top N 慢操作。 */
    public Map<String, Object> queryLatencyStats(String agentId, Instant from, Instant to, int topN) {
        String pctSql = "SELECT " +
                "COALESCE(PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latency_ms), 0) AS p50, " +
                "COALESCE(PERCENTILE_CONT(0.9) WITHIN GROUP (ORDER BY latency_ms), 0) AS p90, " +
                "COALESCE(PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latency_ms), 0) AS p99, " +
                "COALESCE(AVG(latency_ms), 0) AS avg, " +
                "COALESCE(MAX(latency_ms), 0) AS max, " +
                "COUNT(*) AS samples " +
                "FROM memory_events WHERE agent_id = ? AND latency_ms > 0" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        Map<String, Object> stats = jdbc.queryForMap(pctSql, args.toArray());

        String topSql = "SELECT event_id, agent_id, operation, layer, memory_key, memory_summary, " +
                "latency_ms, token_count, ts, session_id " +
                "FROM memory_events WHERE agent_id = ? AND latency_ms > 0" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " ORDER BY latency_ms DESC LIMIT ?";
        List<Object> topArgs = new ArrayList<>();
        topArgs.add(agentId);
        if (from != null) topArgs.add(ts(from));
        if (to != null) topArgs.add(ts(to));
        topArgs.add(topN);
        List<MemoryEvent> topEvents = jdbc.query(topSql, EVENT_MAPPER, topArgs.toArray());

        Map<String, Object> result = new HashMap<>(stats);
        result.put("topSlow", topEvents);
        return result;
    }

    /** 5. Top N memoryKey（按 token 总数排序）。 */
    public List<Map<String, Object>> queryTopKeys(String agentId, Instant from, Instant to, int limit) {
        String sql = "SELECT memory_key, SUM(token_count) AS tokens, COUNT(*) AS events, " +
                "MAX(ts) AS last_ts " +
                "FROM memory_events WHERE agent_id = ? AND memory_key IS NOT NULL AND memory_key <> ''" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY memory_key ORDER BY tokens DESC LIMIT ?";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        args.add(limit);
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 6. 关键比率：读写比、遗忘率、更新率。 */
    public Map<String, Object> queryRatios(String agentId, Instant from, Instant to) {
        String sql = "SELECT " +
                "COALESCE(SUM(CASE WHEN operation='READ' THEN token_count ELSE 0 END), 0) AS read_tokens, " +
                "COALESCE(SUM(CASE WHEN operation='WRITE' THEN token_count ELSE 0 END), 0) AS write_tokens, " +
                "COALESCE(SUM(CASE WHEN operation='UPDATE' THEN token_count ELSE 0 END), 0) AS update_tokens, " +
                "COALESCE(SUM(CASE WHEN operation='EXPIRE' THEN token_count ELSE 0 END), 0) AS expire_tokens, " +
                "COUNT(CASE WHEN operation='READ' THEN 1 END) AS read_count, " +
                "COUNT(CASE WHEN operation='WRITE' THEN 1 END) AS write_count, " +
                "COUNT(CASE WHEN operation='UPDATE' THEN 1 END) AS update_count, " +
                "COUNT(CASE WHEN operation='EXPIRE' THEN 1 END) AS expire_count, " +
                "COUNT(*) AS total_count, " +
                "COALESCE(SUM(token_count), 0) AS total_tokens " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.queryForMap(sql, args.toArray());
    }

    /** 7. Token 分布直方图（按 token_count 分桶）。 */
    public List<Map<String, Object>> queryHistogram(String agentId, Instant from, Instant to) {
        String sql = "SELECT CASE " +
                "WHEN token_count < 100 THEN '0-100' " +
                "WHEN token_count < 500 THEN '100-500' " +
                "WHEN token_count < 1000 THEN '500-1k' " +
                "WHEN token_count < 5000 THEN '1k-5k' " +
                "ELSE '5k+' END AS bucket, " +
                "COUNT(*) AS events, SUM(token_count) AS tokens " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY bucket ORDER BY bucket";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("bucket", rs.getString("bucket"));
            row.put("events", rs.getLong("events"));
            row.put("tokens", rs.getLong("tokens"));
            return row;
        }, args.toArray());
    }

    /** 8. 每日趋势（按 UTC 日期聚合）。 */
    public List<Map<String, Object>> queryDailyTrend(String agentId, Instant from, Instant to) {
        String sql = "SELECT DATE(ts AT TIME ZONE 'UTC') AS day, " +
                "SUM(token_count) AS tokens, COUNT(*) AS events, " +
                "COUNT(DISTINCT session_id) AS sessions " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY day ORDER BY day";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("day", rs.getDate("day").toLocalDate().toString());
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("sessions", rs.getLong("sessions"));
            return row;
        }, args.toArray());
    }

    /** 9. 时间桶 × 层聚合（用于热力图）。bucketSec = 桶大小（秒）。 */
    public List<Map<String, Object>> queryLayerTimeBuckets(String agentId, Instant from, Instant to, long bucketSec) {
        // 用 FLOOR(EXTRACT(EPOCH FROM ts)/bucketSec)*bucketSec 对齐到桶起点
        String sql = "SELECT layer, " +
                "FLOOR(EXTRACT(EPOCH FROM ts)/" + bucketSec + ")*" + bucketSec + " AS bucket_epoch, " +
                "SUM(token_count) AS tokens, COUNT(*) AS events " +
                "FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY layer, bucket_epoch ORDER BY bucket_epoch, layer";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("layer", rs.getString("layer"));
            row.put("bucketEpoch", rs.getLong("bucket_epoch"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            return row;
        }, args.toArray());
    }


    private void appendTime(StringBuilder sql, List<Object> args, Instant from, Instant to) {
        if (from != null) { sql.append(" AND ts >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(ts(to)); }
    }

    private Map<String, Long> groupQuery(String sql, List<Object> args) {
        return jdbc.query(sql, rs -> {
            Map<String, Long> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
            return result;
        }, args.toArray());
    }

    private Object jsonb(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) return null;
        try {
            PGobject pgo = new PGobject();
            pgo.setType("jsonb");
            pgo.setValue(mapper.writeValueAsString(meta));
            return pgo;
        } catch (Exception e) {
            return null;
        }
    }

    private static final RowMapper<MemoryEvent> EVENT_MAPPER = (rs, n) -> {
        // 容错：有些 SELECT 没有查 trace_id / parent_span_id（老版本查询），这里 try/catch
        String traceId = null, parentSpanId = null;
        try { traceId = rs.getString("trace_id"); } catch (Exception ignored) {}
        try { parentSpanId = rs.getString("parent_span_id"); } catch (Exception ignored) {}
        // metadata：列表页通常为 NULL（节省反序列化开销），保持兼容
        Map<String, String> meta = null;
        try {
            Object metaObj = rs.getObject("metadata");
            if (metaObj != null) {
                meta = MAPPER.readValue(metaObj.toString(), Map.class);
            }
        } catch (Exception ignored) {}
        return MemoryEvent.builder()
                .eventId(rs.getString("event_id"))
                .agentId(rs.getString("agent_id"))
                .sessionId(rs.getString("session_id"))
                .operation(rs.getString("operation"))
                .layer(rs.getString("layer"))
                .memoryKey(rs.getString("memory_key"))
                .memorySummary(rs.getString("memory_summary"))
                .tokenCount(rs.getInt("token_count"))
                .latencyMs(rs.getDouble("latency_ms"))
                .timestamp(rs.getTimestamp("ts").toInstant())
                .metadata(meta)
                .traceId(traceId)
                .parentSpanId(parentSpanId)
                .build();
    };

    /** 10. 每会话 Token 总量分布 + 异常检测（KP 8.4.2：按历史 P99 判定异常贵）。
     *  返回 P50/P95/P99 + 异常会话列表（token > P99）。
     *  注意：分位数在 Java 层计算（PG 14 无 PERCENTILE_DISC 聚合函数）。 */
    public Map<String, Object> querySessionTokenDistribution(String agentId, Instant from, Instant to) {
        String sql = "SELECT session_id, SUM(token_count) AS tokens, COUNT(*) AS events, " +
                "MAX(ts) AS last_ts FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " GROUP BY session_id ORDER BY tokens DESC";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("sessionId", rs.getString("session_id"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());

        // Java 层算分位数
        int n = rows.size();
        Map<String, Object> result = new LinkedHashMap<>();
        if (n == 0) {
            result.put("count", 0);
            result.put("p50", 0);result.put("p95", 0);result.put("p99", 0);result.put("max", 0);
            result.put("abnormalSessions", java.util.Collections.emptyList());
            return result;
        }
        long[] tokens = rows.stream().mapToLong(r -> ((Number) r.get("tokens")).longValue()).sorted().toArray();
        long p50 = tokens[(int) Math.min(n - 1, Math.floor(n * 0.5))];
        long p95 = tokens[(int) Math.min(n - 1, Math.floor(n * 0.95))];
        long p99 = tokens[(int) Math.min(n - 1, Math.floor(n * 0.99))];
        long max = tokens[n - 1];
        // 异常会话：tokens > P99
        List<Map<String, Object>> abnormal = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long t = ((Number) r.get("tokens")).longValue();
            if (t > p99) abnormal.add(r);
        }
        result.put("count", n);
        result.put("p50", p50);
        result.put("p95", p95);
        result.put("p99", p99);
        result.put("max", max);
        result.put("abnormalSessions", abnormal);
        // topSessions: 按 token 降序前 5（前端在无异常时展示参考）
        result.put("topSessions", rows.subList(0, Math.min(5, n)));
        return result;
    }

    /** 11. 燃烧速率 burnRate（KP 8.4.1 末尾：实际消耗 / 预期消耗，分级响应触发器）。
     *  没有预算字段，用默认 50k/天（≈2083/h）做基线。返回 burnRate + 分级状态。 */
    public Map<String, Object> queryBurnRate(String agentId, Instant from, Instant to) {
        // 默认基线：50000 tokens/天 → 50000/24/60 ≈ 34.7 tokens/分钟
        long baselinePerMin = 50_000 / 24 / 60;
        // 计算实际窗口内的消耗速率
        long windowSec = (from == null ? 3600 : (to.toEpochMilli() - from.toEpochMilli()) / 1000);
        long windowMin = Math.max(1, windowSec / 60);
        long expected = baselinePerMin * windowMin;
        String sql = "SELECT COALESCE(SUM(token_count), 0) AS tokens FROM memory_events WHERE agent_id = ?" +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        long actual = jdbc.queryForObject(sql, Long.class, args.toArray());
        if (actual == 0) actual = 0;
        double burnRate = expected == 0 ? 0 : (double) actual / expected;
        // 分级：<0.5 低消耗 / 0.5-1.0 正常 / 1.0-1.2 观察 / 1.2-1.5 降级 / ≥1.5 熔断
        String level;
        if (burnRate < 0.5) level = "low";
        else if (burnRate < 1.0) level = "normal";
        else if (burnRate < 1.2) level = "watch";
        else if (burnRate < 1.5) level = "degrade";
        else level = "circuit_break";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actual", actual);
        result.put("expected", expected);
        result.put("burnRate", Math.round(burnRate * 100) / 100.0);
        result.put("level", level);
        result.put("baselinePerDay", 50_000L);
        result.put("windowSec", windowSec);
        return result;
    }

    /** 12. memory_key Token P99 异常检测（KP 8.4.2 思路推广到 memory_key）。
     *  哪些 key 消耗 token 超过历史 P99 → 记忆异常膨胀信号。 */
    public Map<String, Object> queryKeyTokenAbnormal(String agentId, String sessionId, Instant from, Instant to, int topN) {
        String sql = "SELECT memory_key, SUM(token_count) AS tokens, COUNT(*) AS events, " +
                "MAX(ts) AS last_ts FROM memory_events WHERE agent_id = ?" +
                (sessionId == null || sessionId.isBlank() ? "" : " AND session_id = ?") +
                (from == null ? "" : " AND ts >= ?") +
                (to == null ? "" : " AND ts <= ?") +
                " AND memory_key IS NOT NULL AND memory_key <> '' " +
                "GROUP BY memory_key ORDER BY tokens DESC";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (sessionId != null && !sessionId.isBlank()) args.add(sessionId);
        if (from != null) args.add(ts(from));
        if (to != null) args.add(ts(to));
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("tokens", rs.getLong("tokens"));
            row.put("events", rs.getLong("events"));
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        int n = rows.size();
        if (n == 0) {
            result.put("count", 0);
            result.put("p99", 0);
            result.put("abnormalKeys", java.util.Collections.emptyList());
            return result;
        }
        long[] tokens = rows.stream().mapToLong(r -> ((Number) r.get("tokens")).longValue()).sorted().toArray();
        long p99 = tokens[(int) Math.min(n - 1, Math.floor(n * 0.99))];
        List<Map<String, Object>> abnormal = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long t = ((Number) r.get("tokens")).longValue();
            if (t > p99) abnormal.add(r);
        }
        if (abnormal.isEmpty() && n > 0) {
            // 取 Top N 作为参考（没有超 P99 时展示 Top N）
            abnormal = rows.subList(0, Math.min(topN, n));
        }
        result.put("count", n);
        result.put("p99", p99);
        result.put("allKeys", rows);
        result.put("topKeys", rows.subList(0, Math.min(topN, n)));
        result.put("abnormalKeys", abnormal);
        return result;
    }

    /** 13. 按 eventId 查询单条事件完整详情（含 metadata 全字段 + Trace 字段）。 */
    @SuppressWarnings("unchecked")
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

    /** 14. 查询同 turn_message_id 的相关事件（用于抽屉显示完整 turn 详情 + actions 子事件）。
     *  排除主事件本身（operation != 'WRITE' 或 layer != 'session'）。 */
    public List<Map<String, Object>> getTurnEvents(String agentId, String turnMessageId, String excludeEventId) {
        // 用 metadata->>'turn_message_id' = ? 筛选 jsonb 内嵌字段
        String sql = "SELECT event_id, agent_id, session_id, operation, layer, memory_key, " +
                "memory_summary, token_count, latency_ms, ts, " +
                "metadata->>'turn_message_id' AS turn_msg_id, " +
                "metadata->>'action_idx' AS action_idx, " +
                "metadata->>'action_full' AS action_full, " +
                "metadata->>'turn_user' AS turn_user, " +
                "metadata->>'turn_actions' AS turn_actions, " +
                "metadata->>'turn_outcome' AS turn_outcome, " +
                "metadata->>'turn_learned' AS turn_learned " +
                "FROM memory_events WHERE agent_id = ? AND metadata->>'turn_message_id' = ? " +
                "ORDER BY ts ASC";
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", rs.getString("event_id"));
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("memorySummary", rs.getString("memory_summary"));
            row.put("tokenCount", rs.getInt("token_count"));
            row.put("latencyMs", rs.getDouble("latency_ms"));
            row.put("ts", rs.getTimestamp("ts").toInstant().toString());
            row.put("actionIdx", rs.getString("action_idx"));
            row.put("actionFull", rs.getString("action_full"));
            row.put("turnUser", rs.getString("turn_user"));
            row.put("turnActions", rs.getString("turn_actions"));
            row.put("turnOutcome", rs.getString("turn_outcome"));
            row.put("turnLearned", rs.getString("turn_learned"));
            return row;
        }, agentId, turnMessageId);
    }

    /** 15. 列出某 agent 的所有 turn（layer=session AND operation=WRITE 的主事件，排除 action 子事件）。
     *  sessionId 可空：为空则返回 agent 下全部 turn。 */
    public List<Map<String, Object>> listTurns(String agentId, String sessionId) {
        return listTurns(agentId, sessionId, null);
    }

    /** 15. 列出某 agent 的所有 turn（layer=session AND operation=WRITE 的主事件，排除 action 子事件）。
     *  sessionId 可空：为空则返回 agent 下全部 turn。
     *  keyword 可空：非空时对 event_id/session_id/memory_key/memory_summary/用户问题/操作步骤做模糊匹配。 */
    public List<Map<String, Object>> listTurns(String agentId, String sessionId, String keyword) {
        boolean hasSess = sessionId != null && !sessionId.isBlank();
        String sessCond = hasSess ? " AND session_id = ?" : "";
        List<Object> tArgs = new ArrayList<>();
        tArgs.add(agentId);
        if (hasSess) tArgs.add(sessionId);
        String kwCond = "";
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + escapeLike(keyword) + "%";
            kwCond = " AND (event_id ILIKE ? OR session_id ILIKE ? OR memory_key ILIKE ? OR memory_summary ILIKE ?"
                    + " OR COALESCE(metadata->>'turn_user','') ILIKE ?"
                    + " OR COALESCE(metadata->>'turn_actions','') ILIKE ?"
                    + " OR COALESCE(metadata->>'turn_outcome','') ILIKE ?)";
            for (int i = 0; i < 7; i++) tArgs.add(pattern);
        }

        String sql = "SELECT event_id, agent_id, session_id, memory_key, memory_summary, " +
                "token_count, latency_ms, ts, metadata->>'turn_message_id' AS turn_msg_id, " +
                "metadata->>'turn_outcome' AS turn_outcome_preview, " +
                "metadata->>'turn_user' AS turn_user, " +
                "metadata->>'turn_actions' AS turn_actions, " +
                "metadata->>'action_count' AS action_count " +
                "FROM memory_events WHERE agent_id = ?" + sessCond + kwCond +
                " AND layer = 'session' AND operation = 'WRITE' " +
                "AND metadata->>'action_idx' IS NULL " +
                "ORDER BY ts ASC";
        List<Map<String, Object>> turns = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", rs.getString("event_id"));
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("memorySummary", rs.getString("memory_summary"));
            row.put("tokenCount", rs.getInt("token_count"));
            row.put("latencyMs", rs.getDouble("latency_ms"));
            row.put("ts", rs.getTimestamp("ts").toInstant().toString());
            row.put("turnMessageId", rs.getString("turn_msg_id"));
            row.put("turnUser", rs.getString("turn_user"));
            row.put("turnActions", rs.getString("turn_actions"));
            row.put("turnOutcomePreview", rs.getString("turn_outcome_preview"));
            row.put("actionCount", rs.getString("action_count"));
            return row;
        }, tArgs.toArray());
        // 批量查出该范围 action 子事件，按 turn_message_id 分组后挂到对应 turn 上（供前端操作步骤明细展示）。
        // 注意：action 事件不受 keyword 过滤，保证命中 turn 仍返回完整操作步骤。
        List<Object> actArgs = new ArrayList<>();
        actArgs.add(agentId);
        if (hasSess) actArgs.add(sessionId);
        String actSql = "SELECT event_id, operation, layer, memory_summary, token_count, latency_ms, ts, " +
                "metadata->>'turn_message_id' AS turn_msg_id, " +
                "metadata->>'action_idx' AS action_idx, " +
                "metadata->>'action_full' AS action_full " +
                "FROM memory_events WHERE agent_id = ?" + sessCond +
                " AND metadata->>'turn_message_id' IS NOT NULL " +
                "AND metadata->>'action_idx' IS NOT NULL ORDER BY ts ASC";
        List<Map<String, Object>> acts = jdbc.query(actSql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventId", rs.getString("event_id"));
            row.put("operation", rs.getString("operation"));
            row.put("layer", rs.getString("layer"));
            row.put("memorySummary", rs.getString("memory_summary"));
            row.put("tokenCount", rs.getInt("token_count"));
            row.put("latencyMs", rs.getDouble("latency_ms"));
            row.put("ts", rs.getTimestamp("ts").toInstant().toString());
            row.put("turnMessageId", rs.getString("turn_msg_id"));
            row.put("actionIdx", rs.getString("action_idx"));
            row.put("actionFull", rs.getString("action_full"));
            return row;
        }, actArgs.toArray());
        Map<String, List<Map<String, Object>>> byTurn = new LinkedHashMap<>();
        for (Map<String, Object> a : acts) {
            Object tid = a.get("turnMessageId");
            byTurn.computeIfAbsent(tid == null ? "" : String.valueOf(tid), k -> new ArrayList<>()).add(a);
        }
        for (Map<String, Object> t : turns) {
            Object tid = t.get("turnMessageId");
            t.put("actions", byTurn.getOrDefault(tid == null ? "" : String.valueOf(tid), java.util.Collections.emptyList()));
        }
        // 去重：同一 turn_message_id（同一 Turn）可能有多条 session/WRITE 主事件（重复上报）。
        // 非空 mid 只保留最早一条（ts 升序下的第一条），并把重复事件的 token 合并；空 mid（未关联 Turn）各自保留。
        List<Map<String, Object>> deduped = new ArrayList<>();
        Map<String, Map<String, Object>> byMid = new LinkedHashMap<>();
        for (Map<String, Object> t : turns) {
            Object mid = t.get("turnMessageId");
            if (mid == null || String.valueOf(mid).isBlank()) { deduped.add(t); continue; }
            String key = String.valueOf(mid);
            Map<String, Object> first = byMid.get(key);
            if (first == null) { byMid.put(key, t); deduped.add(t); }
            else {
                long extra = ((Number) t.get("tokenCount")).intValue();
                first.put("tokenCount", ((Number) first.get("tokenCount")).intValue() + extra);
            }
        }
        return deduped;
    }

    /** 把用户输入里的 LIKE 通配符转义成普通字符，避免模糊查询被通配符干扰。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Turn Token 热力图：给定 agent + session，按 turn_message_id 返回行=Turn 的 token 数据。
     *  每根柱 = 该 Turn 全部事件（主事件 + action 子事件，各层/各操作）的 token 合计，按最早 ts 升序。 */
    public Map<String, Object> turnHeatmap(String agentId, String sessionId, int buckets) {
        // 每根柱对应一个唯一 Turn（turn_message_id）。一个 Turn 包含主事件 + 各 action 子事件，
        // token 取该 Turn 全部事件（各层/各操作）的 token 合计，时间取最早一条，user/outcome 取主事件。
        String sql = "SELECT metadata->>'turn_message_id' AS mid, "
                + "MAX(metadata->>'turn_user') AS usr, MAX(metadata->>'turn_outcome') AS outc, "
                + "SUM(token_count) AS tokens, MIN(ts) AS ts, MIN(EXTRACT(EPOCH FROM ts) * 1000) AS tsms "
                + "FROM memory_events WHERE agent_id = ? AND session_id = ? "
                + "AND metadata->>'turn_message_id' IS NOT NULL "
                + "GROUP BY metadata->>'turn_message_id' "
                + "ORDER BY ts ASC";
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, n) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("mid", rs.getString("mid"));
            r.put("usr", rs.getString("usr"));
            r.put("outc", rs.getString("outc"));
            r.put("tokens", rs.getInt("tokens"));
            r.put("ts", rs.getTimestamp("ts").toInstant().toString());
            r.put("tsMs", rs.getLong("tsms"));
            return r;
        }, agentId, sessionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentId", agentId);
        out.put("sessionId", sessionId);
        out.put("turnCount", 0);
        out.put("totalTokens", 0L);
        out.put("bucketCount", buckets);
        out.put("start", null);
        out.put("end", null);
        out.put("buckets", List.of());
        out.put("turns", List.of());
        if (rows.isEmpty()) return out;

        long startTs = (long) rows.get(0).get("tsMs");
        long endTs = (long) rows.get(rows.size() - 1).get("tsMs");
        long span = Math.max(endTs - startTs, 1);

        java.time.ZoneId zid = java.time.ZoneId.of("Asia/Shanghai");
        java.time.format.DateTimeFormatter tf = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zid);
        java.time.Instant s0 = java.time.Instant.ofEpochMilli(startTs);

        List<Map<String, Object>> bucketsList = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            java.time.Instant b = s0.plusMillis(span * i / buckets);
            bucketsList.add(Map.of("label", tf.format(b)));
        }

        long total = 0;
        for (Map<String, Object> r : rows) {
            long ts = (long) r.get("tsMs");
            int bi = (int) ((ts - startTs) * buckets / span);
            bi = Math.min(bi, buckets - 1);
            r.put("bucket", bi);
            total += ((Number) r.get("tokens")).longValue();
        }

        out.put("start", rows.get(0).get("ts"));
        out.put("end", rows.get(rows.size() - 1).get("ts"));
        out.put("turnCount", rows.size());
        out.put("totalTokens", total);
        out.put("buckets", bucketsList);
        out.put("turns", rows);
        return out;
    }

    /** 15b. 事件详情页统计：某查询范围（agent [+ session]）内事件总数与 Token 消耗合计。 */
    public Map<String, Object> turnStats(String agentId, String sessionId) {
        boolean hasSess = sessionId != null && !sessionId.isBlank();
        String sessCond = hasSess ? " AND session_id = ?" : "";
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (hasSess) args.add(sessionId);
        String sql = "SELECT COUNT(*) AS cnt, COALESCE(SUM(token_count),0) AS tokens " +
                "FROM memory_events WHERE agent_id = ?" + sessCond;
        Map<String, Object> row = jdbc.queryForMap(sql, args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", ((Number) row.get("cnt")).longValue());
        out.put("totalTokens", ((Number) row.get("tokens")).longValue());
        return out;
    }

    // ==================== Agent 分析 ====================

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

    // ==================== Skill 分析 ====================

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

    // ==================== 问题分析 · Threshold 聚合 ====================

    /** Agent 过滤子句与参数，agentId 可空。前置必须有 WHERE 条件。 */
    private static void appendAgent(StringBuilder sql, List<Object> args, String agentId) {
        if (agentId != null && !agentId.isBlank()) { sql.append(" AND agent_id = ?"); args.add(agentId); }
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

    /** 技能调用事件的公共过滤：仅统计真正的「调用」事件（排除 :result / :end / denied 派生行）。 */
    private static final String SKILL_CALL_FILTER =
            " layer = 'skill' AND memory_key LIKE 'tool:%' " +
            "AND memory_key NOT LIKE 'tool:%:result' AND memory_key NOT LIKE 'tool:%:end' " +
            "AND memory_key <> 'tool:denied'";

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

    // ==================== 流程分析（Flow Analysis，docs/流程分析功能设计.md）====================

    /** 流程分析只关注记忆生命周期层（与设计稿 16 节点矩阵一致，排除 model 等非记忆层）。 */
    private static final String FLOW_LAYERS =
            " AND layer IN ('prompt','session','skill','provider') " +
            "AND operation IN ('read','write','update','expire','READ','WRITE','UPDATE','EXPIRE')";

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

    // ==================== 问题分析 · Harness 维度（H 组）====================

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

    // ==================== Trace 查询（input-contracts §6.4）====================

    /** 23. 拉取某个 trace 下的所有 flat spans（按 start_ts 升序）。
     *  返回字段对齐 Trace API 响应体 spans[] 结构。 */
    public List<Map<String, Object>> queryTraceSpans(String traceId) {
        String sql = """
                SELECT event_id        AS span_id,
                       parent_span_id,
                       agent_id,
                       session_id,
                       operation       AS event_type,
                       layer,
                       COALESCE(memory_key, operation) AS memory_key,
                       memory_summary,
                       token_count,
                       COALESCE(NULLIF(latency_ms, 0), 0) AS duration_ms,
                       ts              AS start_ts,
                       metadata
                FROM memory_events
                WHERE trace_id = ?
                ORDER BY ts ASC
                """;
        return jdbc.query(sql, (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("spanId", rs.getString("span_id"));
            row.put("parentSpanId", rs.getString("parent_span_id"));
            row.put("agentId", rs.getString("agent_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("eventType", rs.getString("event_type"));
            row.put("layer", rs.getString("layer"));
            row.put("memoryKey", rs.getString("memory_key"));
            row.put("memorySummary", rs.getString("memory_summary"));
            row.put("tokenCount", rs.getInt("token_count"));
            row.put("durationMs", rs.getDouble("duration_ms"));
            row.put("startTs", rs.getTimestamp("start_ts").toInstant().toString());
            // name：用 layer:event_type:memory_key 组合，方便瀑布图显示
            String name = (rs.getString("layer") == null ? "" : rs.getString("layer"))
                    + ":" + (rs.getString("event_type") == null ? "event" : rs.getString("event_type").toLowerCase());
            String mk = rs.getString("memory_key");
            if (mk != null && !mk.isBlank() && !mk.equals(rs.getString("event_type"))) {
                name += ":" + shorten(mk, 40);
            }
            row.put("name", name);
            // metadata 解析
            Object metaObj = rs.getObject("metadata");
            Map<String, String> meta = new LinkedHashMap<>();
            if (metaObj != null) {
                try { meta = MAPPER.readValue(metaObj.toString(), Map.class); }
                catch (Exception ignored) {}
            }
            row.put("metadata", meta);
            return row;
        }, traceId);
    }

    /** 24. Agent 维度 Trace 列表（按最早事件时间倒序，分页 + 可选 session 过滤）。
     *  每条汇总：trace_id / session_id / span_count / turn_count / total_duration / failed_count */
    public List<Map<String, Object>> queryAgentTraces(String agentId, String sessionId,
                                                      int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT trace_id,
                       MAX(session_id)                                      AS session_id,
                       COUNT(*)                                             AS span_count,
                       COUNT(DISTINCT COALESCE(metadata->>'turn_message_id',
                                       metadata->>'turn_index',
                                       session_id || ':0'))  AS turn_count,
                       COALESCE(SUM(CASE WHEN latency_ms > 0 THEN latency_ms END), 0) AS total_duration,
                       COUNT(*) FILTER (WHERE
                           metadata->>'status' = 'failed'
                           OR metadata->>'error'  IS NOT NULL)               AS failed_count,
                       COALESCE(SUM(token_count), 0)                        AS total_tokens,
                       MIN(ts)                                              AS first_ts,
                       MAX(ts)                                              AS last_ts
                FROM memory_events
                WHERE agent_id = ? AND trace_id IS NOT NULL
                """);
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        sql.append(" GROUP BY trace_id ORDER BY last_ts DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("traceId", rs.getString("trace_id"));
            row.put("sessionId", rs.getString("session_id"));
            row.put("spanCount", rs.getLong("span_count"));
            row.put("turnCount", rs.getLong("turn_count"));
            row.put("totalDurationMs", rs.getDouble("total_duration"));
            row.put("failedCount", rs.getLong("failed_count"));
            row.put("totalTokens", rs.getLong("total_tokens"));
            row.put("firstTs", rs.getTimestamp("first_ts").toInstant().toString());
            row.put("lastTs", rs.getTimestamp("last_ts").toInstant().toString());
            return row;
        }, args.toArray());
    }

    /** 25. 从 event_id 反查 trace_id（用于 /events/{id}/trace 跳转）。 */
    public String findTraceIdByEventId(String eventId) {
        String sql = "SELECT trace_id FROM memory_events WHERE event_id = ?";
        List<String> r = jdbc.query(sql, (rs, n) -> rs.getString("trace_id"), eventId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 统计辅助：从字符串中间截断到 maxLen，用省略号表示。 */
    private static String shorten(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        int head = (maxLen - 3) / 2;
        int tail = maxLen - 3 - head;
        return s.substring(0, head) + "..." + s.substring(s.length() - tail);
    }
}
