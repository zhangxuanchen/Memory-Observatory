package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillEffectReport;
import io.memobservatory.exoskeleton.model.SkillEffectReport.Bucket;
import io.memobservatory.exoskeleton.model.SkillEffectReport.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能效果验证闸门：拿「采纳前 / 采纳后」两组读数，判这份技能到底有没有把事情做好。
 *
 * <p><b>为什么是纯函数</b>：与 {@code PromotionGate} 同一纪律——判决必须可复现、可单测，
 * 不碰数据库、不改状态。「撤回采纳」这种动作由人拍板，本类只出结论（判决与动作分离）。
 *
 * <p><b>为什么是「不劣化即通过」而不是「必须改善」</b>：规则线的闸门要求四项同时改善，那是因为
 * 规则会<b>自动</b>上线，必须挡住退化解。技能不同——它是人看过、点头才启用的，闸门要回答的不是
 * 「够不够格上线」，而是「用了之后有没有变差」。故三种结局：
 *
 * <pre>
 * ① 任一组样本不足        —— 不可测（不假装通过，也不假装失败）
 * ② 任一项明显变差        —— 不通过（改善与否另说，变差就是变差）
 * ③ 三项都不劣化          —— 通过（持平也算通过；是否真有提升由 tripwire 喊给人看）
 * </pre>
 *
 * <p><b>三项判据全部「越低越好」</b>：准（隐式纠正率）、失败率、回合数——都是「少一点更好」。
 * 这省掉了方向参数，也避免了「把方向写反」这类静默错误。
 */
@Service
public class SkillEffectGate {

    /** 每组的最小样本量：低于它则该组判不了（与 skills.min-turns / min-support 同一精神）。 */
    private final int minSample;
    /** 比率类判据的绝对容忍（百分点）：0.05 表示采纳后高 5 个百分点以内仍算持平。 */
    private final double rateTol;
    /** 回合数判据的相对容忍：0.10 表示采纳后多 10% 以内仍算持平。 */
    private final double roundsTol;

    public SkillEffectGate(
            @Value("${mo.exoskeleton.skills.gate.min-sample:30}") int minSample,
            @Value("${mo.exoskeleton.skills.gate.rate-tolerance:0.05}") double rateTol,
            @Value("${mo.exoskeleton.skills.gate.rounds-tolerance:0.10}") double roundsTol) {
        this.minSample = minSample;
        this.rateTol = rateTol;
        this.roundsTol = roundsTol;
    }

    /** 最小样本门槛（服务层给读数打 measurable 标记时要用同一个值，不能各写各的）。 */
    public int minSample() {
        return minSample;
    }

    /** 一次判决的结果：三项明细 + 结论 + 监控信号（不含落库、不含动作）。 */
    public record Verdict(List<Item> items, String verdict, List<String> tripwires) {
    }

    /**
     * 过闸门。{@code before / after} 为采纳前 / 采纳后两组读数，任一为 null（无该组数据）记不可测。
     */
    public Verdict check(Bucket before, Bucket after) {
        boolean measurable = before != null && after != null
                && before.measurable() && after.measurable();

        List<Item> items = new ArrayList<>();
        items.add(rate("准·隐式纠正率", before, after, measurable,
                b -> b.correctedRate()));
        items.add(rate("失败率", before, after, measurable,
                b -> b.failureRate()));
        items.add(item("回合数", before, after, measurable,
                roundsTol, true, b -> b.rounds()));

        boolean fail = items.stream().anyMatch(i -> SkillEffectReport.FAIL.equals(i.outcome()));
        String verdict = !measurable ? SkillEffectReport.UNKNOWN
                : (fail ? SkillEffectReport.FAIL : SkillEffectReport.PASS);

        return new Verdict(items, verdict, measurable ? tripwires(before, after) : List.of());
    }

    // ------------------------------------------------------------------
    // 单项判定：三态
    // ------------------------------------------------------------------

    /** 取一组读数的某个「越低越好」的量。 */
    private interface Get {
        double of(Bucket b);
    }

    /** 比率类：绝对容忍（百分点）。 */
    private Item rate(String label, Bucket before, Bucket after, boolean measurable, Get get) {
        return item(label, before, after, measurable, rateTol, false, get);
    }

    /**
     * 单项判决。{@code relative=true} 时容忍按比例算（回合数），否则按绝对值算（比率）。
     * 任一组不可测 → UNKNOWN；采纳后越过容忍上限 → FAIL；否则 PASS（持平或变好都算过）。
     */
    private static Item item(String label, Bucket before, Bucket after, boolean measurable,
                             double tol, boolean relative, Get get) {
        if (!measurable) {
            return new Item(label, null, null, SkillEffectReport.UNKNOWN,
                    label + "：不可测（两组样本至少一组未达门槛）");
        }
        double b = get.of(before);
        double a = get.of(after);
        double limit = relative ? b * (1 + tol) : b + tol;
        boolean pass = a <= limit;
        String text = relative
                ? "%s（%.2f → %.2f，容忍 +%.0f%%）".formatted(label, b, a, tol * 100)
                : "%s（%.1f%% → %.1f%%，容忍 +%.0f 个百分点）".formatted(label, b * 100, a * 100, tol * 100);
        return new Item(label, b, a, pass ? SkillEffectReport.PASS : SkillEffectReport.FAIL, text);
    }

    // ------------------------------------------------------------------
    // tripwire：监控，不是拦截。只把「形状很怪」的数字喊给人看。
    // ------------------------------------------------------------------

    private static List<String> tripwires(Bucket before, Bucket after) {
        List<String> out = new ArrayList<>();
        if (after.correctedRate() >= before.correctedRate()) {
            out.add("重问率没有下降 —— 技能可能没起作用，撤回采纳也许更划算");
        }
        if (after.failureRate() >= before.failureRate() && after.failureRate() > 0) {
            out.add("失败迹象没有减少 —— 技能没有把「卡住」消掉");
        }
        if (after.rounds() >= before.rounds()) {
            out.add("回合数没有减少 —— 技能没省下步骤");
        }
        return out;
    }
}