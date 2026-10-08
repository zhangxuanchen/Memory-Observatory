package io.memobservatory.exoskeleton.tasks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务类型分类的取数与回写（写入侧）。
 *
 * <p>与报表侧（{@code RuleRepository.taskTypeDistribution()}）的分工：这边只管「分类流水线」——
 * 取还没分类完的 session、把结果写回去；那边只管读出来做筛选与统计。
 *
 * <p><b>增量与幂等不需要水位线</b>：候选条件是「该 session 里还有 turn 的 {@code task_type} 为 NULL」。
 * 分类成功即整个 session 一次性写满，下轮就不再进候选；LLM 失败的 session 保持 NULL，下轮自动重试。
 * 这比水位线简单，也不会因重启丢状态。
 */
@Repository
public class TaskTypeRepository {

    private final JdbcTemplate jdbc;

    public TaskTypeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一个 session 的待分类素材。
     * {@code turnTexts} 是会话内用户提问的原文列表（按时间顺序），<b>刻意不含</b>
     * {@code io_kind} / {@code action_seq}：一旦把行为特征喂进去，模型容易被带着复述 §4.4 的 shape，
     * 那正是本维度要避免的与分桶重复。
     *
     * <p>为什么给「一整个列表」而不是拼好的字符串：摘要超限时要在 <b>turn 粒度</b>上均匀抽样
     * （见 {@code TaskClassifier}），拼接会丢掉 turn 边界，无法隔条取样。
     */
    public record SessionDigest(String sessionId, String agentId, String source, long turns,
                                List<String> turnTexts) {
    }

    private static final String SQL_COLUMNS = """
            SELECT session_id,
                   MAX(agent_id) AS agent_id,
                   MAX(source)   AS source,
                   COUNT(*)      AS turns,
                   array_agg(user_text ORDER BY started_at NULLS LAST, turn_id) AS turn_texts
            FROM memory_turns
            WHERE user_text IS NOT NULL AND btrim(user_text) <> ''
            GROUP BY session_id
            """;

    /** 增量口径：只取「还有 turn 未分类」的 session。 */
    private static final String SQL_PENDING = SQL_COLUMNS
            + """
            HAVING COUNT(*) FILTER (WHERE task_type IS NULL) > 0
            ORDER BY MAX(started_at) DESC NULLS LAST
            LIMIT ?
            """;

    /** 强制口径（force）：无视已有分类，全部 session 重判并覆盖。 */
    private static final String SQL_FORCE = SQL_COLUMNS
            + """
            ORDER BY MAX(started_at) DESC NULLS LAST
            LIMIT ?
            """;

    /**
     * 取待分类的 session。没有用户提问的 session（如纯 otlp）天然不进候选——没有文本可判。
     *
     * @param limit 本轮上限，给「每 session 一次 LLM 调用」的成本与耗时兜底
     * @param force true = 无视已有分类全量重判（用于判据/摘要策略变更后的重标定）；false = 只取未分类的
     */
    public List<SessionDigest> pendingSessions(int limit, boolean force) {
        return jdbc.query(force ? SQL_FORCE : SQL_PENDING, (rs, n) -> new SessionDigest(
                rs.getString("session_id"),
                rs.getString("agent_id"),
                rs.getString("source"),
                rs.getLong("turns"),
                texts(rs.getArray("turn_texts"))), limit);
    }

    /** 把 Postgres {@code text[]} 拆成 {@code List<String>}，顺带滤掉空白项。 */
    private static List<String> texts(Array arr) {
        if (arr == null) {
            return List.of();
        }
        try {
            Object raw = arr.getArray();
            int len = java.lang.reflect.Array.getLength(raw);
            List<String> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(raw, i);
                if (o == null) {
                    continue;
                }
                String s = o.toString().strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 回写整个 session：该 session 的所有 turn 共享同一个 task_type / task_note（session 粒度）。 */
    private static final String SQL_WRITE = """
            UPDATE memory_turns SET task_type = ?, task_note = ? WHERE session_id = ?
            """;

    public int writeBack(String sessionId, String taskType, String note) {
        return jdbc.update(SQL_WRITE, taskType, note, sessionId);
    }

    /** 批量回写（多个 session 一次提交）。 */
    public int writeBackAll(List<SessionTask> tasks) {
        if (tasks.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(tasks.size());
        for (SessionTask t : tasks) {
            args.add(new Object[]{t.taskType(), t.note(), t.sessionId()});
        }
        int sum = 0;
        for (int a : jdbc.batchUpdate(SQL_WRITE, args)) {
            sum += Math.max(a, 0);
        }
        return sum;
    }

    /**
     * 清空给定 session 的旧分类（置 NULL）。force 重判前调用。
     *
     * <p>为什么要清：force 只覆盖判成功的 session，判失败（超时/不可解析）的会<b>静默留着上一轮
     * 的旧标签</b>——库里看着「有值」，实际是旧判据的产物，且增量口径下它不再进候选，那份旧值
     * 永远得不到修正。先在候选集上清空，失败者就回到 NULL、下轮自动重试，与「不算就不写」一致。
     */
    public int clearTaskType(List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(sessionIds.size());
        for (String id : sessionIds) {
            args.add(new Object[]{id});
        }
        int sum = 0;
        for (int a : jdbc.batchUpdate("UPDATE memory_turns SET task_type = NULL, task_note = NULL"
                + " WHERE session_id = ?", args)) {
            sum += Math.max(a, 0);
        }
        return sum;
    }

    /** 一条待回写的 session 分类。 */
    public record SessionTask(String sessionId, String taskType, String note) {
    }
}