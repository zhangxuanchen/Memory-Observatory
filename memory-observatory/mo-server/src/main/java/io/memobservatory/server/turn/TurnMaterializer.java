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
 *    候选另加一个反兑源（十万级压测发现③，2026-10-12）：有事件但从未物化过的 turn
 *    不论 ts 多老都进候选——否则导入器回填历史（ts=事件时刻，落在水位之前）会永久
 *    漏物化。物化后反连接自然为空，每轮自净化；代价是一遍全表 DISTINCT（万级 70ms）。
 *
 * 判据列（{@code corrected} / {@code is_holdout} / {@code task_type} / …）本类不碰，也已不在本表上——
 * 它们属于自校准侧（§2.4 隐式纠正等），已迁至外骨骼自建的 {@code memory_turn_criteria}（M3 步骤 15）。
 * 本类只写物化列，故物化重跑不会覆盖任何判定结果。
 */
@Component
public class TurnMaterializer {

    private static final Logger log = LoggerFactory.getLogger(TurnMaterializer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 来源家族归类：从 event_source_family 表装载，新增平台零改 Java。 */
    private final SourceFamilyClassifier classifier;

    @Value("${mo.turn.materialize-enabled:true}")
    private boolean enabled;
    @Value("${mo.turn.materialize-interval-ms:60000}")
    private long intervalMs;
    /** 回看重叠：晚到的行可能落在水位之前，回看一段把已写过的 turn 重新聚合一遍。 */
    @Value("${mo.turn.materialize-overlap-minutes:10}")
    private int overlapMinutes;

    /** 已物化到的时刻。首次为 EPOCH，等价于全量回填；重启后从库里接（见
     *  {@link #ensureWatermarkRestored()}），不再每次全量回看——架构审视弱点 5。 */
    private final AtomicReference<Instant> watermark = new AtomicReference<>(Instant.EPOCH);
    /** 水位是否已从库接续过（无行=首次也算接完）。库故障时保持 false，逐轮重试。 */
    private volatile boolean watermarkRestored;
    private ScheduledExecutorService scheduler;

    public TurnMaterializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.classifier = new SourceFamilyClassifier(jdbc);
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
        ensureWatermarkRestored();
        Instant since = watermark.get().minus(Duration.ofMinutes(overlapMinutes));
        try {
            List<TurnRow> rows = loadCandidates(since);
            int written = upsert(rows);
            watermark.set(runAt);
            persistWatermark(runAt);
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
    // 水位持久化（memory_materialize_watermark，本类唯一写者）
    // ------------------------------------------------------------------

    /** 本类负责的物化线主键（水位表预留多条管线，目前只有 turns）。 */
    static final String PIPELINE_TURNS = "turns";

    static final String SQL_WATERMARK_LOAD =
            "SELECT watermark_ts FROM memory_materialize_watermark WHERE pipeline = ?";

    /** 幂等 upsert：同一管线一格，只推进不重写——落库失败/重启竞态都安全。 */
    static final String SQL_WATERMARK_SAVE = """
            INSERT INTO memory_materialize_watermark (pipeline, watermark_ts, updated_ts)
            VALUES (?, ?, now())
            ON CONFLICT (pipeline) DO UPDATE
            SET watermark_ts = EXCLUDED.watermark_ts, updated_ts = now()""";

    /** 首轮从库接水位：有值→接续（只前不 rewind）；无行→首次，维持 EPOCH 全量回填；
     *  库不可达→本轮退回内存值（最差 EPOCH 全扫，幂等兑底）且下轮继续重试。
     *  包内可见供测试直接驱动。 */
    void ensureWatermarkRestored() {
        if (watermarkRestored) {
            return;
        }
        try {
            List<java.sql.Timestamp> rows =
                    jdbc.queryForList(SQL_WATERMARK_LOAD, java.sql.Timestamp.class, PIPELINE_TURNS);
            watermarkRestored = true;
            if (!rows.isEmpty() && rows.get(0) != null) {
                Instant persisted = rows.get(0).toInstant();
                if (persisted.isAfter(watermark.get())) {
                    watermark.set(persisted);
                }
                log.info("turn 物化：从库接续水位 {}", persisted);
            } else {
                log.info("turn 物化：无持久化水位（首次），本轮全量回填");
            }
        } catch (Exception e) {
            log.warn("turn 物化：读取水位失败（本轮按内存值 {} 兑全量，下轮重试）: {}",
                    watermark.get(), e.toString());
        }
    }

    /** 写路成功后把水位落库。失败仅 WARN：内存水位照常推进，重启至多回到旧水位重跑（幂等安全）。 */
    void persistWatermark(Instant ts) {
        try {
            jdbc.update(SQL_WATERMARK_SAVE, PIPELINE_TURNS, java.sql.Timestamp.from(ts));
        } catch (Exception e) {
            log.warn("turn 物化：水位落库失败（不影响本轮，重启后按旧水位重跑）：{}", e.toString());
        }
    }

    /** 供测试/诊断：当前内存水位。 */
    Instant currentWatermark() {
        return watermark.get();
    }

    /** 供测试/诊断：水位是否已从库接续过。 */
    boolean watermarkRestored() {
        return watermarkRestored;
    }

    // ------------------------------------------------------------------
    // 取数：候选 turn → 该 turn 的全量事件聚合
    // ------------------------------------------------------------------

    /**
     * 一轮聚合。cand 定候选 turn（按水位），ev 取候选 turn 的全部事件。
     * layer_mix 记事件数、op_mix 记占比（§4.2 列注释），两者都在子查询里先分组再聚成 jsonb。
     *
     * <p>本 SQL 只取 {@code MAX(meta_source)} 原始平台名，不再在 SQL 里判家族——家族归类交给
     * {@link SourceFamilyClassifier}（查 {@code event_source_family}），使新增平台零改 SQL。
     *
     * <p>包内可见（非 private）以便 {@code TurnMaterializerSourceTest} 断言「SQL 里不再有
     * 硬编码家族清单」。
     */
    static final String SQL_LOAD = """
            WITH cand AS (
                SELECT DISTINCT metadata->>'turn_message_id' AS turn_id
                FROM memory_events
                WHERE ts >= ?
                  AND COALESCE(metadata->>'turn_message_id', '') <> ''
                UNION
                -- 回填兑底（压测发现③）：有事件但从未进过 turns 的 turn，不论 ts 多老。
                SELECT DISTINCT e.metadata->>'turn_message_id'
                FROM memory_events e
                WHERE COALESCE(e.metadata->>'turn_message_id', '') <> ''
                  AND NOT EXISTS (SELECT 1 FROM memory_turns t
                                  WHERE t.turn_id = e.metadata->>'turn_message_id')
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
                   -- 平台原始 source 值（qoder_hook / trae_importer / …）。归到哪个家族由 Java 侧
                   -- SourceFamilyClassifier 查 event_source_family 表决定——新增平台只需运行时
                   -- INSERT 一行，不必改这条 SQL、不必重建镜像。
                   MAX(e.meta_source)                     AS meta_source
            FROM ev e
            LEFT JOIN layer_mix   lm  ON lm.turn_id  = e.turn_id
            LEFT JOIN op_mix      om  ON om.turn_id  = e.turn_id
            LEFT JOIN io_kind_mix ikm ON ikm.turn_id = e.turn_id
            GROUP BY e.turn_id, lm.m, om.m, ikm.m
            """;

    private List<TurnRow> loadCandidates(Instant since) {
        return jdbc.query(SQL_LOAD, (rs, n) -> {
            String metaSource = rs.getString("meta_source");
            String userText = rs.getString("user_text");
            String turnActions = rs.getString("turn_actions");
            String source = classifier.classify(metaSource, userText != null, turnActions != null);
            return new TurnRow(
                    rs.getString("turn_id"),
                    rs.getString("agent_id"),
                    rs.getString("session_id"),
                    rs.getString("trace_id"),
                    userText,
                    turnActions,
                    rs.getString("outcome"),
                    rs.getObject("event_count", Integer.class),
                    rs.getObject("token_total", Long.class),
                    rs.getObject("latency_ms", Long.class),
                    rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                    rs.getString("layer_mix"),
                    rs.getString("op_mix"),
                    rs.getString("io_kind_mix"),
                    metaSource,
                    source);
        }, java.sql.Timestamp.from(since));
    }

    // ------------------------------------------------------------------
    // 写入：按 turn_id 幂等 upsert
    // 只写物化列；判据列（corrected / is_holdout / task_type / …）已迁至外骨骼的
    // memory_turn_criteria（M3 步骤 15），本类不碰，故物化重跑不会覆盖判定结果。
    // ------------------------------------------------------------------
    private static final String SQL_UPSERT = """
            INSERT INTO memory_turns (turn_id, agent_id, session_id, trace_id, user_text,
                action_seq, layer_mix, op_mix, io_kind_mix, event_count, token_total, latency_ms,
                outcome, source, platform, started_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
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
                platform    = EXCLUDED.platform,
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
            ps.setString(15, row.platform());
            if (row.startedAt() == null) {
                ps.setNull(16, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(16, java.sql.Timestamp.from(row.startedAt()));
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

    /** 一行待物化的 turn。platform 是平台原始 source，source 是归类后的家族。 */
    private record TurnRow(
            String turnId, String agentId, String sessionId, String traceId, String userText,
            String turnActions, String outcome, Integer eventCount, Long tokenTotal, Long latencyMs,
            Instant startedAt, String layerMix, String opMix, String ioKindMix,
            String platform, String source) {
    }
}
