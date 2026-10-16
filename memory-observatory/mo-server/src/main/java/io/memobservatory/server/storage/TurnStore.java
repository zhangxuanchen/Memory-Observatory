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
 * {@link EventRepository} 按域拆出的协作类（Turn 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class TurnStore extends SqlRepositoryBase {

    TurnStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
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
                "metadata->>'turn_learned' AS turn_learned, " +
                "metadata->>'source' AS source " +
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
            // 导入来源标识：供前端在无提问原文时如实标注，不回落命令串
            row.put("source", rs.getString("source"));
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
                "metadata->>'action_count' AS action_count, " +
                "metadata->>'source' AS source " +
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
            // 导入来源标识（trae_importer / workbuddy_importer 等）：供前端在无提问原文时如实标注，不回落命令串
            row.put("source", rs.getString("source"));
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

    /**
     * 取某个 turn 的文本上下文（用户输入 + 该 turn 内事件摘要拼接）。
     *
     * <p>问题分析的命中行（{@code turnHit} 与各 repo 聚合行）只有数字指标，没有可判断的内容；
     * 语义确认必须先把内容捞出来，否则 laya 看不到任何文本。取不到时返回空 Map，
     * 调用方据此跳过该候选（保留，不猜）。
     */
    public Map<String, String> queryTurnText(String agentId, String sessionId, String turnId) {
        if (agentId == null || agentId.isBlank() || turnId == null || turnId.isBlank()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT MAX(metadata->>'turn_user') AS turn_user, " +
                "string_agg(DISTINCT NULLIF(memory_summary, ''), ' | ') AS summaries " +
                "FROM memory_events WHERE agent_id = ? AND metadata->>'turn_message_id' = ?");
        List<Object> args = new ArrayList<>();
        args.add(agentId);
        args.add(turnId);
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        return jdbc.query(sql.toString(), rs -> {
            if (!rs.next()) {
                return Map.of();
            }
            Map<String, String> m = new LinkedHashMap<>();
            String user = rs.getString("turn_user");
            String sums = rs.getString("summaries");
            m.put("turnUser", user == null ? "" : user);
            m.put("summary", sums == null ? "" : sums);
            return m;
        }, args.toArray());
    }

}
