package io.memobservatory.exoskeleton.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提炼运行态的读写（表 {@code memory_skill_runs}，P1-3）的落点核对（零依赖手写替身）。
 *
 * <p><b>怎么在无库处验</b>：子类化 {@link JdbcTemplate}，捕获每条 SQL 与绑定参数，再核对
 * <b>语句里写了什么</b>——因为这四件事的风险都在语句本身，而不是替身：
 * <ul>
 *   <li>开跑是否真的写了 {@code RUNNING}（写错字就永远读不到「在跑」）；</li>
 *   <li>完成一类是不是 {@code done_classes = done_classes + 1} 的<b>原子自增</b>
 *       （写成读改写会在并发下丢更新，且症状是静默少算）；</li>
 *   <li>收尾是否带上 {@code finished_at}（漏了就看不出「什么时候结束」）；</li>
 *   <li>启动清遗留的谓词是否只扫 {@code RUNNING}（扫错把历史终态也改了）。</li>
 * </ul>
 * 读侧的 {@link SkillRunRepository#lastRun} 则验行映射（尤其时间戳为空不臆造）。
 */
class SkillRunRepositoryTest {

    /** 捕获 update 的语句与参数、按预置行跑真实 RowMapper 的替身。 */
    private static class FakeJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        final List<ResultSet> rows = new ArrayList<>();

        /** 无参 update 走这个单参重载（Java 重载解析优先非变参），必须一并覆盖。 */
        @Override
        public int update(String sql) {
            sqls.add(sql);
            args.add(new Object[0]);
            return 1;
        }

        @Override
        public int update(String sql, Object... a) {
            sqls.add(sql);
            args.add(a);
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper) {
            return run(mapper);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... a) {
            return run(mapper);
        }

        private <T> List<T> run(RowMapper<T> mapper) {
            List<T> out = new ArrayList<>();
            for (ResultSet r : rows) {
                try {
                    out.add(mapper.mapRow(r, 0));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            return out;
        }

        /** 最近一条语句（压平空白，便于断言子串）。 */
        String lastSql() {
            return sqls.get(sqls.size() - 1).replaceAll("\\s+", " ");
        }

        Object[] lastArgs() {
            return args.get(args.size() - 1);
        }
    }

    /** 造一个按列名取值的假 ResultSet（getString / getInt / getObject）。 */
    private static ResultSet fakeRow(String runId, String status, int total, int done, String current,
                                     OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        return (ResultSet) Proxy.newProxyInstance(
                SkillRunRepositoryTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, a) -> switch (method.getName()) {
                    case "getString" -> switch ((String) a[0]) {
                        case "run_id" -> runId;
                        case "status" -> status;
                        case "current_class" -> current;
                        default -> null;
                    };
                    case "getInt" -> switch ((String) a[0]) {
                        case "total_classes" -> total;
                        case "done_classes" -> done;
                        default -> 0;
                    };
                    case "getObject" -> switch ((String) a[0]) {
                        case "started_at" -> startedAt;
                        case "finished_at" -> finishedAt;
                        default -> null;
                    };
                    case "wasNull" -> false;
                    default -> null;
                });
    }

    @Test
    @DisplayName("开跑：落一行 RUNNING，绑定 run_id 与 agent_id")
    void 开跑写RUNNING() {
        FakeJdbc jdbc = new FakeJdbc();

        new SkillRunRepository(jdbc).start("run-1", "agent-a");

        assertTrue(jdbc.lastSql().contains("INSERT INTO memory_skill_runs"), jdbc.lastSql());
        assertTrue(jdbc.lastSql().contains("'RUNNING'"), "开跑必写 RUNNING：" + jdbc.lastSql());
        assertEquals("run-1", jdbc.lastArgs()[0]);
        assertEquals("agent-a", jdbc.lastArgs()[1]);
    }

    @Test
    @DisplayName("完成一类：原子自增 done_classes（不读改写，避免并发丢更新）")
    void 完成一类原子自增() {
        FakeJdbc jdbc = new FakeJdbc();

        new SkillRunRepository(jdbc).bumpDone("run-1");

        assertTrue(jdbc.lastSql().contains("done_classes = done_classes + 1"),
                "必须是库侧原子自增，不能读改写：" + jdbc.lastSql());
        assertEquals("run-1", jdbc.lastArgs()[0]);
    }

    @Test
    @DisplayName("收尾：写终态与 finished_at，并清 current_class")
    void 收尾写终态与完成时刻() {
        FakeJdbc jdbc = new FakeJdbc();

        new SkillRunRepository(jdbc).finish("run-1", SkillRunRepository.DONE);

        assertTrue(jdbc.lastSql().contains("finished_at = now()"), jdbc.lastSql());
        assertTrue(jdbc.lastSql().contains("current_class = NULL"), jdbc.lastSql());
        assertEquals(SkillRunRepository.DONE, jdbc.lastArgs()[0], "终态取值应绑定为参数");
        assertEquals("run-1", jdbc.lastArgs()[1]);
    }

    @Test
    @DisplayName("启动清遗留：只把 RUNNING 改成 INTERRUPTED（历史终态不动）")
    void 启动清遗留RUNNING() {
        FakeJdbc jdbc = new FakeJdbc();

        int n = new SkillRunRepository(jdbc).markOrphansInterrupted();

        assertEquals(1, n, "替身回 1 行");
        assertTrue(jdbc.lastSql().contains("SET status = 'INTERRUPTED'"), jdbc.lastSql());
        assertTrue(jdbc.lastSql().contains("WHERE status = 'RUNNING'"),
                "谓词必须只扫 RUNNING，否则会篡改已结束的历史：" + jdbc.lastSql());
    }

    @Test
    @DisplayName("读运行态：无行如实给 null；有行按列映射，时间戳空则 null")
    void 读运行态如实() {
        FakeJdbc jdbc = new FakeJdbc();
        SkillRunRepository repo = new SkillRunRepository(jdbc);

        assertNull(repo.lastRun("agent-a"), "从未跑过应如实给 null，不造值");

        OffsetDateTime started = OffsetDateTime.ofInstant(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
        jdbc.rows.add(fakeRow("run-9", SkillRunRepository.INTERRUPTED, 5, 2, null, started, null));

        SkillRunRepository.RunStat st = repo.lastRun("agent-a");
        assertNotNull(st);
        assertEquals("run-9", st.runId());
        assertEquals(SkillRunRepository.INTERRUPTED, st.status());
        assertEquals(5, st.totalClasses());
        assertEquals(2, st.doneClasses());
        assertNull(st.currentClass());
        assertEquals(started.toInstant(), st.startedAt());
        assertNull(st.finishedAt(), "跑着的一轮 finished_at 为空，应如实为 null");
    }
}