package io.memobservatory.exoskeleton.skills;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillProposalRepository} 的 P0-2 分页 / 抽样分支（零依赖手写替身）。
 *
 * <p><b>怎么在无库处验「选了哪条 SQL」</b>：子类化 {@link JdbcTemplate}，覆盖 {@code query}
 * 只把 SQL 串与参数记下来、返回空清单。于是验的是仓库在 {@code sampleCap / limit} 不同取值下
 * <b>走了哪条分支</b>——这正是「缺省行为不变」的落点：缺省必须与改造前逐字一致。
 *
 * <p>抽样的<b>结果</b>本身是 SQL 语义（均匀取样），只能连真库验证；这里断言的是分支与参数，
 * 与登记口对 P0-2「默认 0 = 完全不变」的承诺对应。
 */
class SkillProposalRepositorySamplingTest {

    /** 捕获 SQL 的替身：不连库，只记下被执行的那条 SQL 与绑定参数。 */
    private static class CapturingJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... a) {
            sqls.add(sql);
            args.add(a);
            return List.of();
        }
    }

    private static SkillProposalRepository repo(CapturingJdbc jdbc) {
        return new SkillProposalRepository(jdbc);
    }

    @Test
    @DisplayName("sampleCap 为 0 → 走原全量 SQL（无开窗函数、无抽样），行为与从前逐字一致")
    void 抽样上限为零走全量SQL() {
        CapturingJdbc jdbc = new CapturingJdbc();
        repo(jdbc).classTurnTexts("a", "fix_bug", 0);

        String sql = jdbc.sqls.get(0);
        assertFalse(sql.contains("row_number"), "缺省不得引入开窗抽样：" + sql);
        assertFalse(sql.contains("s.total"), "缺省不得引入抽样条件：" + sql);
        assertEquals(2, jdbc.args.get(0).length, "只绑定 agentId 与 taskType");
    }

    @Test
    @DisplayName("sampleCap > 0 → 走 SQL 层均匀抽样，绑定四个参数（agentId/taskType/cap/cap）")
    void 抽样上限为正走抽样SQL() {
        CapturingJdbc jdbc = new CapturingJdbc();
        repo(jdbc).classTurnTexts("a", "fix_bug", 500);

        String sql = jdbc.sqls.get(0);
        assertTrue(sql.contains("row_number"), "抽样必须发生在 SQL 层：" + sql);
        assertTrue(sql.contains("s.total"), "抽样以总数为分母：" + sql);
        assertEquals(4, jdbc.args.get(0).length, "agentId / taskType / sampleCap / sampleCap");
        assertEquals(500, jdbc.args.get(0)[2]);
        assertEquals(500, jdbc.args.get(0)[3]);
    }

    @Test
    @DisplayName("limit 为 0 → 不加 LIMIT；limit > 0 → 追加 LIMIT 并绑定行数")
    void limit分页可选() {
        CapturingJdbc noLimit = new CapturingJdbc();
        repo(noLimit).agents(0);
        repo(noLimit).listByAgent("a", 0);
        assertFalse(noLimit.sqls.get(0).contains("LIMIT"), noLimit.sqls.get(0));
        assertFalse(noLimit.sqls.get(1).contains("LIMIT"), noLimit.sqls.get(1));
        assertEquals(0, noLimit.args.get(0).length, "limit<=0：仅全量 SQL，无额外参数");
        assertEquals(1, noLimit.args.get(1).length, "limit<=0：listByAgent 只绑 agentId");

        CapturingJdbc limited = new CapturingJdbc();
        repo(limited).agents(5);
        repo(limited).listByAgent("a", 7);
        assertTrue(limited.sqls.get(0).endsWith("LIMIT ?"), limited.sqls.get(0));
        assertTrue(limited.sqls.get(1).endsWith("LIMIT ?"), limited.sqls.get(1));
        assertEquals(5, limited.args.get(0)[0]);
        assertEquals(7, limited.args.get(1)[1]);
    }
}