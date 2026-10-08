package io.memobservatory.exoskeleton.storage;

import io.memobservatory.exoskeleton.cluster.ShapeBucketer;
import io.memobservatory.exoskeleton.model.ClusterProfile;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.tasks.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 规则的只读数据访问层（对应设计文档 §D2 接口二）。
 *
 * 两块取数：规则清单（含版本数与 churn，§2.7）与支撑度分布（J2）。
 * 判据（隐式纠正、holdout、对照）已迁到 {@link TurnCriteriaRepository} 与
 * {@code criteria.CriteriaService}——相似度只能有一个真相来源。
 * 规则的「能不能上线」不在这一层判断——唯一入口是晋升闸门（§4.11），而闸门不在本期。
 *
 * 支撑度只按真实存在的列分桶（见 {@link ShapeBucketer}），不套 lab05 的语义簇：
 * 那三条判据要求 `layer='prompt'` 与 `writes==0`，在工作台事件流里都不成立。
 */
@Repository
public class RuleRepository {

    private final JdbcTemplate jdbc;

    @Value("${mo.exoskeleton.min-support:30}")
    private int minSupport;
    @Value("${mo.exoskeleton.churn-weeks:4}")
    private int churnWeeks;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 规则清单：每条规则取最新一行 + 版本数 + 观察窗内版本数（churn）
    // 旧版本行保留在表中，DISTINCT ON 只取最新版；窗口函数在 DISTINCT 之前算，故不受影响。
    // ------------------------------------------------------------------
    private static final String SQL_RULES = """
            SELECT DISTINCT ON (rule_id)
                   rule_id, cluster_key, version, body, state, hit_count, created_at,
                   demoted_at, hardened_at,
                   COUNT(*) OVER (PARTITION BY rule_id) AS version_count,
                   COUNT(*) FILTER (WHERE created_at >= now() - make_interval(weeks => ?))
                            OVER (PARTITION BY rule_id) AS churn
            FROM memory_rules
            ORDER BY rule_id, version DESC
            """;

    public List<RulesReport.RuleView> rules() {
        return jdbc.query(SQL_RULES, (rs, n) -> new RulesReport.RuleView(
                row(rs),
                rs.getLong("version_count"),
                rs.getLong("churn")), churnWeeks);
    }

