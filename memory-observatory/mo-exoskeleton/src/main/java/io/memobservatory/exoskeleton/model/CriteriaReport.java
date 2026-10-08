package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 一次判据重算的结果（三期 P0）。
 *
 * 只解决「判据算得出来」，不解决「规则能不能上线」——上线仍只走晋升闸门（§4.11）。
 * 重算是幂等的：同一批 turn、同一套参数，回写结果一致（holdout 分配是确定性哈希，§2.5）。
 */
public record CriteriaReport(
        long turns,             // 参与判定的 turn 总数
        long corrected,         // 判为「被隐式纠正」的 turn 数（只写可采信路径）
        long holdout,           // 判为对照组的 turn 数
        long written,           // 实际回写行数
        long transitionPairs,   // 可采信路径下参与相似度比较的相邻 turn 对数
        List<SourceStat> bySource,
        String note
) {

    /**
     * 按来源分路径出数。导入路径拿不到原始提问（jsonl 只有 intent 摘要），
     * 该路径的纠正率是**真值的下界**且结构性不可补（附录 B5），故 trustworthy=false，
     * 它的数字只作参考、不进 headline。
     */
    public record SourceStat(
            String source,
            long turns,
            long measurableTurns,   // 文本可判的 turn 数（导入路径为 0）
            long corrections,
            double rate,
            boolean trustworthy
    ) {
    }
}
