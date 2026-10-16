package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 一个候选簇的特征画像——{@code RuleDrafter} 的输入（设计文档 §4.5）。
 *
 * 画像是三样东西：簇键（行为形状，见 {@code ShapeBucketer}）、该簇的支撑度、以及该簇的**代表性样本**
 * （若干条真实用户提问，B20.2 起加入）。
 *
 * <p>这不是留白，是当前事件流能提供的全部：§4.4 原定的三个维度里，「是否有 provider」与
 * 「是否只读未写」在真实数据上取不到（工作台不写 {@code layer='provider'}，且每个 tool
 * 结果与模型调用都记 {@code WRITE}），要先补埋点（B4）才能加进来。
 *
 * <p>样本为什么放在画像里而不是让撰写器自己去取：撰写器是**无依赖纯件**（契约要的），
 * 取数留在编排侧的仓储层（{@code RuleRepository.draftCandidates()}）——这样换撰写器不必改取数，
 * 换埋点补强画像也不必改撰写器签名。**画像会继续变宽，撰写器的方法签名不变。**
 *
 * @param clusterKey 行为形状的簇标识，如 {@code readonly|steps:4-10}
 * @param turns      该簇的支撑度（落桶 turn 数）
 * @param samples    该簇的代表性用户提问样本（无样本时为空表，不是 null）
 */
public record ClusterProfile(
        String clusterKey,
        long turns,
        List<String> samples
) {
    public ClusterProfile {
        // 规范化：null 一律读作「无样本」，且列表不可变——画像一旦交给撰写器就不再变
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /** 无样本的画像（替身路径与测试用；真实候选由 {@code RuleRepository} 带样本给出）。 */
    public ClusterProfile(String clusterKey, long turns) {
        this(clusterKey, turns, List.of());
    }
}
