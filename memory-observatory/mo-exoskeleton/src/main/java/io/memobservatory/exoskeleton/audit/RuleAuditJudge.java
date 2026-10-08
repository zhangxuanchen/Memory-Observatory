package io.memobservatory.exoskeleton.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 爆炸半径与自动降级的<b>纯判决</b>（§2.6 三道限制 + §2.7 churn 撤回）。
 *
 * <p>与 {@code promote.PromotionGate} 同一设计：不碰数据库、无随机、无时间依赖，故能脱离 Spring
 * 直接 {@code new} 出来单测。参数走 {@code mo.exoskeleton.audit.*}，默认值即 §2.6 的起点值。
 *
 * <p>只管「该做什么」，不管「做没做到」。落库由 {@code RuleAuditService} 负责——
 * 判定与执行分开，才能让「判了但没落下」（规则不存在 / 状态不允许）如实暴露，而不是被静默吞掉。
 *
 * <p><b>与晋升闸门的关系</b>：闸门管「能不能上线」，本判定管「上线后要不要退回来」。
 * 两者都只对 LIVE 的规则有意义——影子规则不注入，没有爆炸半径可言。
 *
 * <p><b>可空约定</b>（与 {@code GateVector} 一致）：{@code null} = 该项不可测，<b>不是 0</b>。
 * 全部不可测时判 {@link Action#NONE}——「没测到」不产生任何处置，但读数会照实落进评估快照，
 * 免得事后把「没量到」读成「量到了、没问题」。
 */
@Service
public class RuleAuditJudge {

    /** §2.6 限制一：单条规则命中超过全部 turn 的这个比例 → 自动退回影子。 */
    private final double hitShareMax;
    /** §2.6 限制二：命中组的约束项比对照组差超过这个比例 → 自动退回影子。 */
    private final double constraintTol;
    /** §2.7：观察窗内版本数达到这个值 → 撤回（形状不稳，不该继续调）。 */
    private final int churnHigh;

    public RuleAuditJudge(
            @Value("${mo.exoskeleton.audit.hit-share-max:0.20}") double hitShareMax,
            @Value("${mo.exoskeleton.audit.constraint-tol:0.20}") double constraintTol,
            @Value("${mo.exoskeleton.audit.churn-high:3}") int churnHigh) {
        this.hitShareMax = hitShareMax;
        this.constraintTol = constraintTol;
        this.churnHigh = churnHigh;
    }

    /** 本次审计对该规则采取的动作。落进 {@code memory_rule_evals.action}。 */
    public enum Action {
        /** 没有越界，或全部不可测——不处置。 */
        NONE,
        /** §2.6 限制一：命中面越界 → 退回影子。 */
        DEMOTE_HIT,
        /** §2.6 限制二：命中组约束项比对照组差 → 退回影子。 */
        DEMOTE_COST,
        /** §2.6 第三条：连续两个评估周期「准」无改善 → 标记硬化（只提示）。 */
        HARDEN,
        /** §2.7：观察窗内版本数过高 → 撤回。 */
        RETIRE_CHURN
    }

    /** 一次判决：动作 + 一句人话（写进审计报告，也是「停在哪一条」的结论）。 */
    public record Decision(Action action, String reason) {
    }

    /**
     * 判决。调用方（{@code RuleAuditService}）只对 <b>LIVE</b> 的规则调用本方法——
     * 影子规则没上线，不存在爆炸半径。
     *
     * @param hitShare              命中面 = 该簇非 holdout turn / 全部 turn；null = 不可测
     * @param tokenDelta            命中组单位成本相对对照组的差（正数=更贵）；null = 不可测
     * @param failDelta             命中组失败率相对对照组的差；null = 不可测
     * @param churn                 观察窗内版本数（§2.7）
     * @param correctionRates       该规则「准」的评估序列，<b>含本次</b>、新的在前；元素可空
     */
    public Decision judge(Double hitShare, Double tokenDelta, Double failDelta,
                          long churn, List<Double> correctionRates) {
        // 限制一：先看爆炸半径。命中面越界是安全问题，先于其他判断执行。
        if (hitShare != null && hitShare > hitShareMax) {
            return new Decision(Action.DEMOTE_HIT,
                    "命中面 %.1f%% > 上限 %.0f%%：单条规则的爆炸半径过大，自动退回影子（§2.6 限制一）"
                            .formatted(hitShare * 100, hitShareMax * 100));
        }

        // 限制二：命中组的约束项比对照组差。成本与失败率任一越界即退回（一票否决的同一精神）。
        Decision cost = worseThanControl(tokenDelta, "单位成本");
        if (cost != null) {
            return cost;
        }
        Decision fail = worseThanControl(failDelta, "失败率");
        if (fail != null) {
            return fail;
        }

        // §2.7：版本数过高说明这一簇的形状本身不稳，正确的动作是撤回而不是继续调。
        if (churn >= churnHigh) {
            return new Decision(Action.RETIRE_CHURN,
                    "观察窗内 %d 版 ≥ %d：这一簇的形状不稳定，撤回规则而不是继续调它（§2.7）"
                            .formatted(churn, churnHigh));
        }

        // §2.6 第三条：连续两个评估周期「准」项无改善 → 标记硬化（只提示，不停用）。
        String hardening = hardeningReason(correctionRates);
        if (hardening != null) {
            return new Decision(Action.HARDEN, hardening);
        }

        return new Decision(Action.NONE, "三条限制均未触发；不可测的项记为 null，不当作「没问题」。");
    }

    /** 约束项越界判定：差超过容忍即退回。null = 不可测，跳过（不产生处置）。 */
    private Decision worseThanControl(Double delta, String label) {
        if (delta == null || delta <= constraintTol) {
            return null;
        }
        return new Decision(Action.DEMOTE_COST,
                "命中组的%s比对照组差 %.1f%% > 容忍 %.0f%%：规则让任务变差了，自动退回影子（§2.6 限制二）"
                        .formatted(label, delta * 100, constraintTol * 100));
    }

    /**
     * 硬化判定：回看最近三次读数，两次相邻比较都无改善。
     *
     * <p><b>方向</b>：「准」的改善是隐式纠正率<b>下降</b>（§2.2 标注为 ↓）。故「无改善」= 纠正率没有下降，
     * 即 {@code r[i] >= r[i+1]}（新在前）。把方向写反会让「已经在变好」的规则被标记硬化——
     * 这正是首版写反、被测试当场抓出的地方。
     *
     * <p>任一读数为 null（不可测）即返回 null——<b>缺读数不当作「无改善」</b>，
     * 否则「没量到」会被误判成「规则无用」。
     */
    private static String hardeningReason(List<Double> rates) {
        if (rates == null || rates.size() < 3) {
            return null;
        }
        Double r0 = rates.get(0);
        Double r1 = rates.get(1);
        Double r2 = rates.get(2);
        if (r0 == null || r1 == null || r2 == null) {
            return null;
        }
        if (r0 >= r1 && r1 >= r2) {
            return "连续两个评估周期「准」无改善（%s → %s → %s，新在前；纠正率未下降）：命中面在扩、重问率没动，标记硬化待人工复核（§2.6 第三条）"
                    .formatted(trim(r0), trim(r1), trim(r2));
        }
        return null;
    }

    private static String trim(double v) {
        return "%.3f".formatted(v);
    }
}
