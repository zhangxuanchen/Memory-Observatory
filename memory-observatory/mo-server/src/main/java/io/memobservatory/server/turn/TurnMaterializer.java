package io.memobservatory.server.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * turn 特征物化（《分类记忆体：设计与实现》§4.2 零期）。
 *
 * 把 memory_events 里同一个 turn 的事件聚合成一行写进 memory_turns，作为分析型访问的唯一入口，
 * 替代每次按 {@code metadata->>'k'} 现算的 jsonb 全表扫。
 *
 * 两条约束：
 * 1. <b>幂等可重放</b>：{@code ON CONFLICT (turn_id) DO UPDATE}，重复跑结果一致（§4.2.1）。
 * 2. <b>增量而非全量</b>：每轮先找出「有事件的 ts 落在水位之后」的 turn（候选），
 *    再对候选 turn 的<b>全部</b>事件重新聚合。只按水位过滤事件会让跨水位的 turn 计数偏小，
 *    所以必须先定候选 turn、再聚合该 turn 的全量事件。
 *
 * {@code corrected} 列本类不填。它的判据属于自校准侧（§2.4 隐式纠正）——两处各写一套相似度
 * 会得到两个真相来源，故留 schema 默认 FALSE，等判据侧接管。
 */
@Component
public class TurnMaterializer {

