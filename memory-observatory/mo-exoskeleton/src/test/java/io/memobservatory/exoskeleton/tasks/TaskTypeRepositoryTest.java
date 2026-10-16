package io.memobservatory.exoskeleton.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskTypeRepository} 的回写口径（任务 A）。
 *
 * <p>用一个只记录参数的 {@link JdbcTemplate} 子类拦下 SQL：不起数据库，只验「回写 SQL 里带没带
 * 口径版本与分类时间、参数量对不对」。方言是 Postgres 专用（{@code now()}），在内存库上跑不了。
 *
 * <p>不用 Mockito：本机运行时是 JDK 26，Mockito 依赖的 Byte Buddy 尚不支持，改用零依赖的子类替身。
 */
class TaskTypeRepositoryTest {

    /** 只记录最近一次调用的 SQL 与参数，不真正连库。 */
    private static class RecordingJdbcTemplate extends JdbcTemplate {
        String sql;
        Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 0;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql = sql;
            this.args = args;
            return null;
        }
    }

    @Test
    @DisplayName("回写同时落 task_type_version 与 classified_at = now()")
    void 回写带口径版本与分类时间() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        TaskTypeRepository repo = new TaskTypeRepository(jdbc);

        repo.writeBack("s1", "fix_bug", "备注");

        assertTrue(jdbc.sql.contains("task_type_version"), jdbc.sql);
        assertTrue(jdbc.sql.contains("classified_at") && jdbc.sql.contains("now()"),
                "classified_at 由 SELECT 子句的 now() 落值（迁自有表后不再是 UPDATE ... SET classified_at = now()）：" + jdbc.sql);
        assertEquals(List.of("fix_bug", "备注", TaskType.TAXONOMY_VERSION, "s1"),
                Arrays.asList(jdbc.args), "参数顺序须为 task_type / note / 版本 / session_id");
    }

    @Test
    @DisplayName("stale 计数按当前版本比对，NULL 或不等都算旧口径")
    void 旧口径计数条件正确() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        TaskTypeRepository repo = new TaskTypeRepository(jdbc);

        repo.countStaleVersion(TaskType.TAXONOMY_VERSION);

        assertTrue(jdbc.sql.contains("task_type_version IS NULL"), jdbc.sql);
        assertTrue(jdbc.sql.contains("task_type_version <> ?"), jdbc.sql);
        assertEquals(List.of(TaskType.TAXONOMY_VERSION), Arrays.asList(jdbc.args));
    }

    @Test
    @DisplayName("人工纠正按会话整段覆盖：回写谓词是 session_id（该会话全部 turn），不是单条 turn")
    void 纠正覆盖该会话全部turn() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        TaskTypeRepository repo = new TaskTypeRepository(jdbc);

        repo.writeBack("s-42", "refactor", "人工纠正");

        assertTrue(jdbc.sql.contains("INSERT INTO memory_turn_criteria"),
                "判据列已迁出 memory_turns，人工纠正写本模块自有的 memory_turn_criteria：" + jdbc.sql);
        assertTrue(jdbc.sql.contains("WHERE t.session_id = ?"),
                "必须按 session_id 整段覆盖，否则同会话其余 turn 会与纠正后的类型不一致：" + jdbc.sql);
        assertEquals("s-42", jdbc.args[3], "谓词绑定的应是 session_id");
    }
}