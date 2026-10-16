package io.memobservatory.server.exoprox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能装载回流写侧（{@link SkillLoadRepository}）的三条边界。
 *
 * <p><b>怎么在无库处验</b>：子类化 {@link JdbcTemplate} 只捕获 {@code update} 的 SQL 与参数；
 * 执行器传同步的 {@code Runnable::run}，让上报内联执行、断言确定（不靠 sleep 等异步）。
 *
 * <p>三条边界对应「回流是旁路」这个前提：空白 skill 不落库（不造噪声行）、
 * 同键在 TTL 内不重复上报（不刷成噪声计数）、同 (agent, skill) 靠 ON CONFLICT 累加而非插两行。
 */
class SkillLoadRepositoryTest {

    /** 捕获 upsert 调用的替身：不连库，只记下 SQL 与绑定参数。 */
    private static class CapturingJdbc extends JdbcTemplate {
        final List<String> sqls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();

        @Override
        public int update(String sql, Object... a) {
            sqls.add(sql);
            args.add(a);
            return 1;
        }
    }

    private static SkillLoadRepository repo(CapturingJdbc jdbc) {
        // 同步执行器：上报内联执行，测试无需等待
        return new SkillLoadRepository(jdbc, Runnable::run);
    }

    @Test
    @DisplayName("空白 skillId 不落库（不造噪声行）")
    void 空白技能不落库() {
        CapturingJdbc jdbc = new CapturingJdbc();
        repo(jdbc).recordLoads("a", "w", "s", List.of("", "   "));
        assertThat(jdbc.sqls).isEmpty();
    }

    @Test
    @DisplayName("空 agent / 空清单直接返回，不写库")
    void 空输入不写库() {
        CapturingJdbc jdbc = new CapturingJdbc();
        SkillLoadRepository r = repo(jdbc);
        r.recordLoads(null, "w", "s", List.of("sk"));
        r.recordLoads("a", "w", "s", List.of());
        assertThat(jdbc.sqls).isEmpty();
    }

    @Test
    @DisplayName("TTL 内同 (agent, skill) 不重复上报；不同技能各写一条")
    void 窗口内同键不重复上报() {
        CapturingJdbc jdbc = new CapturingJdbc();
        SkillLoadRepository r = repo(jdbc);
        r.recordLoads("a", "w", "s1", List.of("sk"));
        r.recordLoads("a", "w", "s2", List.of("sk"));    // 同键：窗口内跳过
        r.recordLoads("a", "w", "s3", List.of("sk2"));   // 不同技能：照写

        assertThat(jdbc.sqls).hasSize(2);
        assertThat(jdbc.args.get(0)[1]).isEqualTo("sk");
        assertThat(jdbc.args.get(1)[1]).isEqualTo("sk2");
    }

    @Test
    @DisplayName("同 (agent, skill) 的 upsert 用 ON CONFLICT 累加计数，不插第二行")
    void upsert累加计数() {
        CapturingJdbc jdbc = new CapturingJdbc();
        repo(jdbc).recordLoads("a", "w", "s", List.of("sk"));

        String sql = jdbc.sqls.get(0);
        assertThat(sql).contains("ON CONFLICT (agent_id, skill_id) DO UPDATE");
        assertThat(sql).contains("load_count      = memory_skill_loads.load_count + 1");
    }
}