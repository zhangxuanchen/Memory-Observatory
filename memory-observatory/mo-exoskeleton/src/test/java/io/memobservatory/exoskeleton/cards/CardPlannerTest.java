package io.memobservatory.exoskeleton.cards;

import io.memobservatory.exoskeleton.model.CardTemplate;
import io.memobservatory.exoskeleton.model.DecisionCard;
import io.memobservatory.exoskeleton.model.MemoryRule;
import io.memobservatory.exoskeleton.model.RulesReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成器的纯逻辑测试（§4.10.5 流水线）。
 *
 * <p>这一层的测试价值最高：模板选择、三档形状、合并同类、排序截断、抑制——都是「判断」，
 * 且不需要数据库就能验。取数与落库在 {@code DecisionCardService} / {@code CardRepository}，
 * 由真实库实测覆盖。
 */
class CardPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final int MIN_SUPPORT = 30;
    private static final double HIT_SHARE_MAX = 0.20;
    private static final int CHURN_HIGH = 3;

    private final CardPlanner planner = new CardPlanner();

    // ------------------------------------------------------------------
    // 输入构造
    // ------------------------------------------------------------------

    private CardPlanner.Input input(List<RulesReport.SupportBucket> support,
                                    List<RulesReport.RuleView> rules,
                                    List<RulesReport.ClusterRow> clusters,
                                    Set<String> evaluated,
                                    Set<String> suppressed,
                                    long totalTurns,
                                    int cap) {
        return new CardPlanner.Input(support, rules, clusters, evaluated, suppressed,
                totalTurns, MIN_SUPPORT, HIT_SHARE_MAX, CHURN_HIGH, cap, NOW);
    }

    private static RulesReport.SupportBucket bucket(String key, long turns) {
        return new RulesReport.SupportBucket(key, turns, turns >= MIN_SUPPORT);
    }

    private static RulesReport.ClusterRow cluster(String key, long turns, long holdout,
                                                  long transitions, double rate,
                                                  double hitToken, double controlToken) {
        return new RulesReport.ClusterRow(key, turns, turns >= MIN_SUPPORT, true, holdout,
                transitions, transitions, rate,
                turns == 0 ? 0.0 : ((double) ((turns - holdout) * hitToken + holdout * controlToken)) / turns,
                hitToken, controlToken, List.of());
    }

    private static RulesReport.RuleView rule(String clusterKey, MemoryRule.State state, Instant hardenedAt,
                                             long churn) {
        return new RulesReport.RuleView(
                new MemoryRule(clusterKey, clusterKey, 1, "body", state, 0, NOW, null, hardenedAt),
                1 + churn, churn);
    }

    private static DecisionCard only(CardPlanner.Plan plan) {
        assertEquals(1, plan.cards().size(), "本次应恰好出一张卡");
        return plan.cards().get(0).card();
    }

    private static String reasonOf(CardPlanner.Plan plan, String template) {
        return plan.templates().stream()
                .filter(s -> s.template().equals(template))
                .findFirst().orElseThrow().reason();
    }

    // ------------------------------------------------------------------
    // 模板库与三档形状（§4.10.1 / §4.10.2 约束二）
    // ------------------------------------------------------------------

    @Test
    void 六个模板都在返回里_未触发的给出原因() {
        CardPlanner.Plan plan = planner.plan(input(List.of(), List.of(), List.of(), Set.of(), Set.of(), 0, 5));

        assertEquals(6, CardPlanner.templates().size(), "模板库固定六个");
        assertEquals(6, plan.templates().size(), "六个模板都要在响应里出现，缺哪个是信息");
        for (CardPlanner.TemplateStatus s : plan.templates()) {
            assertFalse(s.triggered(), "空输入下六个模板都不该触发");
            assertFalse(s.reason().isBlank(), s.template() + " 未触发时必须给出原因，不能只报 false");
        }
        assertTrue(plan.cards().isEmpty());
    }

    @Test
    void 每张卡恰好三档且默认项是不动的那一档() {
        // T1：影子 + 该簇「准」可测 + 影子期已评估过
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:1-3", 40)),
                List.of(rule("readonly|steps:1-3", MemoryRule.State.SHADOW, null, 1)),
                List.of(cluster("readonly|steps:1-3", 40, 8, 6, 0.5, 1200, 1000)),
                Set.of("readonly|steps:1-3"), Set.of(), 40, 5));

        DecisionCard card = only(plan);
        assertEquals(CardTemplate.T1, card.template());
        assertEquals(3, card.options().size(), "选项固定三档（§4.10.2 约束二）");
        assertEquals(List.of("approve", "defer", "reject"),
                card.options().stream().map(DecisionCard.Option::key).toList(),
                "三档位置固定，人只学一次");
        assertEquals("defer", card.defaultOption(), "默认项必须是「不动」（§4.10.2 约束一）");
        assertTrue(card.options().stream().allMatch(o -> !o.effect().isBlank()),
                "不给裸选项：每个选项自带后果（§4.10.2 约束四）");
    }

    @Test
    void 证据只填真有的数_成本无对照时不编那一行() {
        // holdout = 0 ⇒ 没有对照组 ⇒ 成本差不可测 ⇒ 证据里不该出现「单位成本 vs 对照」
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:1-3", 40)),
                List.of(rule("readonly|steps:1-3", MemoryRule.State.SHADOW, null, 1)),
                List.of(cluster("readonly|steps:1-3", 40, 0, 6, 0.5, 1200, 0)),
                Set.of("readonly|steps:1-3"), Set.of(), 40, 5));

        DecisionCard card = only(plan);
        assertTrue(card.evidence().stream().noneMatch(e -> e.label().contains("单位成本")),
                "没有对照的数字不上卡（§4.10.4）");
        assertTrue(card.note().contains("不可测"), "缺项要写在 note 里，不能把「量不到」读成「没问题」");
    }

    // ------------------------------------------------------------------
    // T1 触发条件
    // ------------------------------------------------------------------

    @Test
    void T1需要影子期已过一轮评估_否则不出卡() {
        List<RulesReport.SupportBucket> support = List.of(bucket("readonly|steps:1-3", 40));
        List<RulesReport.RuleView> rules = List.of(rule("readonly|steps:1-3", MemoryRule.State.SHADOW, null, 1));
        List<RulesReport.ClusterRow> clusters = List.of(cluster("readonly|steps:1-3", 40, 8, 6, 0.5, 1200, 1000));

        CardPlanner.Plan withoutEvals = planner.plan(input(support, rules, clusters, Set.of(), Set.of(), 40, 5));
        assertTrue(withoutEvals.cards().isEmpty(), "没有评估快照 ⇒ 影子期还没过一轮，谈不上「影子期结束」");

        CardPlanner.Plan withEvals = planner.plan(input(support, rules, clusters,
                Set.of("readonly|steps:1-3"), Set.of(), 40, 5));
        assertEquals(1, withEvals.cards().size());
    }

    @Test
    void T1的准量不到时不出卡() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:1-3", 40)),
                List.of(rule("readonly|steps:1-3", MemoryRule.State.SHADOW, null, 1)),
                List.of(cluster("readonly|steps:1-3", 40, 8, 0, 0.0, 1200, 1000)),   // transitions = 0
                Set.of("readonly|steps:1-3"), Set.of(), 40, 5));

        assertTrue(plan.cards().isEmpty(), "相邻对为 0 ⇒ 证据不齐，不出卡（不是「证据说没问题」）");
    }

    // ------------------------------------------------------------------
    // T5 触发与合并同类（§4.10.5）
    // ------------------------------------------------------------------

    @Test
    void T5在支撑度不足的簇上触发_三档是继续收集放宽门槛放弃() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("writeonly|steps:1-3", 4)),
                List.of(), List.of(), Set.of(), Set.of(), 4, 5));

        DecisionCard card = only(plan);
        assertEquals(CardTemplate.T5, card.template());
        assertEquals(List.of("collect", "relax", "drop"),
                card.options().stream().map(DecisionCard.Option::key).toList());
        assertEquals("collect", card.defaultOption(), "默认是「继续收集」= 不动");
        assertEquals("4", card.evidence().get(0).value());
        assertEquals("26", card.evidence().get(2).value(), "缺口 = 门槛 - 当前样本");
    }

    @Test
    void 证据一致的多个候选合并成一张卡() {
        // 两个簇的样本数都是 1 ⇒ 证据逐字一致 ⇒ 合并（§4.10.5「这 5 条证据一致，是否一并采纳」）
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("none|steps:1-3", 1), bucket("writeonly|steps:1-3", 1)),
                List.of(), List.of(), Set.of(), Set.of(), 2, 5));

        assertEquals(1, plan.cards().size(), "两个证据一致的候选应合成一张");
        assertEquals(1, plan.merged(), "被合并掉的候选数要能对上账");
        DecisionCard card = plan.cards().get(0).card();
        assertEquals(2, card.subjects().size(), "合并卡覆盖两个簇");
        assertTrue(card.cardId().startsWith("T5:merged:"), "合并卡的 id 走 merged 前缀");
        assertTrue(card.note().contains("合并同类"), "note 要写明这是合成卡，不然读者会以为是单个簇的卡");
    }

    @Test
    void 证据不同的候选不合并() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("none|steps:1-3", 1), bucket("writeonly|steps:1-3", 7)),
                List.of(), List.of(), Set.of(), Set.of(), 8, 5));

        assertEquals(2, plan.cards().size());
        assertEquals(0, plan.merged());
    }

    @Test
    void 被驳回过的簇不再出卡() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("none|steps:1-3", 1), bucket("writeonly|steps:1-3", 1)),
                List.of(), List.of(), Set.of(), Set.of("none|steps:1-3"), 2, 5));

        assertEquals(1, plan.cards().size(), "被驳回的簇退出候选（§4.10.4 的 reject 后果）");
        assertEquals(List.of("writeonly|steps:1-3"), plan.cards().get(0).card().subjects());
    }

    // ------------------------------------------------------------------
    // T3 硬化处置
    // ------------------------------------------------------------------

    @Test
    void T3只在已硬化且仍在注入的规则上触发_默认保留待复核() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:4-10", 50)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, NOW, 1)),
                List.of(cluster("readonly|steps:4-10", 50, 10, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 50, 5));

        DecisionCard card = only(plan);
        assertEquals(CardTemplate.T3, card.template());
        assertEquals(List.of("retire", "narrow", "hold"),
                card.options().stream().map(DecisionCard.Option::key).toList());
        assertEquals("hold", card.defaultOption(), "默认是「保留待复核」= 不动");
    }

    @Test
    void 已硬化的规则退回影子后不再出T3() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:4-10", 50)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.SHADOW, NOW, 1)),
                List.of(cluster("readonly|steps:4-10", 50, 10, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 50, 5));

        assertTrue(plan.cards().isEmpty(), "影子规则不注入、无爆炸半径，无需人工处置");
    }

    // ------------------------------------------------------------------
    // T2 档位调整
    // ------------------------------------------------------------------

    @Test
    void T2在命中面接近上限时触发() {
        // turns 100 / holdout 20 ⇒ 命中面 80/200 ... 需按 totalTurns 算：命中面 = (100-20)/200 = 0.4 太大
        // 取 totalTurns = 500：(100-20)/500 = 0.16 ∈ [0.15, 0.20)
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:4-10", 100)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, null, 1)),
                List.of(cluster("readonly|steps:4-10", 100, 20, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 500, 5));

        DecisionCard card = only(plan);
        assertEquals(CardTemplate.T2, card.template());
        assertEquals(List.of("conservative", "hold", "aggressive"),
                card.options().stream().map(DecisionCard.Option::key).toList());
        assertEquals("hold", card.defaultOption());
        assertTrue(card.note().contains("不改配置"), "改配置的档位必须写明本版只记录，不让用户以为点了就生效");
    }

    @Test
    void 命中面越界时不出T2_越界归自动降级不归人() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("readonly|steps:4-10", 100)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, null, 1)),
                List.of(cluster("readonly|steps:4-10", 100, 20, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 300, 5));   // (100-20)/300 = 0.267 > 0.20

        assertTrue(plan.cards().isEmpty(), "越界由 §2.6 限制一自动退回影子，不该再问人");
    }

    @Test
    void T2未触发的原因按实际数据说_不一律断言无LIVE规则() {
        // 有 LIVE 规则，只是命中面很低：(100-20)/5000 = 1.6% < 15% ⇒ 不触发
        CardPlanner.Plan withLive = planner.plan(input(
                List.of(bucket("readonly|steps:4-10", 100)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, null, 1)),
                List.of(cluster("readonly|steps:4-10", 100, 20, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 5000, 5));
        assertTrue(reasonOf(withLive, "T2").contains("1 条 LIVE"),
                "有 LIVE 规则就不能说「当前无 LIVE 规则」——未触发原因也要按实际数据说（不编证据）");

        // 完全没有规则时，才是真的「无 LIVE 规则」
        CardPlanner.Plan noRule = planner.plan(input(List.of(), List.of(), List.of(), Set.of(), Set.of(), 0, 5));
        assertTrue(reasonOf(noRule, "T2").contains("当前无 LIVE 规则"));
    }

    // ------------------------------------------------------------------
    // 排序截断（§4.10.5）
    // ------------------------------------------------------------------

    @Test
    void 截断按严重度优先_超出上限的排队() {
        // 一个 T3（严重度 30）+ 一个 T5（10），cap = 1 ⇒ 只出 T3，另一个排队
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("writeonly|steps:1-3", 4)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, NOW, 1)),
                List.of(cluster("readonly|steps:4-10", 50, 10, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 50, 1));

        assertEquals(1, plan.cards().size(), "单轮上限 %d".formatted(1));
        assertEquals(CardTemplate.T3, plan.cards().get(0).card().template(), "严重度高的先出");
        assertEquals(1, plan.queued(), "被截断的要报出来，不能静默丢掉");
    }

    @Test
    void 截断后模板触发数按实际出卡回填() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("writeonly|steps:1-3", 4)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, NOW, 1)),
                List.of(cluster("readonly|steps:4-10", 50, 10, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 50, 1));

        CardPlanner.TemplateStatus t5 = plan.templates().stream()
                .filter(s -> s.template().equals("T5")).findFirst().orElseThrow();
        assertEquals(0, t5.candidates(), "T5 被截断掉就不该报「触发了 1 张」");
        assertFalse(t5.triggered());
    }

    // ------------------------------------------------------------------
    // 抑制集与可再出集（语义一致性）
    // ------------------------------------------------------------------

    @Test
    void 抑制集与可再出集不重叠() {
        Set<String> overlap = new java.util.HashSet<>(CardPlanner.SUPPRESSING_ANSWERS);
        overlap.retainAll(CardPlanner.REISSUABLE_ANSWERS);
        assertTrue(overlap.isEmpty(),
                "重叠会让「驳回后又被重新提出」静默发生：" + overlap);
    }

    @Test
    void 抑制集的答案与卡面文案是一致的() {
        // reject / drop 的 effect 文案说「不再重复提议」，代码就得把它们放进抑制集
        assertTrue(CardPlanner.SUPPRESSING_ANSWERS.contains("reject"));
        assertTrue(CardPlanner.SUPPRESSING_ANSWERS.contains("drop"));
        assertTrue(CardPlanner.REISSUABLE_ANSWERS.contains("defer"), "defer 的后果是「下个周期再报」");
    }

    // ------------------------------------------------------------------
    // T4 / T6：建桩但不编证据
    // ------------------------------------------------------------------

    @Test
    void T4与T6未实现时如实说明_不编样本() {
        CardPlanner.Plan plan = planner.plan(input(
                List.of(bucket("writeonly|steps:1-3", 4)),
                List.of(rule("readonly|steps:4-10", MemoryRule.State.LIVE, NOW, 1)),
                List.of(cluster("readonly|steps:4-10", 50, 10, 0, 0.0, 1100, 900)),
                Set.of(), Set.of(), 50, 5));

        for (String t : List.of("T4", "T6")) {
            CardPlanner.TemplateStatus s = plan.templates().stream()
                    .filter(x -> x.template().equals(t)).findFirst().orElseThrow();
            assertFalse(s.triggered(), t + " 当前不该触发");
            assertFalse(s.reason().isBlank(), t + " 未触发必须给出原因（未实现 / 缺数据），不编证据");
            assertTrue(s.reason().contains("未实现"), t + " 原因要说清是「未实现」而不是「没问题」");
        }
    }

    @Test
    void 卡片id对同一簇稳定_重复生成是刷新不是新增() {
        CardPlanner.Input in = input(
                List.of(bucket("writeonly|steps:1-3", 4)),
                List.of(), List.of(), Set.of(), Set.of(), 4, 5);

        String first = only(planner.plan(in)).cardId();
        String second = only(planner.plan(in)).cardId();
        assertEquals(first, second);
        assertNotNull(first);
        assertEquals("T5:cluster:writeonly|steps:1-3", first);
    }
}