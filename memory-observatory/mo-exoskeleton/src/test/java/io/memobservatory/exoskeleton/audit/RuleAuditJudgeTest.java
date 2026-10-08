package io.memobservatory.exoskeleton.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuleAuditJudge} 的判决测试：§2.6 三道限制 + §2.7 churn 撤回。
 *
 * <p>纯函数、不起 Spring 上下文（与 {@code PromotionGateTest} 同一写法）。参数取 §2.6 起点值：
 * 命中面 20%、约束项容忍 20%、churn 上限 3。
 *
 * <p>重点不在「正常时返回 NONE」，而在四条边：<b>边界不越界、优先级、缺读数不误判、互不干扰</b>。
 */
class RuleAuditJudgeTest {

    private final RuleAuditJudge judge = new RuleAuditJudge(0.20, 0.20, 3);

    private static final List<Double> NONE_HISTORY = List.of();

    @Test
    void 三项都正常时不动手() {
        var d = judge.judge(0.05, 0.10, null, 1, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 命中面越界则退回影子() {
        var d = judge.judge(0.21, null, null, 1, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.DEMOTE_HIT, d.action());
    }

    @Test
    void 命中面正好等于上限不算越界() {
        // 阈值是「> 20%」，等于 20% 不触发——边界必须按「越界」而不是「达到」来读
        var d = judge.judge(0.20, null, null, 1, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 命中组成本比对照组差则退回影子() {
        var d = judge.judge(0.05, 0.25, null, 1, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.DEMOTE_COST, d.action());
        assertTrue(d.reason().contains("单位成本"));
    }

    @Test
    void 命中组失败率比对照组差也退回影子() {
        var d = judge.judge(0.05, null, 0.25, 1, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.DEMOTE_COST, d.action());
        assertTrue(d.reason().contains("失败率"));
    }

    @Test
    void churn过高则撤回而不是继续调() {
        var d = judge.judge(0.05, null, null, 3, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.RETIRE_CHURN, d.action());
    }

    @Test
    void 连续两个评估周期准无改善则标记硬化() {
        // 新→旧：0.30, 0.30, 0.30；两次相邻比较都无改善
        var d = judge.judge(0.05, null, null, 1, List.of(0.30, 0.30, 0.30));
        assertEquals(RuleAuditJudge.Action.HARDEN, d.action());
    }

    @Test
    void 准有改善则不硬化() {
        // 0.20 → 0.25 → 0.30（新在前），最近一期在改善
        var d = judge.judge(0.05, null, null, 1, List.of(0.20, 0.25, 0.30));
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 读数不足三次不硬化() {
        var d = judge.judge(0.05, null, null, 1, List.of(0.30, 0.30));
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 读数里有不可测则不硬化() {
        // 中间一期为 null：缺读数不能当作「无改善」，否则「没量到」会被误判成「规则无用」
        var d = judge.judge(0.05, null, null, 1, java.util.Arrays.asList(0.30, null, 0.30));
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 全部不可测时不产生任何处置() {
        var d = judge.judge(null, null, null, 1, List.of());
        assertEquals(RuleAuditJudge.Action.NONE, d.action());
    }

    @Test
    void 命中面越界优先于churn撤回() {
        // 安全性先于稳定性：爆炸半径过大时先退回影子，而不是直接撤回
        var d = judge.judge(0.50, null, null, 5, NONE_HISTORY);
        assertEquals(RuleAuditJudge.Action.DEMOTE_HIT, d.action());
    }
}
