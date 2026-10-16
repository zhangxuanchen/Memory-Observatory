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
 * {@link EventRepository} 按域拆出的协作类（Token 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class TokenAnalyticsStore extends SqlRepositoryBase {

    TokenAnalyticsStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
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

}
