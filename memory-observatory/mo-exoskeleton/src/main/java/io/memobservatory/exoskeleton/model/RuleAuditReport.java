package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 一次审计的结果（§2.6 三道限制 + §2.7 churn 撤回）。
 *
 * <p>与 {@code CriteriaReport}（只读重算）的区别：审计会<b>改状态</b>——把越界的 LIVE 规则退回影子、
 * 标记硬化、或按 churn 撤回。故每一行都要如实报出「判了什么」与「落没落成」，
 * 不能只报判决：规则不存在、状态不允许时落库会命中 0 行，那是「判了没落下」，与被静默吞掉不同。
 */
public record RuleAuditReport(
        int audited,          // 参与审计的规则数（全部，含 SHADOW/RETIRED）
        int demoted,          // 本次退回影子的
        int retired,          // 本次撤回的
        int hardened,         // 本次标记硬化的
        List<Row> rows,
        String note
) {
    /**
     * 一条规则的审计行。
     *
     * <p>可空字段（{@code hitShare} / {@code tokenDelta} / {@code failDelta} / {@code correctionRate}）
     * 为 null 时表示该项<b>不可测</b>，不是 0——与 {@code GateVector} 同一约定。
     */
    public record Row(
            String ruleId,
            String clusterKey,
            int version,
            String state,          // 审计时的状态（SHADOW | LIVE | RETIRED）
            Double hitShare,       // 命中面（§2.6 限制一）
            Double tokenDelta,     // 命中组单位成本相对对照组的差（§2.6 限制二）
            Double failDelta,      // 命中组失败率差（§2.6 限制二）；本期结构性不可测，恒 null
            Double correctionRate, // 该簇「准」
            long churn,            // 观察窗内版本数（§2.7）
            String action,         // 实际采取的动作：NONE | DEMOTE_HIT | DEMOTE_COST | HARDEN | RETIRE_CHURN
            int affected,          // 落库影响行数；0 且 action≠NONE 即「判了没落下」
            String reason          // 一句人话
    ) {
    }
}