    private static final Logger log = LoggerFactory.getLogger(TurnMaterializer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${mo.turn.materialize-enabled:true}")
    private boolean enabled;
    @Value("${mo.turn.materialize-interval-ms:60000}")
    private long intervalMs;
    /** 回看重叠：晚到的行可能落在水位之前，回看一段把已写过的 turn 重新聚合一遍。 */
    @Value("${mo.turn.materialize-overlap-minutes:10}")
    private int overlapMinutes;

    /** 已物化到的时刻。首次为 EPOCH，等价于全量回填。 */
    private final AtomicReference<Instant> watermark = new AtomicReference<>(Instant.EPOCH);
    private ScheduledExecutorService scheduler;

    public TurnMaterializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("turn 物化未启用（mo.turn.materialize-enabled=false）");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mo-turn-materializer");
            t.setDaemon(true);
            return t;
        });
        // 启动即跑一轮：首次等价于从零回填，否则 memory_turns 会一直空着。
        scheduler.scheduleWithFixedDelay(this::runOnce, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("turn 物化启动 intervalMs={} overlapMinutes={}", intervalMs, overlapMinutes);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 跑一轮。异常只记日志不抛出——后台任务失败不应影响主流程。
     *
     * @return 本轮写入/更新的 turn 数
     */
    public int runOnce() {
        Instant runAt = Instant.now();
        Instant since = watermark.get().minus(Duration.ofMinutes(overlapMinutes));
        try {
            List<TurnRow> rows = loadCandidates(since);
            int written = upsert(rows);
            watermark.set(runAt);
            if (written > 0) {
                log.info("turn 物化完成：{} 个 turn（自 {} 起，水位推进到 {}）", written, since, runAt);
            }
            return written;
        } catch (Exception e) {
            log.warn("turn 物化失败（下轮重试）：{}", e.toString());
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 取数：候选 turn → 该 turn 的全量事件聚合
    // ------------------------------------------------------------------

    /**
     * 一轮聚合。cand 定候选 turn（按水位），ev 取候选 turn 的全部事件。
     * layer_mix 记事件数、op_mix 记占比（§4.2 列注释），两者都在子查询里先分组再聚成 jsonb。
     */
    private static final String SQL_LOAD = """
            WITH cand AS (
                SELECT DISTINCT metadata->>'turn_message_id' AS turn_id
                FROM memory_events
                WHERE ts >= ?
                  AND COALESCE(metadata->>'turn_message_id', '') <> ''
            ),
            ev AS (
                SELECT metadata->>'turn_message_id' AS turn_id,
                       agent_id, session_id, trace_id, layer, operation,
                       token_count, latency_ms, ts,
                       metadata->>'turn_user'    AS turn_user,
                       metadata->>'turn_actions' AS turn_actions,
                       metadata->>'turn_outcome' AS outcome,
                       metadata->>'source'       AS meta_source,
                       metadata->>'io_kind'      AS io_kind
                FROM memory_events
                WHERE metadata->>'turn_message_id' IN (SELECT turn_id FROM cand)
            ),
            layer_mix AS (
                SELECT turn_id, jsonb_object_agg(layer, c) AS m
                FROM (SELECT turn_id, layer, COUNT(*) AS c FROM ev GROUP BY 1, 2) x
                GROUP BY turn_id
            ),
            op_mix AS (
                SELECT turn_id, jsonb_object_agg(lower(operation), c::float8 / t) AS m
                FROM (SELECT turn_id, operation, COUNT(*) AS c,
                             SUM(COUNT(*)) OVER (PARTITION BY turn_id) AS t
                      FROM ev GROUP BY 1, 2) y
                GROUP BY turn_id
            ),
            -- io_kind 是 B4 补的事件性质键（工作台中间件与导入器共同写）。
            -- 旧数据没有这个键，此时整列为 NULL——分桶侧据此区分「无从判断」与「判过且无读写」。
            io_kind_mix AS (
                SELECT turn_id, jsonb_object_agg(io_kind, c) AS m
                FROM (SELECT turn_id, io_kind, COUNT(*) AS c
                      FROM ev WHERE io_kind IS NOT NULL GROUP BY 1, 2) z
                GROUP BY turn_id
            )
            SELECT e.turn_id,
                   MIN(e.agent_id)                        AS agent_id,
                   MIN(e.session_id)                      AS session_id,
                   MIN(e.trace_id)                        AS trace_id,
                   LEFT(MAX(e.turn_user), 500)            AS user_text,
                   MAX(e.turn_actions)                    AS turn_actions,
                   MAX(e.outcome)                         AS outcome,
                   COUNT(*)::int                          AS event_count,
                   COALESCE(SUM(e.token_count), 0)::bigint AS token_total,
                   COALESCE(SUM(e.latency_ms), 0)::bigint  AS latency_ms,
                   MIN(e.ts)                              AS started_at,
                   lm.m                                   AS layer_mix,
                   om.m                                   AS op_mix,
                   ikm.m                                  AS io_kind_mix,
                   -- source 判定：导入家族必须显式列出。此前只认 'import'，漏掉了 trae_importer
                   -- （examples/trae_importer.py 写的是 metadata.source='trae_importer'），
                   -- 结果回灌历史因「有 turn_user」被误判成 workbench，使 J1 的量具分不开可测/不可测两条路。
                   -- 注意 metadata.source 在两条路上语义不同：导入器写「来源」，工作台写「子 Agent 路径」。
                   CASE WHEN BOOL_OR(e.meta_source IN ('import', 'trae_importer')) THEN 'import'
                        WHEN MAX(e.turn_user) IS NOT NULL
                          OR MAX(e.turn_actions) IS NOT NULL THEN 'workbench'
                        ELSE 'otlp' END                    AS source
            FROM ev e
            LEFT JOIN layer_mix   lm  ON lm.turn_id  = e.turn_id
            LEFT JOIN op_mix      om  ON om.turn_id  = e.turn_id
            LEFT JOIN io_kind_mix ikm ON ikm.turn_id = e.turn_id
            GROUP BY e.turn_id, lm.m, om.m, ikm.m
            """;

    private List<TurnRow> loadCandidates(Instant since) {
        return jdbc.query(SQL_LOAD, (rs, n) -> new TurnRow(
                rs.getString("turn_id"),
                rs.getString("agent_id"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("user_text"),
                rs.getString("turn_actions"),
                rs.getString("outcome"),
                rs.getObject("event_count", Integer.class),
                rs.getObject("token_total", Long.class),
                rs.getObject("latency_ms", Long.class),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getString("layer_mix"),
                rs.getString("op_mix"),
                rs.getString("io_kind_mix"),
                rs.getString("source")), java.sql.Timestamp.from(since));
    }

    // ------------------------------------------------------------------
    // 写入：按 turn_id 幂等 upsert
    // corrected 不在列里，故插入走默认 FALSE、更新时保持原值不动。
    // ------------------------------------------------------------------
    private static final String SQL_UPSERT = """
            INSERT INTO memory_turns (turn_id, agent_id, session_id, trace_id, user_text,
                action_seq, layer_mix, op_mix, io_kind_mix, event_count, token_total, latency_ms,
                outcome, source, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (turn_id) DO UPDATE SET
                agent_id    = EXCLUDED.agent_id,
                session_id  = EXCLUDED.session_id,
                trace_id    = EXCLUDED.trace_id,
                user_text   = EXCLUDED.user_text,
                action_seq  = EXCLUDED.action_seq,
                layer_mix   = EXCLUDED.layer_mix,
                op_mix      = EXCLUDED.op_mix,
                io_kind_mix = EXCLUDED.io_kind_mix,
                event_count = EXCLUDED.event_count,
                token_total = EXCLUDED.token_total,
                latency_ms  = EXCLUDED.latency_ms,
                outcome     = EXCLUDED.outcome,
                source      = EXCLUDED.source,
                started_at  = EXCLUDED.started_at
            """;

    private int upsert(List<TurnRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        int[][] affected = jdbc.batchUpdate(SQL_UPSERT, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.turnId());
            ps.setString(2, row.agentId());
            ps.setString(3, row.sessionId());
            ps.setString(4, row.traceId());
            ps.setString(5, row.userText());
            Array seq = ps.getConnection().createArrayOf("text", parseActions(row.turnActions()));
            ps.setArray(6, seq);
            ps.setString(7, row.layerMix());
            ps.setString(8, row.opMix());
            ps.setString(9, row.ioKindMix());
            ps.setInt(10, row.eventCount() == null ? 0 : row.eventCount());
            ps.setLong(11, row.tokenTotal() == null ? 0L : row.tokenTotal());
            ps.setLong(12, row.latencyMs() == null ? 0L : row.latencyMs());
            ps.setString(13, row.outcome());
            ps.setString(14, row.source());
            if (row.startedAt() == null) {
                ps.setNull(15, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(15, java.sql.Timestamp.from(row.startedAt()));
            }
        });
        int sum = 0;
        for (int[] batch : affected) {
            for (int a : batch) {
                sum += Math.max(a, 0);
            }
        }
        return sum == 0 ? rows.size() : sum;
    }

    /** turn_actions 是中间件写的 JSON 数组字符串；解析不出按「没有步骤」处理，不让脏值打断整批。 */
    private String[] parseActions(String json) {
        if (json == null || json.isBlank()) {
            return new String[0];
        }
        try {
            List<?> list = mapper.readValue(json, List.class);
            return list.stream().map(v -> v == null ? "" : String.valueOf(v)).toArray(String[]::new);
        } catch (Exception e) {
            log.warn("turn_actions 解析失败，按空步骤处理：{}", e.toString());
            return new String[0];
        }
    }

    /** 一行待物化的 turn。 */
    private record TurnRow(
            String turnId, String agentId, String sessionId, String traceId, String userText,
            String turnActions, String outcome, Integer eventCount, Long tokenTotal, Long latencyMs,
            Instant startedAt, String layerMix, String opMix, String ioKindMix, String source) {
    }
}
