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
 * 取数入口是 {@code memory_turns}（分析型只读，见《分类记忆体》§4.2 零期），判据回写落本模块自建的
 * {@code memory_turn_criteria}（以 turn_id 关联）——本类不再写 {@code memory_turns} 的任何列。
 *
 * <p><b>判据列已迁出 memory_turns</b>（架构重构 M3 / 步骤 15，治 R2：memory_turns 双写）：
 * 回写两列 {@code corrected}（§2.4 隐式纠正）与 {@code is_holdout}（§2.5 对照组）现写入
 * {@code memory_turn_criteria}，{@code turn_id / session_id} 由 {@code memory_turns} 带出——
 * 读 memory_turns 是允许的（owner 建表），但本模块只读写自己的判据表，使 memory_turns 彻底单写。
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
            SELECT t.turn_id, t.agent_id, t.session_id, t.user_text, t.source, t.outcome, t.action_seq,
                   t.started_at, t.token_total, t.latency_ms,
                   %s                          AS io_kinds,
                   cardinality(t.action_seq)   AS steps,
                   COALESCE(c.is_holdout, false) AS is_holdout,
                   COALESCE(c.corrected, false)  AS corrected,
                   c.task_type
            FROM memory_turns t
            LEFT JOIN memory_turn_criteria c ON c.turn_id = t.turn_id
            %s
            ORDER BY t.session_id, t.started_at NULLS LAST, t.turn_id
            """;

    public List<TurnRow> loadTurns() {
        return loadTurns(null);
    }

    public List<TurnRow> loadTurns(String taskTypeFilter) {
        String filter = taskTypeFilter == null ? "" : taskTypeFilter.strip();
        if (filter.isEmpty()) {
            return jdbc.query(SQL_TURNS.formatted(ShapeBucketer.SQL_IO_KINDS, ""), MAPPER);
        }
        return jdbc.query(SQL_TURNS.formatted(ShapeBucketer.SQL_IO_KINDS, "WHERE c.task_type = ?"),
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

    /**
     * 判据回写：upsert 自有表 {@code memory_turn_criteria} 的 {@code corrected} / {@code is_holdout}。
     * {@code turn_id / session_id} 从 {@code memory_turns} 带出（读允许，写禁止）；ON CONFLICT 保证
     * 只更新这两列与 {@code updated_at}，不触碰同表的 task_type 等分类列（写路径由 {@code TaskTypeRepository} 负责）。
     */
    private static final String SQL_UPDATE = """
            INSERT INTO memory_turn_criteria (turn_id, session_id, corrected, is_holdout, updated_at)
            SELECT t.turn_id, t.session_id, ?, ?, now() FROM memory_turns t WHERE t.turn_id = ?
            ON CONFLICT (turn_id) DO UPDATE SET
                corrected  = EXCLUDED.corrected,
                is_holdout = EXCLUDED.is_holdout,
                updated_at = now()
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