    /** 从一行读一条规则。两处（清单 / 单条取用）共用，避免新增列时改一处漏一处。 */
    private static MemoryRule row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MemoryRule(
                rs.getString("rule_id"),
                rs.getString("cluster_key"),
                rs.getInt("version"),
                rs.getString("body"),
                state(rs.getString("state")),
                rs.getLong("hit_count"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs, "demoted_at"),
                instant(rs, "hardened_at"));
    }

    /** 可空时间戳 → Instant；列为空返回 null（「没有印记」与「印记在纪元」不是一回事）。 */
    private static java.time.Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    /** state 是文本列，未知取值不抛异常（只读接口不该因一条脏数据整体失败）。 */
    private static MemoryRule.State state(String s) {
        try {
            return MemoryRule.State.valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 支撑度分布（J2）：按 turn 聚合行为形状 → 归入粗分桶 → 计数
    // 取数走 memory_turns（分析型访问的唯一入口，《分类记忆体》§4.2 零期），不再现算 memory_events 的 jsonb。
    // 特征口径与判据侧（TurnCriteriaRepository）同源：ioKinds = io_kind_mix 的键集，steps = action_seq 长度。
    // 两处若不同源，「规则从哪个桶起草」与「哪些 turn 命中规则」会静默对不上。
    // ------------------------------------------------------------------
    private static final String SQL_SUPPORT = """
            SELECT %s  AS io_kinds,
                   cardinality(action_seq) AS steps
            FROM memory_turns
            %s
            """;

    public List<RulesReport.SupportBucket> support() {
        return support(null);
    }

    /**
     * 支撑度分布，可选按任务类型（二级筛选）收窄。
     *
     * <p>{@code taskTypeFilter} 为 null/空白时不加谓词，结果与加筛选前逐字一致。
     * <b>桶键、固定桶序、{@code supported} 公式一律不动</b>——筛选只是把样本面筛窄后再数，
     * 闸门口径不因筛选而变。
     */
    public List<RulesReport.SupportBucket> support(String taskTypeFilter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String key : ShapeBucketer.BUCKET_ORDER) {
            counts.put(key, 0L);   // 固定桶序，空桶照报（缺哪个桶是信息，不是噪声）
        }
        String filter = taskTypeFilter == null ? "" : taskTypeFilter.strip();
        String sql = SQL_SUPPORT.formatted(ShapeBucketer.SQL_IO_KINDS,
                filter.isEmpty() ? "" : "WHERE task_type = ?");
        RowCallbackHandler count = rs -> {
            Object steps = rs.getObject("steps");
            Set<String> kinds = ShapeBucketer.kindsOf(rs.getArray("io_kinds"));
            String key = ShapeBucketer.bucketKey(new ShapeBucketer.TurnFeatures(
                    kinds, steps == null ? null : ((Number) steps).longValue()));
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        };
        if (filter.isEmpty()) {
            jdbc.query(sql, count);
        } else {
            jdbc.query(sql, count, filter);
        }
        List<RulesReport.SupportBucket> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            out.add(new RulesReport.SupportBucket(e.getKey(), e.getValue(), e.getValue() >= minSupport));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 任务类型分布（二级筛选的「选项清单」）
    // 刻意不收 taskType 过滤参数：这一块本身是筛选项的全貌，永远全量——
    // 否则一旦筛上就再也看不到别的取值，等于把自己锁死在一个选项里。
    // trusted_turns 与判据侧的 TRUSTED_SOURCE 同口径：导入路径是代理口径，只作参考（附录 B5.1）。
    // ------------------------------------------------------------------
    private static final String SQL_TASK_TYPES = """
            SELECT COALESCE(task_type, '__unclassified__') AS task_type,
                   COUNT(DISTINCT session_id)              AS sessions,
                   COUNT(*)                                AS turns,
                   COUNT(*) FILTER (WHERE source <> 'import') AS trusted_turns
            FROM memory_turns
            GROUP BY 1
            ORDER BY 3 DESC
            """;

    /** 未分类的哨兵值（SQL 侧）；转成 null 返回，避免与真实取值混淆。 */
    private static final String UNCLASSIFIED = "__unclassified__";

    public List<RulesReport.TaskTypeStat> taskTypeDistribution() {
        return jdbc.query(SQL_TASK_TYPES, (rs, n) -> {
            String raw = rs.getString("task_type");
            boolean unclassified = UNCLASSIFIED.equals(raw);
            return new RulesReport.TaskTypeStat(
                    unclassified ? null : raw,
                    unclassified ? "未分类" : TaskType.fromOrOther(raw).label(),
                    rs.getLong("sessions"),
                    rs.getLong("turns"),
                    rs.getLong("trusted_turns"));
        });
    }

    /** 已有规则的簇键集合。判据侧用它判断一个 turn「会不会命中规则」（影子 would-trigger，§4.6）。 */
    public Set<String> clusterKeys() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT cluster_key FROM memory_rules", String.class));
    }

    /** 分桶口径（可审计）。 */
    public RulesReport.Bucketing bucketing() {
        return new RulesReport.Bucketing(
                "支撑度 = 按行为形状粗分桶的 turn 计数（§4.4），不是语义聚类；桶键由真实事件列生成。",
                ShapeBucketer.DIMENSIONS);
    }

    public RulesReport.Config config() {
        return new RulesReport.Config(minSupport, churnWeeks);
    }

    // ------------------------------------------------------------------
    // 起草（§4.5）：找出「支撑度已达标、但还没有规则」的簇
    // 支撑度不足的簇不进候选（§4.4 第 4 条：低于最小支撑度直接丢弃）；
    // 已有规则的簇也不进——rule_id 即簇键，一条簇一条规则谱系，重跑起草不会重复产规则。
    // ------------------------------------------------------------------
    public List<ClusterProfile> draftCandidates() {
        Set<String> taken = new HashSet<>(jdbc.queryForList("SELECT cluster_key FROM memory_rules", String.class));
        List<ClusterProfile> out = new ArrayList<>();
        for (RulesReport.SupportBucket bucket : support()) {
            if (bucket.supported() && !taken.contains(bucket.key())) {
                out.add(new ClusterProfile(bucket.key(), bucket.turns()));
            }
        }
        return out;
    }

    /** 写入一条 SHADOW 草稿。ON CONFLICT DO NOTHING 是并发下的兜底，正常路径由 draftCandidates 去重。 */
    private static final String SQL_INSERT_SHADOW = """
            INSERT INTO memory_rules (rule_id, cluster_key, version, body, state, hit_count)
            VALUES (?, ?, 1, ?, 'SHADOW', 0)
            ON CONFLICT (rule_id, version) DO NOTHING
            """;

    public int insertShadow(String clusterKey, String body) {
        return jdbc.update(SQL_INSERT_SHADOW, clusterKey, clusterKey, body);
    }

    // ------------------------------------------------------------------
    // 单条规则取用 + 晋升落库（§4.11）
    // ------------------------------------------------------------------

    private static final String SQL_RULE_LATEST = """
            SELECT rule_id, cluster_key, version, body, state, hit_count, created_at,
                   demoted_at, hardened_at
            FROM memory_rules
            WHERE rule_id = ?
            ORDER BY version DESC
            LIMIT 1
            """;

    /** 取一条规则的最新版本。闸门要判的就是「这条规则现在这一版」。 */
    public Optional<MemoryRule> latest(String ruleId) {
        return jdbc.query(SQL_RULE_LATEST, (rs, n) -> row(rs), ruleId).stream().findFirst();
    }

    /**
     * 把一条规则从 SHADOW 推进到 LIVE。<b>闸门判决为 pass 之后才允许调用</b>。
     *
     * <p>状态由 SQL 谓词本身守住三件事，不靠调用方自觉：
     * <ul>
     *   <li>{@code state = 'SHADOW'}：单向推进，重复晋升不会二次生效；</li>
     *   <li>{@code demoted_at IS NULL}：<b>被降级的规则不能再自动晋升</b>（§2.6「降级只降不升」/ A5）。
     *       没有这一条，降级把规则打回 SHADOW 后，闸门下一次通过就会把它自动推回去——规则反复横跳。
     *       回升必须走人工路径 {@link #clearDemotion} 清掉印记；</li>
     *   <li>{@code state <> 'RETIRED'} 的效果同前——已停用的规则不能被这条路复活。</li>
     * </ul>
     * 命中 0 行即表示「当前不允许晋升」，由调用方如实报告，而不是静默成功。
     *
     * <p>不新增版本行：§2.7 的版本号是<b>内容</b>变更的产物，晋升只改状态、不改内容。
     * 故 churn 不因晋升而变化——「稳」那项要等内容真的改过（{@link #revise}）才有得算。
     */
    private static final String SQL_PROMOTE = """
            UPDATE memory_rules SET state = 'LIVE'
            WHERE rule_id = ? AND version = ? AND state = 'SHADOW' AND demoted_at IS NULL
            """;

    public int promote(String ruleId, int version) {
        return jdbc.update(SQL_PROMOTE, ruleId, version);
    }

    // ------------------------------------------------------------------
    // 版本推进（§2.7）
    // ------------------------------------------------------------------

    private static final String SQL_LATEST_VERSION = """
            SELECT version FROM memory_rules WHERE rule_id = ? ORDER BY version DESC LIMIT 1
            """;
    private static final String SQL_RETIRE_OLD = """
            UPDATE memory_rules SET state = 'RETIRED'
            WHERE rule_id = ? AND version = ? AND state <> 'RETIRED'
            """;
    private static final String SQL_INSERT_NEW_VERSION = """
            INSERT INTO memory_rules (rule_id, cluster_key, version, body, state, hit_count)
            VALUES (?, ?, ?, ?, 'SHADOW', 0)
            ON CONFLICT (rule_id, version) DO NOTHING
            """;

    /**
     * 规则内容变更 → 产生新版本（§2.7）。旧版本置 RETIRED 保留可查，新版本回到 SHADOW。
     *
     * <p><b>为什么新版本回到 SHADOW</b>：内容变了就是一条新规则，得重新过闸门。若沿用 LIVE，
     * 等于「改内容」成了绕过闸门的后门（§4.11 纪律 1：LIVE 的唯一来源是 promote()）。
     *
     * <p>这三步必须在一个事务里（{@code RuleLifecycleService.revise} 标注 {@code @Transactional}）：
     * 中途失败会留下「旧版已停用、新版没建」的空洞，规则凭空消失。
     *
     * @return 新版本号；规则不存在时返回 -1（调用方据此如实报告，不静默建一条新的）
     */
    public int revise(String ruleId, String body) {
        List<Integer> latest = jdbc.queryForList(SQL_LATEST_VERSION, Integer.class, ruleId);
        if (latest.isEmpty()) {
            return -1;
        }
        int from = latest.get(0);
        int next = from + 1;
        jdbc.update(SQL_RETIRE_OLD, ruleId, from);
        String clusterKey = jdbc.queryForObject(
                "SELECT cluster_key FROM memory_rules WHERE rule_id = ? AND version = ?", String.class, ruleId, from);
        jdbc.update(SQL_INSERT_NEW_VERSION, ruleId, clusterKey, next, body);
        return next;
    }

    /** 当前最大版本号；规则不存在时返回 0。审计与控制器用它定位「现在这一版」。 */
    public int latestVersion(String ruleId) {
        List<Integer> latest = jdbc.queryForList(SQL_LATEST_VERSION, Integer.class, ruleId);
        return latest.isEmpty() ? 0 : latest.get(0);
    }

    // ------------------------------------------------------------------
    // 降级 / 停用 / 硬化（§2.6）
    // ------------------------------------------------------------------

    /**
     * 自动降级：LIVE → SHADOW，并写下 demoted_at 印记（§2.6）。
     *
     * <p>谓词 {@code state = 'LIVE'} 守住「只降」：SHADOW 无处可降、RETIRED 不能再降。
     * 写印记是为了让 {@link #promote} 拒收——只降不升。
     */
    private static final String SQL_DEMOTE = """
            UPDATE memory_rules SET state = 'SHADOW', demoted_at = now(), demote_reason = ?
            WHERE rule_id = ? AND version = ? AND state = 'LIVE'
            """;

    public int demote(String ruleId, int version, String reason) {
        return jdbc.update(SQL_DEMOTE, reason, ruleId, version);
    }

    /** 人工停用（§5.2 撤销）：SHADOW / LIVE → RETIRED。RETIRED 不可逆，故不再匹配。 */
    private static final String SQL_RETIRE = """
            UPDATE memory_rules SET state = 'RETIRED'
            WHERE rule_id = ? AND version = ? AND state <> 'RETIRED'
            """;

    public int retire(String ruleId, int version) {
        return jdbc.update(SQL_RETIRE, ruleId, version);
    }

    /**
     * 人工批准回升：清掉降级印记，使规则重新可被闸门审视（§2.6「回升必须人工批准」）。
     *
     * <p>只清印记、<b>不直接改 state</b>——回升仍然要走闸门（内容与判据都要重新看一遍）。
     * 这条路径的存在，正是「只降不升」不导致规则永久卡死的原因。
     */
    private static final String SQL_CLEAR_DEMOTION = """
            UPDATE memory_rules SET demoted_at = NULL, demote_reason = NULL
            WHERE rule_id = ? AND version = ?
            """;

    public int clearDemotion(String ruleId, int version) {
        return jdbc.update(SQL_CLEAR_DEMOTION, ruleId, version);
    }

    /** 标记硬化（§2.6 第三条，只提示不处置）。幂等：已硬化的不再覆盖时间戳。 */
    private static final String SQL_HARDEN = """
            UPDATE memory_rules SET hardened_at = now()
            WHERE rule_id = ? AND version = ? AND hardened_at IS NULL
            """;

    public int harden(String ruleId, int version) {
        return jdbc.update(SQL_HARDEN, ruleId, version);
    }

    // ------------------------------------------------------------------
    // 评估快照（§2.6 第三条的「评估周期」）
    // ------------------------------------------------------------------

    private static final String SQL_INSERT_EVAL = """
            INSERT INTO memory_rule_evals
                (rule_id, version, hit_share, token_delta, fail_delta, correction_rate, action)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    public void insertEval(EvalRow e) {
        jdbc.update(SQL_INSERT_EVAL, e.ruleId(), e.version(), e.hitShare(),
                e.tokenDelta(), e.failDelta(), e.correctionRate(), e.action());
    }

    /** 最近 n 次评估读数，新的在前。硬化判定回看它。 */
    private static final String SQL_RECENT_EVALS = """
            SELECT rule_id, version, hit_share, token_delta, fail_delta, correction_rate, action
            FROM memory_rule_evals
            WHERE rule_id = ? AND version = ?
            ORDER BY evaluated_at DESC, eval_id DESC
            LIMIT ?
            """;

    public List<EvalRow> recentEvals(String ruleId, int version, int limit) {
        return jdbc.query(SQL_RECENT_EVALS, (rs, n) -> new EvalRow(
                rs.getString("rule_id"), rs.getInt("version"),
                nullable(rs.getObject("hit_share")),
                nullable(rs.getObject("token_delta")),
                nullable(rs.getObject("fail_delta")),
                nullable(rs.getObject("correction_rate")),
                rs.getString("action")), ruleId, version, limit);
    }

    /** 一次评估的读数（§2.6 三条限制的输入 + 当时采取的 action）。可空列 = 该次不可测。 */
    public record EvalRow(
            String ruleId, int version, Double hitShare, Double tokenDelta,
            Double failDelta, Double correctionRate, String action) {
    }

    private static Double nullable(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
