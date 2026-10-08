package io.memobservatory.exoskeleton.criteria;

import io.memobservatory.exoskeleton.cluster.HoldoutRouter;
import io.memobservatory.exoskeleton.cluster.ShapeBucketer;
import io.memobservatory.exoskeleton.criteria.CorrectionDetector.PrevTurn;
import io.memobservatory.exoskeleton.model.CriteriaReport;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import io.memobservatory.exoskeleton.storage.TurnCriteriaRepository;
import io.memobservatory.exoskeleton.storage.TurnCriteriaRepository.Score;
import io.memobservatory.exoskeleton.storage.TurnCriteriaRepository.TurnRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判据编排（三期 P0）：隐式纠正检测 + holdout 路由 + 对照取数。
 *
 * 三件事共用一次 {@link TurnCriteriaRepository#loadTurns()}：
 * <ol>
 *   <li>{@link #recompute()}：算 corrected 与 is_holdout 并回写，返回重算报告；</li>
 *   <li>{@link #correctionRate()}：J1 head line，只采信可采信路径（§2.4 / 附录 B5）；</li>
 *   <li>{@link #holdout()}：命中组 vs 对照组交叉指标（§2.5），这是「规则有效吗」唯一可伪证的写法。</li>
 * </ol>
 *
 * 刻意不做：不注入规则、不改 state、不碰晋升闸门。判据只负责「看得见」，上线是闸门的事（§4.11）。
 */
@Service
public class CriteriaService {

    /**
     * 可采信的来源路径。
     * 导入路径的 user_text 是 intent 摘要而非原始提问，判据在该路径上语义不成立（附录 B5），
     * 它的数字只作**下界参考**，不进 headline、不回写。
     */
    private static final String TRUSTED_SOURCE = "workbench";

    static final String GROUP_HIT = "命中组";
    static final String GROUP_CONTROL = "对照组";
    static final String GROUP_NOMATCH = "未命中组";

    private final TurnCriteriaRepository turns;
    private final RuleRepository rules;
    private final CorrectionDetector detector;
    private final HoldoutRouter router;

    @Value("${mo.exoskeleton.correction-window-minutes:10}")
    private int windowMinutes;
    @Value("${mo.exoskeleton.correction-similarity:0.75}")
    private double similarityThreshold;
    @Value("${mo.exoskeleton.min-support:30}")
    private int minSupport;

    public CriteriaService(TurnCriteriaRepository turns, RuleRepository rules,
                           CorrectionDetector detector, HoldoutRouter router) {
        this.turns = turns;
        this.rules = rules;
        this.detector = detector;
        this.router = router;
    }

    // ==================================================================
    // 重算：算 + 回写
    // ==================================================================

    public CriteriaReport recompute() {
        Scoring s = score(turns.loadTurns());
        int written = turns.writeBack(s.scores);

        List<CriteriaReport.SourceStat> bySource = new ArrayList<>();
        for (Map.Entry<String, long[]> e : s.bySource.entrySet()) {
            long[] v = e.getValue();   // {turns, measurable, transitions, corrections}
            boolean trustworthy = TRUSTED_SOURCE.equals(e.getKey());
            bySource.add(new CriteriaReport.SourceStat(
                    e.getKey(), v[0], v[1], v[3],
                    v[2] == 0 ? 0.0 : (double) v[3] / v[2],
                    trustworthy));
        }

        return new CriteriaReport(
                s.turnCount, s.correctedCount, s.holdoutCount, written, s.transitions,
                bySource,
                "corrected 只回写可采信路径（" + TRUSTED_SOURCE + "）；导入路径缺原始提问，其数字是真值的下界"
                        + "（附录 B5），只作参考。is_holdout 由确定性哈希分配，重跑结果一致。");
    }

    // ==================================================================
    // J1 headline 与对照分组（GET 用，共用一次读）
    // ==================================================================

    /** 一次读同时产出两块，避免 3268 行 turn 被同一次 GET 读两遍。 */
    public record CriteriaView(RulesReport.CorrectionRate correctionRate, RulesReport.Holdout holdout) {
    }

    public CriteriaView view() {
        return view(null);
    }

    /**
     * 带二级筛选（任务类型）的视图。
     *
     * <p>{@code taskTypeFilter} 为 null/空白时不加任何谓词，结果与不带筛选<b>逐字一致</b>。
     * 非空时 J1 与对照分组只统计该任务类型的 turn——桶键与闸门公式都不动，只是样本面被筛窄。
     * 注意 {@code recompute()} 永远全量：判定回写不该受筛选影响。
     */
    public CriteriaView view(String taskTypeFilter) {
        List<TurnRow> rows = turns.loadTurns(taskTypeFilter);
        Scoring s = score(rows);

        String note = s.transitions == 0
                ? "无可采信样本：" + TRUSTED_SOURCE + " 路径下没有形成可比较的相邻 turn 对。"
                        + "导入路径的数字见重算报告的 bySource，但它是下界、不可采信（附录 B5）。"
                : "只统计 " + TRUSTED_SOURCE + " 路径；导入路径不计入（附录 B5）。";

        RulesReport.CorrectionRate rate = new RulesReport.CorrectionRate(
                windowMinutes, similarityThreshold, TRUSTED_SOURCE,
                s.transitions, s.corrections,
                s.transitions == 0 ? 0.0 : (double) s.corrections / s.transitions,
                note);

        return new CriteriaView(rate, holdoutOf(rows));
    }

    /** 命中组 / 对照组 / 未命中组。holdout 优先于一切，故对照组与命中组互斥（§4.6 第 1 条）。 */
    private RulesReport.Holdout holdoutOf(List<TurnRow> rows) {
        Set<String> ruleKeys = rules.clusterKeys();

        Map<String, long[]> agg = new LinkedHashMap<>();   // 固定组序，空组照报
        for (String g : List.of(GROUP_HIT, GROUP_CONTROL, GROUP_NOMATCH)) {
            agg.put(g, new long[4]);   // {turns, corrected, tokenSum, latencySum}
        }

        for (TurnRow t : rows) {
            String g;
            if (t.holdout()) {
                g = GROUP_CONTROL;
            } else if (ruleKeys.contains(bucketKey(t))) {
                g = GROUP_HIT;
            } else {
                g = GROUP_NOMATCH;
            }
            long[] a = agg.get(g);
            a[0]++;
            if (t.corrected()) {
                a[1]++;
            }
            a[2] += t.tokenTotal();
            a[3] += t.latencyMs();
        }

        List<RulesReport.GroupStat> groups = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            groups.add(new RulesReport.GroupStat(
                    e.getKey(), a[0], a[1],
                    a[0] == 0 ? 0.0 : (double) a[1] / a[0],
                    a[0] == 0 ? 0.0 : (double) a[2] / a[0],
                    a[0] == 0 ? 0.0 : (double) a[3] / a[0]));
        }

        return new RulesReport.Holdout(router.ratio(), router.basis(), groups);
    }

    // ==================================================================
    // 簇级判据（按簇切开看，§2.2）
    // ==================================================================

    /**
     * 把判据从全局下放到每个粗分桶，并逐簇标注四项里哪几项真的量得到（附录 B7.3）。
     *
     * <p><b>为什么要有这一块</b>：全局出数不等于每簇可测。「准」在全局只有极少数可采信对，
     * 摊到 20 个桶多半是 0；「记忆有效性 / 多 / 稳」三项在真实库上结构性不可测（B7.1 / B7.2）。
     * 若只报一个全局数，看板读者会把「量不到」读成「值为 0」。故本块的价值一半在数字、一半在**可得性标注**。
     *
     * <p>刻意全量：{@code task_type} 只作筛选维度、不作分簇键（A13），故本块不收筛选参数——
     * 桶键与支撑度闸门口径一律不因本块改变。
     */
    public RulesReport.ClusterCriteria clusters() {
        Scoring s = score(turns.loadTurns());
        Set<String> ruleKeys = rules.clusterKeys();

        List<RulesReport.ClusterRow> out = new ArrayList<>(ShapeBucketer.BUCKET_ORDER.size());
        for (String key : ShapeBucketer.BUCKET_ORDER) {   // 固定桶序，空桶照报（缺哪个桶是信息）
            long[] c = s.byCluster.getOrDefault(key, new long[6]);
            long turns = c[0];
            long holdoutTurns = c[1];
            long transitions = c[2];
            long corrections = c[3];
            long hitTurns = turns - holdoutTurns;          // 非 holdout = 该簇的命中组（§2.6 限制二）
            out.add(new RulesReport.ClusterRow(
                    key, turns, turns >= minSupport, ruleKeys.contains(key),
                    holdoutTurns, transitions, corrections,
                    transitions == 0 ? 0.0 : (double) corrections / transitions,
                    turns == 0 ? 0.0 : (double) c[4] / turns,
                    hitTurns == 0 ? 0.0 : (double) (c[4] - c[5]) / hitTurns,
                    holdoutTurns == 0 ? 0.0 : (double) c[5] / holdoutTurns,
                    criteriaOf(key, transitions)));
        }

        String note = "桶键与支撑度口径同源（ShapeBucketer），本块不改分桶、不改闸门。"
                + "「簇内隐式纠正率」要求相邻 turn 对的两端落进同一个桶；只采信 " + TRUSTED_SOURCE
                + " 路径（附录 B5）。criteria 逐项标注可得性——measurable=false 是结构性缺数据，不是值为 0。";
        return new RulesReport.ClusterCriteria(out, note);
    }

    /**
     * 四项 + 约束在<b>本簇</b>上的可得性。
     *
     * <p>只有「准」随簇变化（取决于该簇内可采信相邻对是否为 0）；
     * 其余三项本期在**任何簇**上都不可测——那是全局的结构性缺陷（B7.1 / B7.2），
     * 逐簇重复声明是为了让每一行都能独立读，不必回头查全局说明。
     */
    private static List<RulesReport.Criterion> criteriaOf(String key, long transitions) {
        boolean correctionMeasurable = transitions > 0;
        return List.of(
                new RulesReport.Criterion("准·隐式纠正率", correctionMeasurable,
                        correctionMeasurable
                                ? "簇内可比较相邻 turn 对 n=" + transitions + "（只采信 " + TRUSTED_SOURCE + " 路径）"
                                : "簇内没有可比较的相邻 turn 对；可采信路径的样本量不足，本簇该项量不到（附录 B7）"),
                new RulesReport.Criterion("记忆有效性·速忘率/复读率", false,
                        "memory_events 是按 action 展开的伪事件流、无记忆生命周期，按 §4.3 ②③ 算出来的是噪声（B7.1）"),
                new RulesReport.Criterion("多·规则托底占比", false,
                        "规则全停 SHADOW、hit_count 从无写入、无命中日志表，真命中取不到（B7.2）"),
                new RulesReport.Criterion("稳·跨周 churn", false,
                        "无版本历史表，且写入把 version 写死为 1，churn ≡ 1（B7.2）"),
                new RulesReport.Criterion("约束·成本/延迟/失败率", false,
                        "数据齐（token_total / latency_ms / outcome），但判据侧尚未实现取数"));
    }

    // ==================================================================
    // 一次扫描：分组、配对、判定
    // ==================================================================

    private record Scoring(
            List<Score> scores,
            Map<String, long[]> bySource,   // {turns, measurable, transitions, corrections}
            Map<String, long[]> byCluster,  // 桶键 → {turns, holdoutTurns, transitions, corrections, tokenSum, holdoutTokenSum}
            long turnCount,
            long holdoutCount,
            long correctedCount,
            long transitions,               // 只含可采信路径
            long corrections
    ) {
    }

    private Scoring score(List<TurnRow> rows) {
        Map<String, long[]> bySource = new LinkedHashMap<>();
        Map<String, long[]> byCluster = new LinkedHashMap<>();
        Set<String> correctedIds = new LinkedHashSet<>();
        List<Score> scores = new ArrayList<>(rows.size());

        long holdoutCount = 0;
        for (TurnRow t : rows) {
            String src = t.source() == null ? "unknown" : t.source();
            long[] v = bySource.computeIfAbsent(src, k -> new long[4]);
            v[0]++;
            if (measurable(t)) {
                v[1]++;
            }
            boolean ho = router.isHoldout(t.agentId(), t.turnId());
            if (ho) {
                holdoutCount++;
            }
            long[] c = byCluster.computeIfAbsent(bucketKey(t), k -> new long[6]);
            c[0]++;
            if (ho) {
                c[1]++;
                c[5] += t.tokenTotal();   // 簇内 holdout 的 token 合计，供 §2.6 限制二的对照组用
            }
            c[4] += t.tokenTotal();   // 簇内 token 合计，供约束项（闸门 ① / 限制二）用
            scores.add(new Score(t.turnId(), false, ho));   // corrected 稍后按配对结果覆盖
        }

        long transitions = 0;
        long corrections = 0;
        for (int i = 1; i < rows.size(); i++) {
            TurnRow a = rows.get(i - 1);
            TurnRow b = rows.get(i);
            if (!a.sessionId().equals(b.sessionId()) || a.startedAt() == null || b.startedAt() == null) {
                continue;
            }
            long gap = Duration.between(a.startedAt(), b.startedAt()).toMinutes();
            if (gap <= 0 || gap > windowMinutes) {
                continue;
            }
            String src = a.source() == null ? "unknown" : a.source();
            long[] v = bySource.computeIfAbsent(src, k -> new long[4]);
            v[2]++;
            boolean corr = detector.isCorrection(new PrevTurn(a.userText(), a.actions(), a.outcome()), b.userText());
            // 簇内口径（§2.2「簇内隐式纠正率」）：两端必须落在同一个桶，否则这对相邻 turn 不属于任何单簇。
            // 采信面与 headline 同源——导入路径缺原始提问，其相似度判定不成立（附录 B5.1），故本处一并排除。
            boolean trusted = measurable(a) && measurable(b) && TRUSTED_SOURCE.equals(a.source());
            String ka = bucketKey(a);
            if (trusted && ka.equals(bucketKey(b))) {
                long[] c = byCluster.computeIfAbsent(ka, k -> new long[6]);
                c[2]++;
                if (corr) {
                    c[3]++;
                }
            }
            // 分母：可采信配对总数——与是否判为纠正无关（B8 修正）。
            // 旧实现把 transitions++ 写在下方 if (corr) 分支内，使 transitions ≡ corrections、
            // headline 的 rate 结构性恒为 1.0（不带信息）。分母本应是「该来源下窗口内的配对总数」。
            if (trusted) {
                transitions++;
            }
            if (!corr) {
                continue;
            }
            v[3]++;
            // 只有两端都来自可采信路径，才认这条纠正并回写（附录 B5）
            if (trusted) {
                correctedIds.add(a.turnId());
                corrections++;
            }
        }

        List<Score> finalScores = new ArrayList<>(scores.size());
        for (Score sc : scores) {
            finalScores.add(new Score(sc.turnId(), correctedIds.contains(sc.turnId()), sc.holdout()));
        }

        return new Scoring(finalScores, bySource, byCluster, rows.size(), holdoutCount,
                correctedIds.size(), transitions, corrections);
    }

    /** 文本可判：既要有原始提问，又要来自可采信路径。导入路径的 user_text 是 intent 摘要，不算可判。 */
    private static boolean measurable(TurnRow t) {
        return !"import".equals(t.source())
                && t.userText() != null && !t.userText().isBlank();
    }

    private static String bucketKey(TurnRow t) {
        return ShapeBucketer.bucketKey(new ShapeBucketer.TurnFeatures(t.ioKinds(), t.steps()));
    }
}
