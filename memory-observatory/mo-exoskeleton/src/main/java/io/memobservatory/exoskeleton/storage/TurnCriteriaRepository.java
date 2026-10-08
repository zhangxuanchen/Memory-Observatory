package io.memobservatory.exoskeleton.storage;

import io.memobservatory.exoskeleton.cluster.ShapeBucketer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 判据侧的 turn 取数与回写（三期 P0）。
 *
 * 与 {@link RuleRepository} 的分工：那边管「规则」，这边管「判据」。
 * memory_turns 是分析型访问的唯一入口（《分类记忆体》§4.2 零期），本类不再回 memory_events 现算 jsonb。
 *
 * 回写两列：
 * <ul>
 *   <li>{@code corrected}（§2.4 隐式纠正）——mo-server 建表时留出、TurnMaterializer 明确不填，等判据侧接管。</li>
 *   <li>{@code is_holdout}（§2.5 对照组）——本模块 schema.sql 用 ALTER 增列，TurnMaterializer 的 upsert 不碰它，
 *       故物化重跑不会覆盖判定结果。</li>
 * </ul>
 *
 * 一次 loadTurns 同时服务三件事：重算、对照分组、headline 纠正率——避免同一批数据被读三遍。
 */
@Repository
public class TurnCriteriaRepository {

    private final JdbcTemplate jdbc;

    public TurnCriteriaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个 turn 的判据侧视图。
     * steps 与 ioKinds 都取物化列，与 {@link RuleRepository#support()} 的分桶口径同源，
     * 否则「规则是从哪些桶起草的」与「哪些 turn 命中了规则」会用两套特征、静默对不上。
     */
    public record TurnRow(
            String turnId,
            String agentId,
            String sessionId,
            String userText,
            String source,
            String outcome,
            String[] actions,
            Instant startedAt,
            long tokenTotal,
            long latencyMs,
            Set<String> ioKinds,
            Long steps,
            boolean holdout,
            boolean corrected,
            String taskType
    ) {
    }

    /**
     * 全量读入。按 (session_id, started_at) 排序——隐式纠正只在同 session 相邻 turn 之间判定，
     * 排序放在 SQL 里做，Java 侧只顺次扫一遍。
     *
     * <p>{@code taskTypeFilter} 为 null/空白时<b>不加任何谓词</b>，行为与加过滤前逐字一致；
     * 非空时只取该任务类型的 turn（task_type 是 session 粒度，故同一 session 的 turn 整批同值）。
     */
    private static final String SQL_TURNS = """
            SELECT turn_id, agent_id, session_id, user_text, source, outcome, action_seq,
                   started_at, token_total, latency_ms,
                   %s                     AS io_kinds,
                   cardinality(action_seq) AS steps,
                   is_holdout, corrected, task_type
            FROM memory_turns
            %s
            ORDER BY session_id, started_at NULLS LAST, turn_id
            """;

    public List<TurnRow> loadTurns() {
        return loadTurns(null);
    }

    public List<TurnRow> loadTurns(String taskTypeFilter) {
        String filter = taskTypeFilter == null ? "" : taskTypeFilter.strip();
        if (filter.isEmpty()) {
            return jdbc.query(SQL_TURNS.formatted(ShapeBucketer.SQL_IO_KINDS, ""), MAPPER);
        }
        return jdbc.query(SQL_TURNS.formatted(ShapeBucketer.SQL_IO_KINDS, "WHERE task_type = ?"),
                MAPPER, filter);
    }

    private static final RowMapper<TurnRow> MAPPER = (rs, n) -> {
        Timestamp ts = rs.getTimestamp("started_at");
        Object steps = rs.getObject("steps");
        return new TurnRow(
                rs.getString("turn_id"),
                rs.getString("agent_id"),
                rs.getString("session_id"),
                rs.getString("user_text"),
                rs.getString("source"),
                rs.getString("outcome"),
                textArray(rs.getArray("action_seq")),
                ts == null ? null : ts.toInstant(),
                rs.getLong("token_total"),
                rs.getLong("latency_ms"),
                ShapeBucketer.kindsOf(rs.getArray("io_kinds")),
                steps == null ? null : ((Number) steps).longValue(),
                rs.getBoolean("is_holdout"),
                rs.getBoolean("corrected"),
                rs.getString("task_type"));
    };

    private static String[] textArray(Array arr) {
        if (arr == null) {
            return new String[0];
        }
        try {
            Object v = arr.getArray();
            if (v instanceof String[] s) {
                return s;
            }
            if (v instanceof Object[] o) {
                String[] out = new String[o.length];
                for (int i = 0; i < o.length; i++) {
                    out[i] = o[i] == null ? null : String.valueOf(o[i]);
                }
                return out;
            }
            return new String[0];
        } catch (Exception e) {
            return new String[0];   // 脏值不打断整批判定
        }
    }

    // ------------------------------------------------------------------
    // 回写
    // ------------------------------------------------------------------

    private static final String SQL_UPDATE = """
            UPDATE memory_turns SET corrected = ?, is_holdout = ? WHERE turn_id = ?
            """;

    /** 一条待回写的判定。 */
    public record Score(String turnId, boolean corrected, boolean holdout) {
    }

    public int writeBack(List<Score> scores) {
        if (scores.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(scores.size());
        for (Score s : scores) {
            args.add(new Object[]{s.corrected(), s.holdout(), s.turnId()});
        }
        int sum = 0;
        for (int a : jdbc.batchUpdate(SQL_UPDATE, args)) {
            sum += Math.max(a, 0);
        }
        return sum;
    }
}
