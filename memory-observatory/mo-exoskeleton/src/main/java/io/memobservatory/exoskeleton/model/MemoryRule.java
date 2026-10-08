package io.memobservatory.exoskeleton.model;

import java.time.Instant;

/**
 * 一条记忆策略规则及其版本（对应设计文档 §4.2 的 memory_rules 表）。
 *
 * state 单向推进：SHADOW → LIVE → RETIRED；停用的旧版本保留可查，用来算 churn（§2.7）。
 * 规则文本只写记忆策略（该记什么 / 该忘什么 / 何时读回 / 用哪个工具），不写推理策略（§1.4）。
 * 注意：能不能从 SHADOW 走到 LIVE，不由本记录决定——唯一入口是晋升闸门（§4.11）。
 *
 * <p><b>降级印记</b>（{@code demotedAt}）：§2.6 的自动降级把 LIVE 打回 SHADOW，但「从未上线的影子」
 * 与「被降级回来的影子」在 {@code state} 上长得一模一样——单看 state 区分不出。降级时写上时间戳，
 * 晋升闸门的谓词要求它为 NULL，于是<b>降级只降不升</b>（A5）：被降级的规则不能再走自动晋升，
 * 回升必须走人工路径把印记清掉。
 *
 * <p><b>硬化印记</b>（{@code hardenedAt}）：§2.6 第三条只提示不处置，故它是一个时间戳而不是状态。
 */
public record MemoryRule(
        String ruleId,
        String clusterKey,   // 行为形状的簇标识（§2.1）
        int version,
        String body,         // 规则文本，注入 Agent 用
        State state,
        long hitCount,
        Instant createdAt,
        Instant demotedAt,   // 非 null = 曾被自动降级，自动晋升通道对它关闭（§2.6）
        Instant hardenedAt   // 非 null = 已标记硬化，待人工复核（§2.6 第三条）
) {
    /** 规则生命周期。影子期只记录「若触发会怎样」，不干预生成。 */
    public enum State {
        SHADOW, LIVE, RETIRED
    }

    /** 是否带降级印记（带印记者不能走自动晋升）。 */
    public boolean demoted() {
        return demotedAt != null;
    }

    /** 是否已硬化（待人工复核）。 */
    public boolean hardened() {
        return hardenedAt != null;
    }
}
