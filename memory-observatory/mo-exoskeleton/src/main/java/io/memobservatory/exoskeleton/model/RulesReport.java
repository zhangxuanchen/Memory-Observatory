package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 「当前情况」一屏所需的全部只读数据（对应设计文档 §D2 接口二）。
 *
 * 八块：规则清单（含版本数与 churn）+ 支撑度分布（J2）+ J1 隐式纠正率 + holdout 对照组交叉指标
 * + 分桶口径 + 生效参数 + 任务类型分布 + <b>簇级判据</b>。holdout 与 J1 由三期 P0
 * （{@code criteria.CriteriaService}）补上——没有对照组，所有「规则有效」的结论都只是选择偏差。
 * 簇级判据（{@link ClusterCriteria}）把四项下放到每个桶并逐簇标注可得性，理由见其 javadoc。
 * 刻意不含待决卡片（属后续版本，§F）。
 *
 * 注：支撑度按**行为形状粗分桶**统计（§4.4），不是语义聚类——桶键由真实事件列生成，口径见 {@link Bucketing}。
 */
public record RulesReport(
        List<RuleView> rules,
        List<SupportBucket> support,
        CorrectionRate correctionRate,
        Holdout holdout,
        Bucketing bucketing,
        Config config,
        TaskTypes taskTypes,
        ClusterCriteria clusters
) {
    /** 一条规则的当前版本 + 版本数 + churn。旧版本行保留在库里，此处只报最新版本（§2.7）。 */
    public record RuleView(
            MemoryRule rule,
            long versionCount,     // 该规则历史版本总数
            long churn             // 观察窗内（churn-weeks 周）的版本数
    ) {
    }

    /** 一个粗分桶的支撑度：turns 为落桶 turn 数，supported = turns ≥ 最小支撑度（§3.2）。 */
    public record SupportBucket(String key, long turns, boolean supported) {
    }

    /** 分桶口径（可审计）：说明支撑度是按什么维度分出来的，避免被误读成语义簇。 */
    public record Bucketing(String note, List<String> dimensions) {
    }

    /**
     * J1：隐式纠正率（§2.4）。
     * rate = corrections / transitions；transitions 为同 session 内、间隔在窗口内的相邻 turn 对数。
     * 低于 1% 即视为量纲太稀疏（§3.6 止损第 2 条）。
     *
     * {@code source} 标明只采信哪条路径：导入路径的历史缺原始提问（jsonl 只有 intent 摘要），
     * 该路径的纠正率是真值的下界且结构性不可补（附录 B5），故不计入本块，只出现在重算报告的 bySource 里。
     */
    public record CorrectionRate(
            int windowMinutes,
            double similarityThreshold,
            String source,
            long transitions,
            long corrections,
            double rate,
            String note
    ) {
    }

    /**
     * 对照组（§2.5 / §4.6）：命中组 vs 裸跑组。
     * 这是「规则让它变好了吗」唯一可伪证的写法——没有对照，所有结论都只是选择偏差。
     * holdout 优先于一切：进对照组的 turn 永不注入规则，故对照组与命中组互斥。
     */
    public record Holdout(
            double ratio,          // 配置的裸跑比例
            String basis,          // 分配依据（可审计）
            List<GroupStat> groups
    ) {
    }

    /**
     * 一个分组的观测。
     * {@code correctedRate} 是「被隐式纠正的 turn 占比」，与 J1 的相邻对口径不同，两者不可混读。
     */
    public record GroupStat(
            String group,
            long turns,
            long corrected,
            double correctedRate,
            double avgToken,
            double avgLatency
    ) {
    }

    /** 生效参数快照，便于对照与复现。 */
    public record Config(int minSupport, int churnWeeks) {
    }

    /**
     * 二级筛选维度：任务类型分布（session 粒度，由 LLM 判定）。
     *
     * <p>与 {@link SupportBucket} 的关系：那一块按<b>行为形状</b>分桶（规则从哪个桶起草），
     * 这一块按<b>在做什么任务</b>分组（会话层面的定性分类）。两者正交，互不参与对方的判定：
     * 任务类型<b>只作筛选维度，不作分簇键</b>——桶键与支撑度闸门口径不因它改变（E2 的推翻结论依然成立）。
     *
     * <p>本块<b>刻意不收筛选参数</b>：它是筛选项的全貌，收窄了就没法切换。
     */
    public record TaskTypes(List<TaskTypeStat> stats, String vocabulary, String note) {
    }

    /**
     * 一个任务类型的观测。
     * {@code taskType} 为 null 表示<b>未分类</b>（尚未跑到 / LLM 不可达 / 解析失败），
     * 与 {@code other}（判过、确实归不进）刻意分开——「没量到」不能当成「量到了」。
     * {@code trustedTurns} 只计非导入路径：导入路径的 user_text 是意图摘要而非原始提问，
     * 其分类是<b>代理口径</b>，只作参考（附录 B5.1）。
     */
    public record TaskTypeStat(
            String taskType,     // 封闭词表取值；null = 未分类
            String label,        // 中文标签（未分类时为「未分类」）
            long sessions,       // 该取值的 session 数（session 粒度的主计数）
            long turns,          // 落在该取值下的 turn 数
            long trustedTurns    // 其中非导入路径（可采信）的 turn 数
    ) {
    }

    /**
     * 簇级判据（§2.2 的「按簇切开看」）：把四项判据从全局下放到每个粗分桶。
     *
     * <p>存在的理由：判据在全局上出数，不代表在每个簇上都量得到。本块<b>逐簇标注哪几项真的可测</b>，
     * 免得把「报表里有个数」当成「指标可用」。
     *
     * <p>与 {@link SupportBucket} 的关系：那一块只报「落桶 turn 数」，本块在同样的桶上再算判据。
     * <b>桶键口径完全同源</b>（{@code ShapeBucketer}），不因本块而改变分桶与支撑度闸门。
     */
    public record ClusterCriteria(List<ClusterRow> rows, String note) {
    }

    /** 一个簇（粗分桶）上的判据观测。 */
    public record ClusterRow(
            String key,                       // 桶键 = 簇键，如 readonly|steps:4-10
            long turns,                       // 落桶 turn 数
            boolean supported,                // 支撑度是否达标（≥ minSupport，§3.2）
            boolean hasRule,                  // 该簇是否已有规则（含 SHADOW）
            long holdoutTurns,                // 其中被 holdout 路由拿走的（永不注入规则，§2.5）
            long transitions,                 // 簇内可比较的相邻 turn 对（两端同簇、同 session、窗口内）
            long corrections,                 // 其中判为隐式纠正的
            double correctionRate,            // 簇内隐式纠正率 = corrections / transitions
            double avgToken,                  // 约束项：簇内平均单位成本（token/turn）；晋升闸门的 ① 要用
            double hitAvgToken,               // 约束项：簇内<b>非</b> holdout 的平均成本（= 命中组，§2.6 限制二）
            double controlAvgToken,           // 约束项：簇内 holdout 的平均成本（= 该簇的对照组）
            List<Criterion> criteria          // 四项 + 约束的可得性（本块的核心）
    ) {
    }

    /**
     * 一项判据在某个簇上「量不量得到」。
     *
     * <p>{@code measurable=false} 时 {@code reason} 说明为何量不到——是<b>结构性的</b>（缺表 / 缺埋点），
     * 不是样本偶然为空。两者的区别必须写在响应里，否则看板读者会把「量不到」读成「值为 0」。
     */
    public record Criterion(String name, boolean measurable, String reason) {
    }

    /**
     * 一次起草的结果（§4.5）。只解决「规则从哪来」，不解决「规则能不能上线」——
     * 写入一律为 SHADOW，上线的唯一入口是晋升闸门（§4.11）。
     */
    public record DraftResult(
            int candidates,              // 支撑度达标、且尚无规则的簇数
            int created,                 // 本次真正写入的规则数
            List<DraftOutcome> outcomes,
            String note
    ) {
    }

    /** 单个簇的起草结果。body 为 null 表示撰写器对该簇没有对应策略（不编）。 */
    public record DraftOutcome(
            String clusterKey,
            long turns,
            String body,
            boolean written,
            String detail
    ) {
    }
}
