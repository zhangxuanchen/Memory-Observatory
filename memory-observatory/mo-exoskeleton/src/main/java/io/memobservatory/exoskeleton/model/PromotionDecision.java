package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 晋升闸门的一次判决（§4.11）。
 *
 * <p>它是**逐层的**：{@code layers} 按 ① 硬约束 → ② 四项 → ③ holdout 的顺序排列。
 * 遇到**不通过（FAIL）就停在那一层**——这不是为了省事，而是结论本身：三条退化解死在不同层
 * （写垃圾自刷死在 ①、什么都不写死在 ②、拒绝难任务死在 ③），「死在哪一层」比「被拦住」更有信息量。
 * 若一路走到底，这个结构性事实就被抹平了。
 *
 * <p>不可测（UNKNOWN）不停止，会继续往下判，好让读者看到后面几层的状态；但它**同样阻止放行**——
 * 最终 {@code passed} 要求每一层都通过。缺证据绝不放行，也不把「没测」当成「没问题」。
 */
public record PromotionDecision(
        String ruleId,
        String clusterKey,
        int version,
        String state,           // 判决时的状态（SHADOW / LIVE / RETIRED）
        boolean passed,
        List<Layer> layers,     // 只在第一个失败的层之前有内容（含该层）
        List<String> tripwires, // §4.11：监控，不拦截——只把「形状很怪」的数字喊出来给人看
        String note
) {

    /** 单项检查的结局。 */
    public enum Outcome {
        PASS, FAIL, UNKNOWN
    }

    /** 一层闸门：整层的结局 + 该层每项的明细。 */
    public record Layer(String name, Outcome outcome, List<String> details) {
        public boolean ok() {
            return outcome == Outcome.PASS;
        }
    }
}
