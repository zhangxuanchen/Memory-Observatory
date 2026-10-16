package io.memobservatory.exoskeleton.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 技能装载回流<strong>读侧</strong>（{@link SkillLoadRepository#bySkill}）的行映射（零依赖手写替身）。
 *
 * <p><b>怎么在无库处验</b>：子类化 {@link JdbcTemplate}，用一个动态代理造假 {@link ResultSet}，
 * 把它交给真实的 {@code RowMapper} 跑——于是验的是映射本身（含时间戳为空不回填、跨 Agent 取最大时间），
 * 而不是替身。放着不测的风险点是「{@code last_loaded_at} 为 NULL 时被读成 1970」这类静默错误。
 */
class SkillLoadRepositoryTest {

    /** 造一个只认三列的假 ResultSet（getString / getLong / getTimestamp）。 */
    private static ResultSet fakeRow(String skillId, long count, Timestamp ts) {
        return (ResultSet) Proxy.newProxyInstance(
                SkillLoadRepositoryTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> skillId;
                    case "getLong" -> count;
                    case "getTimestamp" -> ts;
                    case "wasNull" -> false;
                    default -> null;
                });
    }

    /** 捕获查询、按预置行跑真实 RowMapper 的替身。 */
    private static class FakeJdbc extends JdbcTemplate {
        final List<ResultSet> rows = new ArrayList<>();

        /** 无参 SQL 走这个两参重载（Java 重载解析优先非变参），必须一并覆盖，否则落到真 JdbcTemplate。 */
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
    }

    @Test
    @DisplayName("一行装载读数按 skill_id / 次数 / 最近时间映射;时间戳为空如实给 null，不回填 1970")
    void 读侧映射如实() {
        FakeJdbc jdbc = new FakeJdbc();
        Instant t = Instant.parse("2026-10-09T00:00:00Z");
        jdbc.rows.add(fakeRow("a--fix", 7, Timestamp.from(t)));
        jdbc.rows.add(fakeRow("a--doc", 3, null));

        List<SkillLoadRepository.LoadStat> out = new SkillLoadRepository(jdbc).bySkill();

        assertEquals(2, out.size());
        assertEquals("a--fix", out.get(0).skillId());
        assertEquals(7, out.get(0).loadCount());
        assertEquals(t, out.get(0).lastLoadedAt());
        assertNull(out.get(1).lastLoadedAt(), "从未…的 NULL 时间戳应如实为 null");
    }
}