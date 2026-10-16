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
 * {@link EventRepository} 按域拆出的协作类（Trace 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class TraceStore extends SqlRepositoryBase {

    TraceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

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

}
