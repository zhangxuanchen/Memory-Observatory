package io.memobservatory.exoskeleton.model;

/**
 * 一个评估周期上的判据向量（§2.2 的四项 + §2.6 的硬约束），晋升闸门的输入（§4.11）。
 *
 * <p>与 lab05 {@code gate.py} 的 {@code metrics()} 同形：把一批 turn 折成闸门所需的那几个量。
 *
 * <p><b>所有字段可空，且 {@code null} 一律表示「不可测」，不是 0</b>。这个区分是这张表的全部要点：
 * 本库当前有四项取不到值（附录 B7），若把「取不到」写成 0，闸门会把它读成「改善为 0」或「回退到 0」，
 * 从而给出一个看着有依据、实则凭空捏造的结论。故这里用包装类型，把「缺」显式带进闸门。
 *
 * <p>方向约定（与 §2.2 一致）：{@code correctionRate / forgetRate / churn} 越低越好；
 *  {@code reuseRate / coverage} 越高越好；{@code avgToken / failureRate} 作硬约束，只要求不回退。
 */
public record GateVector(
        Double correctionRate,   // 准：隐式纠正率（↓）
        Double forgetRate,       // 记忆有效性：写后速忘率（↓）
        Double reuseRate,        // 记忆有效性：写入记忆的后续复读率（↑）
        Double coverage,         // 多：有规则托底的 turn 占比（↑）
        Double churn,            // 稳：观察窗内的规则版本数（↓）
        Double avgToken,         // 约束：单位成本 token/turn
        Double failureRate       // 约束：失败率
) {
}
