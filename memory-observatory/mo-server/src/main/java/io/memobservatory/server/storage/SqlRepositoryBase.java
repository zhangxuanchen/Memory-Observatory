package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.model.MemoryEvent;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储层协作类的公共底座。
 *
 * <p>拆 {@code EventRepository} 前，这些 helper 与常量挤在同一个类里被 60 多个方法共用；
 * 按域抽出协作类后，它们沉到这里，各协作类继承即用。行为与原实现逐字一致——
 * 本类不含任何业务查询，只有时间绑定、jsonb 封装、结果映射这些通用件。
 *
 * <p>包内可见（非 {@code public}）：协作类与门面 {@link EventRepository} 同包，
 * 外部不应依赖这些通用件。
 */
abstract class SqlRepositoryBase {

    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;

    /** 静态复用：结果集里 metadata/trace 列的 JSON 解析，避免每行新建 ObjectMapper。 */
    static final ObjectMapper MAPPER = new ObjectMapper();

    SqlRepositoryBase(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** PG JDBC 不能直接绑定 Instant，统一转 Timestamp。 */
    static java.sql.Timestamp ts(Instant i) {
        return i == null ? null : java.sql.Timestamp.from(i);
    }

    /** 追加时间窗过滤条件（from/to 可空），与 args 保持顺序一致。 */
    static void appendTime(StringBuilder sql, List<Object> args, Instant from, Instant to) {
        if (from != null) { sql.append(" AND ts >= ?"); args.add(ts(from)); }
        if (to != null) { sql.append(" AND ts <= ?"); args.add(ts(to)); }
    }

    /** agentId 过滤子句，可空。前置必须已有 WHERE 条件。 */
    static void appendAgent(StringBuilder sql, List<Object> args, String agentId) {
        if (agentId != null && !agentId.isBlank()) { sql.append(" AND agent_id = ?"); args.add(agentId); }
    }

    /** 单列分组计数：SELECT key, count/sum ... GROUP BY key → Map<key, value>。 */
    protected Map<String, Long> groupQuery(String sql, List<Object> args) {
        return jdbc.query(sql, rs -> {
            Map<String, Long> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
            return result;
        }, args.toArray());
    }

    /** Map 序列化为 PG jsonb 参数；空或异常返回 null（列允许 NULL）。 */
    protected Object jsonb(Map<String, String> meta) {
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

    /** 把用户输入里的 LIKE 通配符转义成普通字符，避免模糊查询被通配符干扰。 */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 从字符串中间截断到 maxLen，用省略号表示。 */
    static String shorten(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        int head = (maxLen - 3) / 2;
        int tail = maxLen - 3 - head;
        return s.substring(0, head) + "..." + s.substring(s.length() - tail);
    }

    /** 事件行映射：容错缺列（老查询不 SELECT trace/metadata），metadata 走 Jackson。 */
    static final RowMapper<MemoryEvent> EVENT_MAPPER = (rs, n) -> {
        String traceId = null, parentSpanId = null;
        try { traceId = rs.getString("trace_id"); } catch (Exception ignored) {}
        try { parentSpanId = rs.getString("parent_span_id"); } catch (Exception ignored) {}
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

    /** 技能调用事件的公共过滤：仅统计真正的「调用」事件（排除 :result / :end / denied 派生行）。 */
    static final String SKILL_CALL_FILTER =
            " layer = 'skill' AND memory_key LIKE 'tool:%' " +
            "AND memory_key NOT LIKE 'tool:%:result' AND memory_key NOT LIKE 'tool:%:end' " +
            "AND memory_key <> 'tool:denied'";

    /** 流程分析只关注记忆生命周期层（与设计稿 16 节点矩阵一致，排除 model 等非记忆层）。 */
    static final String FLOW_LAYERS =
            " AND layer IN ('prompt','session','skill','provider') " +
            "AND operation IN ('read','write','update','expire','READ','WRITE','UPDATE','EXPIRE')";
}
