package io.memobservatory.exoskeleton.skills;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 提炼运行态的读写（表 {@code memory_skill_runs}，见 schema.sql 的表注释）。
 *
 * <p><b>它只落「进度与终态」，不做互斥</b>：互斥仍由 {@code SkillDiscoverer} 的进程内
 * {@code Progress} 承担（单实例前提）。本表的作用是让「跑到哪、是不是被中断」在重启后还读得到。
 *
 * <p><b>所有写方法都直抛异常，由调用方（{@code SkillDiscoverer}）包旁路 try/catch</b>——
 * 运行态是观测旁路，写不进去也不该掀翻提炼本身。唯一例外是启动清遗留 RUNNING（{@link #markOrphansInterrupted()}），
 * 它自己吞掉异常并记日志（那时没有上层可兜）。
 *
 * <p><b>原子自增</b>：{@link #bumpDone} 用 {@code SET done_classes = done_classes + 1} 而不是读改写——
 * 并发提炼时多个类同时完成，读改写会丢更新。
 */
@Repository
public class SkillRunRepository {

    private static final Logger log = LoggerFactory.getLogger(SkillRunRepository.class);

    /** 运行态取值（与 schema.sql 的 status 注释一一对应）。 */
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String CANCELLED = "CANCELLED";
    public static final String INTERRUPTED = "INTERRUPTED";
    /**
     * 因预算 / 熔断提前停下（P2）：没跑完全部达标类，但也不是人取消、不是意外中断。
     *
     * <p><b>为什么单列一态而不并入 CANCELLED</b>：CANCELLED 的语义是「人点了取消」；预算耗尽与熔断
     * 是系统自停、且「下一轮还会继续」，把两者混成一个值，事后就答不出「这轮为什么没跑完」——
     * 与记忆外骨骼「如实记账」的一贯口径相悖（同 DONE / INTERRUPTED 的分法）。
     */
    public static final String HALTED = "HALTED";

    private static final String SQL_INSERT = """
            INSERT INTO memory_skill_runs (run_id, agent_id, status)
            VALUES (?, ?, 'RUNNING')
            """;
    private static final String SQL_TOTALS = """
            UPDATE memory_skill_runs
               SET total_classes = ?, updated_at = now()
             WHERE run_id = ?
            """;
    private static final String SQL_CURRENT = """
            UPDATE memory_skill_runs
               SET current_class = ?, updated_at = now()
             WHERE run_id = ?
            """;
    /** 原子自增：并发完成多个类时不丢更新（禁读改写）。 */
    private static final String SQL_BUMP = """
            UPDATE memory_skill_runs
               SET done_classes = done_classes + 1, updated_at = now()
             WHERE run_id = ?
            """;
    private static final String SQL_FINISH = """
            UPDATE memory_skill_runs
               SET status = ?, current_class = NULL, finished_at = now(), updated_at = now()
             WHERE run_id = ?
            """;
    /** 启动清遗留：单实例前提下，库里的 RUNNING 只可能来自上一次没跑完的进程（见 schema.sql 边界注释）。 */
    private static final String SQL_ORPHANS = """
            UPDATE memory_skill_runs
               SET status = 'INTERRUPTED', current_class = NULL, finished_at = now(), updated_at = now()
             WHERE status = 'RUNNING'
            """;
    private static final String SQL_LAST = """
            SELECT run_id, status, total_classes, done_classes, current_class, started_at, finished_at
              FROM memory_skill_runs
             WHERE agent_id = ?
             ORDER BY started_at DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbc;

    public SkillRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 启动清遗留 RUNNING → INTERRUPTED。
     *
     * <p>放在 {@code @PostConstruct}：进程刚起来、内存 Progress 必为空，此刻库里的 RUNNING 必是中断残留。
     * 失败只记日志（例如启动次序极端情况下表尚未建）——运行态是旁路，不阻塞启动。
     */
    @PostConstruct
    void init() {
        int n = markOrphansInterrupted();
        if (n > 0) {
            log.info("[skill-run] 启动清理：{} 条遗留 RUNNING 已标记为 INTERRUPTED（上轮未跑完）", n);
        }
    }

    /** 把库里所有遗留 RUNNING 标为 INTERRUPTED；返回改写行数。异常自行吞掉（启动路径无上层可兜）。 */
    public int markOrphansInterrupted() {
        try {
            return jdbc.update(SQL_ORPHANS);
        } catch (Exception e) {
            log.warn("[skill-run] 启动清遗留 RUNNING 失败（按无遗留继续）：{}", e.toString());
            return 0;
        }
    }

    /** 开跑：先落一行 RUNNING（total / done 默认 0，随后由 {@link #setTotals} 补齐分母）。 */
    public void start(String runId, String agentId) {
        jdbc.update(SQL_INSERT, runId, agentId);
    }

    /** 记本轮要处理的类数（过门槛后的真实数量），让页面分母一开始就稳定。 */
    public void setTotals(String runId, int totalClasses) {
        jdbc.update(SQL_TOTALS, totalClasses, runId);
    }

    /** 记当前正在处理的类（只作展示）；可为 null（空闲）。 */
    public void setCurrent(String runId, String currentClass) {
        jdbc.update(SQL_CURRENT, currentClass, runId);
    }

    /** 每完成一类 +1（原子自增）。 */
    public void bumpDone(String runId) {
        jdbc.update(SQL_BUMP, runId);
    }

    /** 写终态：{@code status} ∈ {DONE, CANCELLED, INTERRUPTED, HALTED}，并置 {@code finished_at} 与清 current。 */
    public void finish(String runId, String status) {
        jdbc.update(SQL_FINISH, status, runId);
    }

    /** 某 Agent 最近一轮运行态；从未跑过返回 null（如实，不造值）。 */
    public RunStat lastRun(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        List<RunStat> rows = jdbc.query(SQL_LAST, (rs, n) -> new RunStat(
                rs.getString("run_id"),
                rs.getString("status"),
                rs.getInt("total_classes"),
                rs.getInt("done_classes"),
                rs.getString("current_class"),
                instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("finished_at", OffsetDateTime.class))), agentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant instant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    /**
     * 一轮提炼的运行态读数（供 status 响应：页面据此显示「上轮被中断（done/total）」）。
     * 时间戳可空（{@code finished_at} 在跑着时为空）。
     */
    public record RunStat(String runId, String status, int totalClasses, int doneClasses,
                          String currentClass, Instant startedAt, Instant finishedAt) {
    }
}