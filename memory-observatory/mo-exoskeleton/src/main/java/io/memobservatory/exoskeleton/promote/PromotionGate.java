package io.memobservatory.exoskeleton.promote;

import io.memobservatory.exoskeleton.model.GateVector;
import io.memobservatory.exoskeleton.model.PromotionDecision.Layer;
import io.memobservatory.exoskeleton.model.PromotionDecision.Outcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 晋升闸门（§4.11）：规则从 SHADOW 升为 LIVE 的<b>唯一入口</b>，没有旁路。
 *
 * <p>它刻意<b>不检测作弊</b>。§2.2 的结论是：四项判据每一项单独优化，系统都会走到一个退化解上；
 * 若为此写四个检测器，就是打地鼠——每堵一条，就有没想到的一条漏在外面。这里的做法是
 * 「让作弊无法同时通过四项」：
 *
 * <pre>
 * ① 硬约束（成本 / 失败率）不回退        —— 一票否决，先于一切
 * ② 四项（准 / 记忆有效性 / 多 / 稳）同时改善 —— 少一项都不行
 * ③ 改善在 holdout 对照组里复现          —— 归因的唯一入口
 * </pre>
 *
 * <p>于是三条退化解<b>死在不同层</b>——这是结构决定的，不是设计出来的：
 *
 * <table>
 *   <tr><td>写垃圾然后自己 READ</td><td>① 硬约束</td><td>自产自销要把 token 吃上去</td></tr>
 *   <tr><td>什么都不写</td><td>② 四项</td><td>记忆有效性与「多」同时归零，四项不可能同向</td></tr>
 *   <tr><td>拒绝难任务</td><td>③ holdout</td><td>对照组没被治理，改善在那里不复现</td></tr>
 * </table>
 *
 * <p>本类是纯函数、无随机、无时间依赖：同一输入永远同一判决（与 lab05 {@code gate.py} 同序同参）。
 * 不碰数据库、不改状态——「放行」的落库由 {@link PromotionService} 在本类判决为 pass 之后执行。
 *
 * <p>与 lab05 的唯一差别：那边输入永远是实数值，这边可能缺值（附录 B7）。
 * 缺值记为 {@link Outcome#UNKNOWN}，<b>同样拦住</b>，但理由写明是「不可测」而非「回退」。
 */
@Service
public class PromotionGate {

    /** 四项每项至少要改善的量（绝对百分点）。 */
    private final double minEffect;
    /** churn 只要求不上升，故容忍为 0。 */
    private final double churnTol;
    /** 单位成本相对容忍（§2.6 的 20% 是「退回影子」阈值，这里 10% 是「不许上线」阈值）。 */
    private final double tolToken;
    /** 失败率绝对容忍。 */
    private final double tolFail;

    public PromotionGate(
            @Value("${mo.exoskeleton.gate.min-effect:0.05}") double minEffect,
            @Value("${mo.exoskeleton.gate.churn-tol:0.0}") double churnTol,
            @Value("${mo.exoskeleton.gate.constraint-tol-token:0.10}") double tolToken,
            @Value("${mo.exoskeleton.gate.constraint-tol-fail:0.0}") double tolFail) {
        this.minEffect = minEffect;
        this.churnTol = churnTol;
        this.tolToken = tolToken;
        this.tolFail = tolFail;
    }

    /** 一次判决的结果：是否放行 + 逐层明细 + 监控信号（不含落库）。 */
    public record Verdict(boolean passed, List<Layer> layers, List<String> tripwires) {
    }

    /**
     * 过闸门。{@code before / after} 是受治理面的前后两个评估周期；{@code hoBefore / hoAfter} 是对照组。
     *
     * <p>停止规则只有一条：**遇到 FAIL 就停在那一层**（含该层明细一起带回）——「死在第几层」就是结论。
     * UNKNOWN（不可测）不停止，继续往下判，这样读者能看到后面几层的状态；但它**同样阻止放行**：
     * 最终 {@code passed} 要求每一层都是 PASS。缺证据绝不放行，也不假装「没测」等于「没问题」。
     */
    public Verdict check(GateVector before, GateVector after, GateVector hoBefore, GateVector hoAfter) {
        List<Layer> layers = new ArrayList<>();
        boolean anyUnknown = false;

        // ① 硬约束：一票否决，先于一切。它不看四项做得多好。
        Check token = notWorse("单位成本（token/turn）", before.avgToken(), after.avgToken(), tolToken, true);
        Check fail = notWorse("失败率", before.failureRate(), after.failureRate(), tolFail, false);
        Layer l1 = layer("① 硬约束（一票否决）", List.of(token, fail));
        layers.add(l1);
        if (l1.outcome() == Outcome.FAIL) {
            return new Verdict(false, layers, tripwires(before, after));
        }
        anyUnknown |= l1.outcome() == Outcome.UNKNOWN;

        // ② 四项同时改善：少一项都不行——这就是「不写检测器」的落点。
        Check correction = improve("准·隐式纠正率", before.correctionRate(), after.correctionRate(), true);
        Check forget = improve("记忆有效性·写后速忘率", before.forgetRate(), after.forgetRate(), true);
        Check reuse = improve("记忆有效性·复读率", before.reuseRate(), after.reuseRate(), false);
        Check coverage = improve("多·规则托底占比", before.coverage(), after.coverage(), false);
        Check churn = notWorse("稳·规则 churn", before.churn(), after.churn(), churnTol, false);
        Layer l2 = layer("② 四项同时改善", List.of(
                correction,
                and("记忆有效性", forget, reuse),
                coverage,
                churn));
        layers.add(l2);
        if (l2.outcome() == Outcome.FAIL) {
            return new Verdict(false, layers, tripwires(before, after));
        }
        anyUnknown |= l2.outcome() == Outcome.UNKNOWN;

        // ③ holdout：归因的唯一入口。没有对照，改善就不可归因。
        Check ho;
        if (hoBefore == null || hoAfter == null) {
            ho = new Check(Outcome.UNKNOWN, "对照组：没有对照组向量，改善不可归因");
        } else {
            ho = improve("对照组·隐式纠正率", hoBefore.correctionRate(), hoAfter.correctionRate(), true);
        }
        Layer l3 = layer("③ holdout 对照复现", List.of(ho));
        layers.add(l3);

        boolean passed = !anyUnknown && l3.outcome() == Outcome.PASS;
        return new Verdict(passed, layers, tripwires(before, after));
    }

    // ------------------------------------------------------------------
    // 单项判定：三态（通过 / 不通过 / 不可测）
    // ------------------------------------------------------------------

    private record Check(Outcome outcome, String text) {
    }

    /** 越高越好 / 越低越好，且必须改善至少 minEffect。缺任一取值则不可测。 */
    private Check improve(String label, Double before, Double after, boolean lowerIsBetter) {
        if (before == null || after == null) {
            return new Check(Outcome.UNKNOWN, label + "：不可测（缺评估周期取值）");
        }
        double delta = after - before;
        boolean pass = lowerIsBetter ? delta <= -minEffect : delta >= minEffect;
        String dir = lowerIsBetter ? "↓" : "↑";
        String text = "%s（%s %.3f → %.3f，要求 %s≥%.2f）".formatted(label, dir, before, after, dir, minEffect);
        return new Check(pass ? Outcome.PASS : Outcome.FAIL, text);
    }

    /** 只要求不回退（硬约束 / churn）。{@code relative=true} 时容忍按比例算（成本），否则按绝对值算（失败率）。 */
    private static Check notWorse(String label, Double before, Double after, double tol, boolean relative) {
        if (before == null || after == null) {
            return new Check(Outcome.UNKNOWN, label + "：不可测（缺评估周期取值）");
        }
        double limit = relative ? before * (1 + tol) : before + tol;
        boolean pass = after <= limit;
        String text = relative
                ? "%s（%.0f → %.0f，容忍 +%.0f%%）".formatted(label, before, after, tol * 100)
                : "%s（%.3f → %.3f，容忍 +%.2f）".formatted(label, before, after, tol);
        return new Check(pass ? Outcome.PASS : Outcome.FAIL, text);
    }

    /**
     * 把两项合成一项（记忆有效性 = 速忘率 ↓ 且 复读率 ↑）。
     * 只要有硬性不通过就是失败；否则只要有一项测不了，整体就不可测——不能拿半张卷子判及格。
     */
    private static Check and(String label, Check a, Check b) {
        Outcome o = a.outcome() == Outcome.FAIL || b.outcome() == Outcome.FAIL
                ? Outcome.FAIL
                : (a.outcome() == Outcome.UNKNOWN || b.outcome() == Outcome.UNKNOWN
                        ? Outcome.UNKNOWN : Outcome.PASS);
        return new Check(o, label + "：" + a.text() + "；" + b.text());
    }

    /** 一层的结局：全通过才通过；有不可测而无失败时，记不可测。 */
    private static Layer layer(String name, List<Check> checks) {
        Outcome o = checks.stream().anyMatch(c -> c.outcome() == Outcome.FAIL)
                ? Outcome.FAIL
                : (checks.stream().anyMatch(c -> c.outcome() == Outcome.UNKNOWN) ? Outcome.UNKNOWN : Outcome.PASS);
        return new Layer(name, o, checks.stream().map(Check::text).toList());
    }

    // ------------------------------------------------------------------
    // tripwire（§4.11）：监控，不是拦截。
    // 只把「形状很怪」的数字喊出来给人看；不要在这里加第二层检测器，那就是打地鼠的开始。
    // ------------------------------------------------------------------

    private static List<String> tripwires(GateVector before, GateVector after) {
        List<String> out = new ArrayList<>();
        if (after.forgetRate() != null && after.forgetRate() >= 0.90) {
            out.add("写后速忘率 %.0f%% —— 写进去的记忆几乎全废：只写不用，或写垃圾".formatted(after.forgetRate() * 100));
        }
        if (after.reuseRate() != null && after.reuseRate() >= 0.90) {
            out.add("记忆复读率 %.0f%% —— 异常高，警惕自产自销（写记忆再读回去刷分）".formatted(after.reuseRate() * 100));
        }
        if (after.coverage() != null && after.coverage() <= 0.01) {
            out.add("规则托底占比 %.0f%% —— 规则等于没有，警惕「什么都不写」式退化".formatted(after.coverage() * 100));
        }
        if (before.correctionRate() != null && after.correctionRate() != null
                && after.correctionRate() >= before.correctionRate()) {
            out.add("重问率没有下降 —— 规则可能无效，停在 L4 也许更划算（§3.6）");
        }
        return out;
    }
}
