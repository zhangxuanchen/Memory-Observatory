package io.memobservatory.exoskeleton.skills;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 技能装载回流的<strong>读侧</strong>（表 {@code memory_skill_loads}，由 mo-server 直写）。
 *
 * <p><b>只读</b>：本模块不产装载事实，只把它读出来给人面看——「哪些技能真的被装进过 Agent」。
 * 口径是<b>装载</b>而非调用（写侧注释与 schema.sql 已详述为何不能归因到调用），
 * 故这里按 {@code skill_id} 汇总「被装载的总次数」与「最近一次装载时间」，答的是
 * 「这个技能有没有被用上、还在被用吗」，不假装答「用了多少次、效果如何」。
 *
 * <p><b>为什么按 skill_id 聚合而不是按 Agent</b>：看板是「技能提议清单」，一行一个技能，
 * 装载读数天然挂在技能上；同一技能可能被多个 Agent 装载，故 {@code SUM} 计总、{@code MAX} 取最近。
 * 表由 mo-server 建（跑在外骨骼 schema 上），极端启动次序下可能尚未存在——
 * 查询失败由调用方兜住（看板取不到就当作无回流读数，不影响其余字段）。
 */
@Repository
public class SkillLoadRepository {

    /** 某技能一行的回流读数：被装载过几次、最近一次是什么时候（按 skill_id 跨 Agent 汇总）。 */
    public record LoadStat(String skillId, long loadCount, Instant lastLoadedAt) {
    }

    private static final String SQL_BY_SKILL = """
            SELECT skill_id,
                   SUM(load_count)    AS load_count,
                   MAX(last_loaded_at) AS last_loaded_at
            FROM memory_skill_loads
            GROUP BY skill_id
            ORDER BY load_count DESC
            """;

    private final JdbcTemplate jdbc;

    public SkillLoadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部技能的装载读数（按装载次数从多到少）。 */
    public List<LoadStat> bySkill() {
        return jdbc.query(SQL_BY_SKILL, (rs, n) -> {
            Timestamp ts = rs.getTimestamp("last_loaded_at");
            return new LoadStat(rs.getString("skill_id"), rs.getLong("load_count"),
                    ts == null ? null : ts.toInstant());
        });
    }
}