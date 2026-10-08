package io.memobservatory.exoskeleton.promote;

import io.memobservatory.exoskeleton.model.GateVector;
import io.memobservatory.exoskeleton.model.PromotionDecision.Layer;
import io.memobservatory.exoskeleton.model.PromotionDecision.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 晋升闸门的三层结构（§4.11）。
 *
 * <p>这里复现 lab05 {@code gate.py} 的四个场景——一个诚实的改善被放行，三个退化解分别死在 ①②③。
 * 测试的价值不在「函数返回了 false」，而在**死在第几层**：那是结构决定的，不是设计出来的。
 * 若哪天有人往闸门里塞了一个「作弊检测器」，这四条断言会先坏掉，因为三层的分工会被搅乱。
 *
 * <p>纯函数测试，不起 Spring 上下文：闸门刻意不依赖数据库，正是为了能这样验。
 */
class PromotionGateTest {

    private static final double MIN_EFFECT = 0.05;
    private static final double CHURN_TOL = 0.0;
    private static final double TOL_TOKEN = 0.10;
    private static final double TOL_FAIL = 0.0;

    private final PromotionGate gate = new PromotionGate(MIN_EFFECT, CHURN_TOL, TOL_TOKEN, TOL_FAIL);

    /** 四项 + 约束的取值顺序：准 / 速忘 / 复读 / 托底 / churn / token / 失败率。 */
    private static GateVector v(double correction, double forget, double reuse,
                                double coverage, double churn, double token, double fail) {
        return new GateVector(correction, forget, reuse, coverage, churn, token, fail);
    }

    /** 基线：判据不动、成本与失败率不动。 */
    private static final GateVector BASE = v(0.30, 0.40, 0.30, 0.00, 0.30, 1000, 0.15);

    /** 把只看纠正率的对照组向量补全（其余位无关紧要，但要真实，免得掩盖方向错误）。 */
    private static GateVector ho(double correction) {
        return v(correction, 0.40, 0.30, 0.00, 0.30, 1000, 0.15);
    }

    @Test
    @DisplayName("S0 诚实改善：三层全通，放行")
    void honestImprovementPasses() {
        GateVector after = v(0.12, 0.20, 0.60, 0.35, 0.20, 1030, 0.13);

        PromotionGate.Verdict verdict = gate.check(BASE, after, ho(0.30), ho(0.12));

        assertTrue(verdict.passed(), "四项同向改善且约束未回退，应放行。实际：" + verdict.layers() + verdict.tripwires());
        assertEquals(3, verdict.layers().size(), "三层都要走到");
        assertTrue(verdict.layers().stream().allMatch(Layer::ok));
    }

    @Test
    @DisplayName("S1 什么都不写：死在 ② 四项（不看后面的层）")
    void writeNothingDiesAtFourItems() {
        // token 掉了 30%，代价是记忆产出被清空——速忘率拉满、复读率归零、托底归零。
        GateVector after = v(0.30, 1.00, 0.00, 0.00, 0.00, 700, 0.15);

        PromotionGate.Verdict verdict = gate.check(BASE, after, ho(0.30), ho(0.30));

        assertFalse(verdict.passed());
        assertEquals(2, verdict.layers().size(), "① 通过后应停在 ②，不再往下走");
        assertEquals("② 四项同时改善", verdict.layers().get(1).name());
        assertEquals(Outcome.FAIL, verdict.layers().get(1).outcome());
    }

    @Test
    @DisplayName("S2 拒绝难任务：① ② 全绿，死在 ③ holdout 不复现")
    void refuseHardTasksDiesAtHoldout() {
        // 整体全绿——但对照组没被治理，数字一点没变。
        GateVector after = v(0.05, 0.20, 0.60, 0.10, 0.20, 700, 0.05);
        GateVector control = ho(0.30);

        PromotionGate.Verdict verdict = gate.check(BASE, after, control, control);

        assertFalse(verdict.passed());
        assertEquals(3, verdict.layers().size(), "必须走到 ③ 才拦得住——这正是「归因的唯一入口」");
        assertEquals("③ holdout 对照复现", verdict.layers().get(2).name());
        assertEquals(Outcome.FAIL, verdict.layers().get(2).outcome());
    }

    @Test
    @DisplayName("S3 写垃圾自己 READ：死在 ① 硬约束，后面根本不用看")
    void selfFulfillingReuseDiesAtConstraints() {
        // 复读率从 30% 刷到 95%，全靠自产自销——成本涨 60%。
        GateVector after = v(0.28, 0.70, 0.95, 0.30, 0.20, 1600, 0.15);

        PromotionGate.Verdict verdict = gate.check(BASE, after, ho(0.30), ho(0.28));

        assertFalse(verdict.passed());
        assertEquals(1, verdict.layers().size(), "一票否决先于一切，必须在 ① 就停");
        assertEquals("① 硬约束（一票否决）", verdict.layers().get(0).name());
        assertEquals(Outcome.FAIL, verdict.layers().get(0).outcome());
    }

    @Test
    @DisplayName("三条退化解死在不同层——这是结构，不是巧合")
    void threeDegenerateSolutionsDieAtThreeDifferentLayers() {
        List<String> diedAt = List.of(
                gate.check(BASE, v(0.28, 0.70, 0.95, 0.30, 0.20, 1600, 0.15), ho(0.30), ho(0.28))
                        .layers().get(0).name(),
                gate.check(BASE, v(0.30, 1.00, 0.00, 0.00, 0.00, 700, 0.15), ho(0.30), ho(0.30))
                        .layers().get(1).name(),
                gate.check(BASE, v(0.05, 0.20, 0.60, 0.10, 0.20, 700, 0.05), ho(0.30), ho(0.30))
                        .layers().get(2).name());

        assertEquals(3, diedAt.stream().distinct().count(), "三条路必须落在三个不同的层：" + diedAt);
    }

    @Test
    @DisplayName("缺证据不放行：四项不可测时不得通过")
    void unknownBlocksPromotion() {
        // 复现本库现状（附录 B7）：只有成本取得到，四项全不可测。
        GateVector onlyToken = new GateVector(null, null, null, null, null, 1000.0, null);

        PromotionGate.Verdict verdict = gate.check(onlyToken, onlyToken, null, null);

        assertFalse(verdict.passed(), "「没测」不等于「没问题」，必须拦");
        assertTrue(verdict.layers().stream().anyMatch(l -> l.outcome() == Outcome.UNKNOWN),
                "拦住的原因应是不可测，而不是回退");
    }

    @Test
    @DisplayName("tripwire 只喊不拦：S3 的极端形状要能被看见")
    void tripwiresReportButDoNotBlock() {
        GateVector after = v(0.28, 0.95, 0.95, 0.00, 0.20, 1600, 0.15);

        PromotionGate.Verdict verdict = gate.check(BASE, after, ho(0.30), ho(0.28));

        assertFalse(verdict.passed());
        assertTrue(verdict.tripwires().stream().anyMatch(t -> t.contains("写后速忘率")), verdict.tripwires().toString());
        assertTrue(verdict.tripwires().stream().anyMatch(t -> t.contains("复读率")), verdict.tripwires().toString());
        assertTrue(verdict.tripwires().stream().anyMatch(t -> t.contains("托底占比")), verdict.tripwires().toString());
    }
}
