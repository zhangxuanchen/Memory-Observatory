package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemorySnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EventRepository SQL 层集成测试（真 PostgreSQL 直连）。
 *
 * <p><b>为什么必须连真库</b>：本类的 SQL 重度依赖 PG 专有语法——
 * {@code metadata->>}、{@code jsonb_object_agg}、{@code PERCENTILE_CONT}、
 * {@code ON CONFLICT DO NOTHING}、{@code FILTER (WHERE)}、{@code string_agg}。
 * H2 / 内存库都过不了这些方言，单元测试又触不到 JDBC 层，
 * 于是 SQL 退化（列名写错、占位符错位、聚合口径变了）没有任何拦截网。
 *
 * <p><b>为什么复用 compose 的 mo 库而不是 Testcontainers</b>：项目纪律零新依赖、
 * 本地 mvn -o 离线可跑、CI 无 Docker。代价是环境耦合，用两件事兜底：
 * ① 探活失败整类干净跳过（Assumptions），CI 与无库开发机不受影响；
 * ② 一切读写都限定 {@code itest-<运行后缀>} 前缀的 agent_id，测后物理删除，
 *    连在跑的服务器物化器可能顺带写进的 memory_turns 也一并清掉。
 *
 * <p>测试走 EventRepository 门面方法——后续按域拆分协作类时门面签名不变，
 * 本测试同时是拆分重构的安全网。数值断言全部钉死（token 和、分位数、
 * 聚合口径），任何 SQL 口径漂移都会在这里红。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventRepositoryIntegrationTest {

    /** 单次运行的唯一隔离前缀：并发跑测试 / 上次崩溃残留都不会互相污染。 */
    private static final String RUN = Long.toHexString(System.nanoTime());
    private static final String AGENT = "itest-" + RUN;
    private static final String SESSION_A = "itest-session-a";
    private static final String SESSION_B = "itest-session-b";
    // 列宽硬约束：trace_id varchar(32)、parent_span_id varchar(16)，超长直接 22000 报错
    private static final String TRACE_ID = pad("itest" + RUN, 32, '0');
    private static final String SPAN_ROOT = pad("itestroot" + RUN, 16, '0');
    private static final String SPAN_CHILD = pad("itestchild" + RUN, 16, '0');
    private static final String IMPORT_MD5 = "itest-md5-" + RUN;

    private static String pad(String s, int len, char c) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < len) b.append(c);
        return b.substring(0, len);
    }

    /** 种子事件总数与 Token 总和：与 seed() 逐条对账，改种子必须同步改这里。 */
    private static final int SEED_EVENTS = 19;
    private static final long SEED_TOKENS = 20_330L;
    // 对账（与库内实查一致）：session 800 + prompt 9250 + skill 880 + model 400 + provider 9000 = 20330；
    //       READ 9600 + WRITE 10680 + UPDATE 50 = 20330

    /** 查询窗口：覆盖种子数据（now-2h .. now-1min），又不撞全库历史噪音。 */
    private static final Instant FROM = Instant.now().minus(3, ChronoUnit.HOURS);
    private static final Instant TO = Instant.now().plus(1, ChronoUnit.MINUTES);

    private static JdbcTemplate jdbc;
    private static EventRepository repo;

    @BeforeEach
    void setUpAndSeed() {
        // 探活即门槛：连不上（compose 没起 / CI 无库）整类跳过，不算失败。
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(env("MO_DB_URL", "jdbc:postgresql://127.0.0.1:5432/mo"));
        ds.setUsername(env("MO_DB_USER", "mo"));
        ds.setPassword(env("MO_DB_PASSWORD", "mo"));
        try {
            ds.getConnection().close();
        } catch (Exception e) {
            Assumptions.abort("PostgreSQL 不可达（" + e.getMessage() + "），跳过集成测试");
        }
        jdbc = new JdbcTemplate(ds);
        repo = new EventRepository(jdbc, new ObjectMapper());
        // 每个测试独立重新播种：否则幂等测试插入的冲突行会污染后续汇总断言（方法执行顺序不定）
        seed();
    }

    @AfterEach
    void cleanSeed() {
        if (jdbc == null) return;
        wipe();
    }

    @AfterAll
    void tearDown() {
        if (jdbc == null) return;
        wipe();
    }

    private void wipe() {
        jdbc.update("DELETE FROM memory_events WHERE agent_id = ?", AGENT);
        jdbc.update("DELETE FROM memory_snapshots WHERE agent_id = ?", AGENT);
        // 在跑的服务器 TurnMaterializer 可能把种子事件物化进 turns，一并清掉。
        jdbc.update("DELETE FROM memory_turns WHERE agent_id = ?", AGENT);
        jdbc.update("DELETE FROM import_log_files WHERE md5 = ?", IMPORT_MD5);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ==================== 种子数据 ====================

    private void seed() {
        Instant base = Instant.now().minus(2, ChronoUnit.HOURS);

        // turn 组：主事件(layer=session + operation=WRITE，listTurns 的识别口径) + action 子事件
        repo.batchInsertEvents(List.of(
                event("itest-ev-turn-main", SESSION_A, "WRITE", "session",
                        "turn:t-1", "本轮总结：完成定价计算并写回", 500, 1200, base,
                        Map.of("turn_message_id", "t-1", "turn_user", "给主套餐定价",
                                "turn_outcome", "已完成"), TRACE_ID, null),
                event("itest-ev-turn-a0", SESSION_A, "READ", "prompt",
                        "config/price", "读取价格表", 100, 80, base.plusSeconds(1),
                        Map.of("turn_message_id", "t-1", "action_idx", "0",
                                "items_returned", "3"), TRACE_ID, SPAN_ROOT),
                event("itest-ev-turn-a1", SESSION_A, "WRITE", "prompt",
                        "config/price", "写回新价格", 60, 40, base.plusSeconds(2),
                        Map.of("turn_message_id", "t-1", "action_idx", "1"),
                        TRACE_ID, SPAN_ROOT),
                // 同 session 第二个 turn（keyword 过滤用）
                event("itest-ev-turn2-main", SESSION_A, "WRITE", "session",
                        "turn:t-2", "第二轮：退款政策核对", 300, 900, base.plusSeconds(30),
                        Map.of("turn_message_id", "t-2", "turn_user", "核对退款政策"),
                        TRACE_ID, null)));

        // skill 组：有效调用、:result 派生行（应被 SKILL_CALL_FILTER 剔除）、
        // 报错（metadata.status=failed）、denied、同 turn 复读
        repo.batchInsertEvents(List.of(
                event("itest-ev-sk-ok", SESSION_B, "WRITE", "skill",
                        "tool:pricing:v1", "技能执行成功", 200, 3000, base.plusSeconds(5),
                        Map.of("skill_name", "pricing", "status", "success",
                                "span_id", "span-s1"), TRACE_ID, SPAN_ROOT),
                event("itest-ev-sk-res", SESSION_B, "WRITE", "skill",
                        "tool:pricing:v1:result", "派生结果行不计入调用", 100, 0,
                        base.plusSeconds(5),
                        Map.of("skill_name", "pricing"), TRACE_ID, SPAN_CHILD),
                event("itest-ev-sk-tool", SESSION_B, "WRITE", "skill",
                        "tool:search:v2", "工具调用正常返回", 150, 200, base.plusSeconds(6),
                        Map.of("skill_name", "search", "status", "success",
                                "span_id", "span-s2"), TRACE_ID, SPAN_ROOT),
                event("itest-ev-sk-slow", SESSION_B, "WRITE", "skill",
                        "tool:report:v1", "慢技能", 180, 30000, base.plusSeconds(7),
                        Map.of("skill_name", "report", "status", "success",
                                "span_id", "span-s3"), TRACE_ID, SPAN_ROOT),
                event("itest-ev-sk-err", SESSION_B, "WRITE", "skill",
                        "tool:pricing:v1", "技能报错", 90, 500, base.plusSeconds(8),
                        Map.of("skill_name", "pricing", "status", "failed",
                                "error", "timeout", "span_id", "span-s4"),
                        TRACE_ID, SPAN_CHILD),
                event("itest-ev-sk-denied", SESSION_B, "WRITE", "skill",
                        "tool:denied", "命中精确剔除的 denied 键", 10, 10, base.plusSeconds(9),
                        Map.of("skill_name", "denied"), TRACE_ID, null),
                // 复读：同一 turn 内同 key 连续 3 次（P-SR 需要 turn_message_id）
                event("itest-ev-sk-rep-1", SESSION_B, "READ", "skill",
                        "tool:repeat:v1", "复读 1", 50, 100, base.plusSeconds(10),
                        Map.of("skill_name", "repeat", "turn_message_id", "t-9"),
                        TRACE_ID, null),
                event("itest-ev-sk-rep-2", SESSION_B, "READ", "skill",
                        "tool:repeat:v1", "复读 2", 50, 100, base.plusSeconds(11),
                        Map.of("skill_name", "repeat", "turn_message_id", "t-9"),
                        TRACE_ID, null),
                event("itest-ev-sk-rep-3", SESSION_B, "UPDATE", "skill",
                        "tool:repeat:v1", "复读 3", 50, 100, base.plusSeconds(12),
                        Map.of("skill_name", "repeat", "turn_message_id", "t-9"),
                        TRACE_ID, null)));

        // 阈值组：model 慢、provider token 高、prompt 高消耗、同 key 反复写（churn）
        repo.batchInsertEvents(List.of(
                event("itest-ev-model", SESSION_B, "READ", "model",
                        "llm:chat", "模型推理", 400, 25000, base.plusSeconds(15),
                        Map.of(), TRACE_ID, null),
                event("itest-ev-tool-prov", SESSION_B, "WRITE", "provider",
                        "api:external", "外部 API 高消耗", 9000, 700, base.plusSeconds(16),
                        Map.of(), TRACE_ID, null),
                event("itest-ev-prompt-heavy", SESSION_B, "READ", "prompt",
                        "system/prompt", "系统提示词装载", 9000, 5, base.plusSeconds(17),
                        Map.of(), TRACE_ID, null),
                event("itest-ev-churn-1", SESSION_B, "WRITE", "prompt",
                        "memory:churn-key", "第 1 次写", 30, 10, base.plusSeconds(18),
                        Map.of(), TRACE_ID, null),
                event("itest-ev-churn-2", SESSION_B, "WRITE", "prompt",
                        "memory:churn-key", "第 2 次写", 30, 10, base.plusSeconds(19),
                        Map.of(), TRACE_ID, null),
                event("itest-ev-churn-3", SESSION_B, "WRITE", "prompt",
                        "memory:churn-key", "第 3 次写", 30, 10, base.plusSeconds(20),
                        Map.of(), TRACE_ID, null)));

        // 快照组：五区占比（system 20k / task 10k / memory 30k / tool 5k / free 35k）
        repo.batchInsertSnapshots(List.of(
                new MemorySnapshot("itest-sn-1", AGENT, SESSION_A,
                        100000, 20000, 10000, 30000, 5000, 35000, 1, 0.5, base),
                new MemorySnapshot("itest-sn-2", AGENT, SESSION_B,
                        100000, 20000, 10000, 30000, 5000, 35000, 2, 0.4,
                        base.plusSeconds(60))));
    }

    private static MemoryEvent event(String id, String session, String op, String layer,
                                     String key, String summary, int tokens, double latency,
                                     Instant ts, Map<String, String> meta,
                                     String traceId, String parentSpanId) {
        return MemoryEvent.builder()
                .eventId(id).agentId(AGENT).sessionId(session)
                .operation(op).layer(layer)
                .memoryKey(key).memorySummary(summary)
                .tokenCount(tokens).latencyMs(latency).timestamp(ts)
                .metadata(meta).traceId(traceId).parentSpanId(parentSpanId)
                .build();
    }

    // ==================== 写侧 ====================

    @Test
    void 事件写入后原样读回且字段不丢() {
        List<MemoryEvent> got = repo.queryEvents(AGENT, SESSION_A, "itest-ev-turn-a0",
                null, null, FROM, TO, 10, 0);
        assertEquals(1, got.size());
        MemoryEvent e = got.get(0);
        assertEquals("READ", e.operation().name());
        assertEquals("prompt", e.layer());
        assertEquals("config/price", e.memoryKey());
        assertEquals(100, e.tokenCount());
        assertEquals(80.0, e.latencyMs(), 0.001);
        assertEquals(TRACE_ID, e.traceId());
        assertEquals(SPAN_ROOT, e.parentSpanId());
        // metadata 经 jsonb 往返：值全部保真
        assertEquals("0", e.metadata().get("action_idx"));
        assertEquals("3", e.metadata().get("items_returned"));
    }

    @Test
    void 同event_id重复插入静默跳过不报错() {
        // 与种子同 event_id、但 summary 不同：ON CONFLICT DO NOTHING 应保留旧行
        repo.batchInsertEvents(List.of(event("itest-ev-turn-a0", SESSION_A, "READ", "prompt",
                "config/price", "被冲突丢弃的新摘要", 999, 1, Instant.now(),
                Map.of(), null, null)));
        assertEquals(SEED_EVENTS, repo.countEvents(AGENT, null, null, null, null, FROM, TO));
        List<MemoryEvent> got = repo.queryEvents(AGENT, null, "itest-ev-turn-a0",
                null, null, FROM, TO, 5, 0);
        assertEquals("读取价格表", got.get(0).memorySummary());
    }

    @Test
    void count与eventStats口径一致() {
        long total = repo.countEvents(AGENT, null, null, null, null, FROM, TO);
        assertEquals(SEED_EVENTS, total);
        Map<String, Object> stats = repo.eventStats(AGENT, null, null, null, null, FROM, TO);
        assertEquals(total, ((Number) stats.get("total")).longValue());
        assertEquals(SEED_TOKENS, ((Number) stats.get("totalTokens")).longValue());
        // 会话数：种子只有 A/B 两个 session
        assertEquals(2, ((Number) stats.get("activeSessions")).longValue());
    }

    // ==================== 基础读 ====================

    @Test
    void 时间线按layer分泳道且op过滤生效() {
        Map<String, List<MemoryEvent>> lanes = repo.queryTimeline(SESSION_A);
        assertTrue(lanes.containsKey("session"));
        assertTrue(lanes.containsKey("prompt"));
        // op + layer 组合过滤：skill 层 WRITE 共 6 条（ok/res/tool/slow/err/denied）
        List<MemoryEvent> writes = repo.queryEvents(AGENT, null, null, "WRITE", "skill",
                FROM, TO, 100, 0);
        assertEquals(6, writes.size());
        assertTrue(writes.stream().allMatch(e -> "skill".equals(e.layer())));
    }

    @Test
    void 会话与Agent列表能看到本次隔离数据() {
        List<Map<String, Object>> sessions = repo.querySessions(AGENT, 10);
        assertEquals(2, sessions.size());
        List<Map<String, Object>> agents = repo.queryAgents(500);
        assertTrue(agents.stream().anyMatch(a -> AGENT.equals(a.get("agentId"))),
                "queryAgents 应包含隔离前缀 Agent");
        assertFalse(repo.queryAgentClassify(List.of(AGENT), FROM, TO).isEmpty());
    }

    // ==================== Token 聚合 ====================

    @Test
    void token分层聚合与明细和一致() {
        Map<String, Long> byLayer = repo.tokenByLayer(AGENT, FROM, TO);
        // 逐层钉死（与 seed 对账）：任何 SELECT/WHERE 漂移都会红
        assertEquals(800L, byLayer.get("session"));
        assertEquals(9250L, byLayer.get("prompt"));
        assertEquals(880L, byLayer.get("skill"));
        assertEquals(400L, byLayer.get("model"));
        assertEquals(9000L, byLayer.get("provider"));
        long sum = byLayer.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(SEED_TOKENS, sum);
        Map<String, Long> byOp = repo.tokenByOp(AGENT, FROM, TO);
        assertEquals(9600L, byOp.get("READ"));
        assertEquals(10680L, byOp.get("WRITE"));
        assertEquals(50L, byOp.get("UPDATE"));
        assertFalse(repo.tokenTrend(AGENT, FROM, TO, "hour").isEmpty());
        assertFalse(repo.queryHistogram(AGENT, FROM, TO).isEmpty());
        assertFalse(repo.queryByHour(AGENT, FROM, TO).isEmpty());
        assertFalse(repo.queryDailyTrend(AGENT, FROM, TO).isEmpty());
    }

    @Test
    void 五区预算与memory占比来自快照表() {
        Map<String, Object> zone = repo.zoneBudget(AGENT, FROM, TO);
        assertEquals(2L, ((Number) zone.get("snapshotCount")).longValue());
        assertEquals(30_000L, ((Number) zone.get("memory")).longValue());
        // memory 30k / total 100k：占比是比值不是百分数
        assertEquals(0.30, repo.memoryZonePct(AGENT, FROM, TO), 0.01);
    }

    @Test
    void 延迟分位与TopN钉死PERCENTILE_CONT口径() {
        Map<String, Object> lat = repo.queryLatencyStats(AGENT, FROM, TO, 5);
        // 18 条 latency>0 的样本（res 行 latency=0 被 WHERE 排除），
        // PERCENTILE_CONT 插值口径：p50 = 第 9.5 序 = 100，p99 = 第 17.83 序 = 29150
        assertEquals(100.0, ((Number) lat.get("p50")).doubleValue(), 0.001);
        assertEquals(29150.0, ((Number) lat.get("p99")).doubleValue(), 0.001);
        assertEquals(30000.0, ((Number) lat.get("max")).doubleValue(), 0.001);
        assertEquals(18L, ((Number) lat.get("samples")).longValue());
        assertEquals(5, ((List<?>) lat.get("topSlow")).size());
        // queryRatios 返回裸列名（snake_case）
        Map<String, Object> ratios = repo.queryRatios(AGENT, FROM, TO);
        assertEquals(SEED_TOKENS, ((Number) ratios.get("total_tokens")).longValue());
        assertEquals(10680L, ((Number) ratios.get("write_tokens")).longValue());
        assertFalse(repo.queryTopKeys(AGENT, FROM, TO, 10).isEmpty());
        assertFalse(repo.queryTopSessions(AGENT, FROM, TO, 5).isEmpty());
        assertFalse(repo.queryOpLayerMatrix(AGENT, FROM, TO).isEmpty());
        assertFalse(repo.queryLayerTimeBuckets(AGENT, FROM, TO, 3600).isEmpty());
    }

    @Test
    void 分布诊断三查询钉死Java侧分位与燃烧速率口径() {
        // 两个 session 的 token 合计：A=960，B=19370（Java 数组下标的"高估"分位口径）
        Map<String, Object> dist = repo.querySessionTokenDistribution(AGENT, FROM, TO);
        assertEquals(2L, ((Number) dist.get("count")).longValue());
        assertEquals(19_370L, ((Number) dist.get("p95")).longValue());
        assertEquals(19_370L, ((Number) dist.get("max")).longValue());
        // burnRate：窗口内无种子事件（都在 2h 前），actual=0、level=low
        Map<String, Object> burn = repo.queryBurnRate(AGENT,
                Instant.now().minus(10, ChronoUnit.MINUTES), Instant.now());
        assertEquals(0L, ((Number) burn.get("actual")).longValue());
        assertEquals("low", burn.get("level"));
        // key 异常：13 个不同 memory_key，p99 下标口径取最贵 key 合计（api/external 与 system/prompt 都是 9000）
        Map<String, Object> abnormal = repo.queryKeyTokenAbnormal(AGENT, null, FROM, TO, 10);
        assertEquals(13L, ((Number) abnormal.get("count")).longValue());
        assertEquals(9000L, ((Number) abnormal.get("p99")).longValue());
        assertFalse(((List<?>) abnormal.get("abnormalKeys")).isEmpty());
    }

    // ==================== 详情 / Turn ====================

    @Test
    void 事件详情与turn关联查询() {
        Map<String, Object> detail = repo.getEventById("itest-ev-turn-a0");
        assertNotNull(detail);
        assertEquals(AGENT, detail.get("agentId"));
        // 同 turn 事件（不排除）：t-1 共 main + a0 + a1 = 3 条
        List<Map<String, Object>> turn = repo.getTurnEvents(AGENT, "t-1", null);
        assertEquals(3, turn.size());
        // 不存在的事件返回 null（告警回放链路的空值契约）
        assertNull(repo.getEventById("itest-ev-not-exist"));
    }

    @Test
    void turn列表识别session加WRITE主事件并挂载action() {
        List<Map<String, Object>> all = repo.listTurns(AGENT, null);
        assertEquals(2, all.size()); // layer=session AND operation=WRITE AND 无 action_idx
        for (Map<String, Object> t : all) {
            assertNotNull(t.get("actions"));
        }
        // t-1 应有 2 个 action 子事件
        Map<String, Object> t1 = all.stream()
                .filter(t -> "t-1".equals(t.get("turnMessageId")))
                .findFirst().orElseThrow();
        assertEquals(2, ((List<?>) t1.get("actions")).size());
        List<Map<String, Object>> hit = repo.listTurns(AGENT, null, "退款");
        assertEquals(1, hit.size());
        assertEquals("itest-ev-turn2-main", hit.get(0).get("eventId"));
        // escapeLike：'_' 被转义为字面量 → 内容里没有下划线，0 命中
        // （若退化掉转义，'%' 通配会让这里非 0）
        assertEquals(0, repo.listTurns(AGENT, null, "_").size());
        Map<String, Object> stats = repo.turnStats(AGENT, SESSION_A);
        assertTrue(((Number) stats.get("total")).longValue() > 0);
        assertFalse(repo.turnHeatmap(AGENT, SESSION_A, 12).isEmpty());
    }

    @Test
    void queryTurnText返回用户问题与摘要拼接() {
        Map<String, String> text = repo.queryTurnText(AGENT, SESSION_A, "t-1");
        assertEquals("给主套餐定价", text.get("turnUser"));
        assertTrue(text.get("summary").contains("读取价格表"));
        assertTrue(text.get("summary").contains("写回新价格"));
        // 空参数契约：直接返回空 Map，不发 SQL
        assertTrue(repo.queryTurnText(null, SESSION_A, "t-1").isEmpty());
    }

    // ==================== Skill 域 ====================

    @Test
    void skill聚合与跨Agent视图() {
        assertFalse(repo.querySkillTopKeys(AGENT, 10).isEmpty());
        assertFalse(repo.querySkillOpDistribution(AGENT).isEmpty());
        assertFalse(repo.querySkillDailyTrend(AGENT, 7).isEmpty());
        assertFalse(repo.querySkillCrossAgent().isEmpty());
        assertFalse(repo.querySkillGlobalTopKeys(10).isEmpty());
        assertFalse(repo.querySkillMetrics(FROM, TO, AGENT).isEmpty());
    }

    @Test
    void SKILL_CALL_FILTER剔除result与denied() {
        List<Map<String, Object>> metrics = repo.querySkillMetrics(FROM, TO, AGENT);
        Map<String, Object> pricing = metrics.stream()
                .filter(m -> "pricing:v1".equals(m.get("skill"))).findFirst().orElseThrow();
        // tool:pricing:v1 两次调用（ok + err）；:result 派生行不计入（若退化会成 3 次 / 450+100 token）
        assertEquals(2L, ((Number) pricing.get("callCount")).longValue());
        assertEquals(290L, ((Number) pricing.get("totalTokens")).longValue());
        assertEquals(1L, ((Number) pricing.get("errorCount")).longValue());
        assertTrue(metrics.stream().noneMatch(m -> String.valueOf(m.get("skill")).contains("result")),
                ":result 派生行不应进入技能指标");
        assertTrue(metrics.stream().noneMatch(m -> "denied".equals(m.get("skill"))),
                "tool:denied 应被精确剔除");
    }

    // ==================== 问题阈值（P 组 / H 组）====================

    @Test
    void P组慢技能次数复读报错命中隔离数据() {
        List<Map<String, Object>> slow = repo.querySlowSkillByAgent(FROM, TO, AGENT, 10000);
        assertEquals(1L, ((Number) slow.get(0).get("events")).longValue());
        assertEquals(30000L, ((Number) slow.get(0).get("maxMs")).longValue());
        // skill 层共 9 条 > minCount=1（ok/res/tool/slow/err/denied/rep×3）
        assertEquals(9L, ((Number) repo.querySkillCountByAgent(FROM, TO, AGENT, 1)
                .get(0).get("events")).longValue());
        // 复读：t-9 内 tool:repeat:v1 共 3 次 > 2
        List<Map<String, Object>> rep = repo.querySkillRepeat(FROM, TO, AGENT, 2);
        assertEquals(1, rep.size());
        assertEquals("repeat:v1", rep.get(0).get("skill"));
        assertEquals(3L, ((Number) rep.get(0).get("count")).longValue());
        // 报错：按 metadata.status=failed / error 非空，>1 才命中 → 单条不命中，>0 命中 1
        assertTrue(repo.querySkillErrors(FROM, TO, AGENT, 1).isEmpty());
        List<Map<String, Object>> errs = repo.querySkillErrors(FROM, TO, AGENT, 0);
        assertEquals(1, errs.size());
        assertEquals("pricing:v1", errs.get(0).get("skill"));
    }

    @Test
    void P3到P7阈值查询命中() {
        // P3：provider 合计 9000 > 5000
        assertEquals(9000L, ((Number) repo.queryToolTokenByAgent(FROM, TO, AGENT, 5000)
                .get(0).get("tokens")).longValue());
        // P4-P8 共用 turn 聚合：t-9 有 3 个事件（getLong 列是 int，用 Number 兼容）
        List<Map<String, Object>> turnMetrics = repo.queryTurnMetrics(FROM, TO, AGENT);
        assertEquals(3L, ((Number) turnMetrics.stream()
                .filter(m -> "t-9".equals(m.get("turnId"))).findFirst().orElseThrow()
                .get("events")).longValue());
        // P7：快照压缩次数 2 ≥ 2 命中（也满足 free_ratio 0.35 < 0.5）
        assertEquals(2L, ((Number) repo.queryMemoryPressure(FROM, TO, AGENT, 2, 0.5)
                .get(0).get("maxCompressions")).longValue());
        // H2：session B 合计 41950 > 10000
        assertFalse(repo.querySessionTokenByAgent(FROM, TO, AGENT, 10000).isEmpty());
        // H4：memory:churn-key 被 WRITE 3 次 > 2
        List<Map<String, Object>> churn = repo.queryMemoryKeyChurn(FROM, TO, AGENT, 2);
        assertTrue(churn.stream().anyMatch(m -> "memory:churn-key".equals(m.get("key"))));
    }

    @Test
    void H组模型慢与提示词占比命中() {
        // H1：model 层均值 25000 > 10000
        assertEquals(25000L, ((Number) repo.queryModelSlowByAgent(FROM, TO, AGENT, 10000)
                .get(0).get("avgMs")).longValue());
        // H3：(system 20k + task 10k)/100k = 0.30 > 0.2
        List<Map<String, Object>> share = repo.queryPromptSharePressure(FROM, TO, AGENT, 0.2);
        assertEquals(0.30, ((Number) share.get(0).get("avgShare")).doubleValue(), 0.001);
    }

    // ==================== 流程 / Trace / 导出 ====================

    @Test
    void 流程会话与事件按FLOW_LAYERS取材() {
        List<Map<String, Object>> sessions = repo.queryFlowSessions(FROM, TO, AGENT);
        assertEquals(2, sessions.size());
        // model 层被 FLOW_LAYERS 排除：provider 的 status='failed' 才是失败口径
        List<Map<String, Object>> eventsB = repo.queryFlowEvents(FROM, TO, AGENT, SESSION_B);
        assertTrue(eventsB.stream().noneMatch(e -> "model".equals(e.get("layer"))));
        assertFalse(eventsB.isEmpty());
    }

    @Test
    void trace查询返回span并可反查trace_id() {
        List<Map<String, Object>> spans = repo.queryTraceSpans(TRACE_ID);
        assertEquals(SEED_EVENTS, spans.size());
        // spanId 取 event_id；parentSpanId='span-root' 的挂接行存在（树构建前提）
        assertTrue(spans.stream().anyMatch(s -> SPAN_ROOT.equals(s.get("parentSpanId"))));
        Map<String, Object> child = spans.stream()
                .filter(s -> "itest-ev-sk-ok".equals(s.get("spanId"))).findFirst().orElseThrow();
        assertEquals(SPAN_ROOT, child.get("parentSpanId"));
        assertEquals(TRACE_ID, repo.findTraceIdByEventId("itest-ev-sk-ok"));
        List<Map<String, Object>> traces = repo.queryAgentTraces(AGENT, null, 10, 0);
        assertEquals(1, traces.size());
        assertEquals(TRACE_ID, traces.get(0).get("traceId"));
        assertEquals((long) SEED_EVENTS, ((Number) traces.get(0).get("spanCount")).longValue());
    }

    @Test
    void 导出流式回调逐行消费() {
        List<String> ids = new ArrayList<>();
        repo.streamEventsForExport(FROM, TO, AGENT, null, null, 1000,
                rs -> ids.add(rs.getString("event_id")));
        assertEquals(SEED_EVENTS, ids.size());
        assertTrue(ids.contains("itest-ev-turn-main"));
    }

    // ==================== 导入台账 ====================

    @Test
    void 导入台账upsert可回读且重复刷新() {
        assertNull(repo.findImportedLogByMd5(IMPORT_MD5));
        repo.upsertImportedLog(IMPORT_MD5, "itest.log", false, 22, 22, 0, 0);
        Map<String, Object> row = repo.findImportedLogByMd5(IMPORT_MD5);
        assertNotNull(row);
        // 刷新统计后再读回不报错（ON CONFLICT DO UPDATE 分支）
        repo.upsertImportedLog(IMPORT_MD5, "itest.log", true, 25, 5, 20, 1);
        assertNotNull(repo.findImportedLogByMd5(IMPORT_MD5));
    }
}
