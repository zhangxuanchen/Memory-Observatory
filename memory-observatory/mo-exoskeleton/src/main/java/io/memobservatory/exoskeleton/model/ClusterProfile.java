package io.memobservatory.exoskeleton.model;

/**
 * 一个候选簇的特征画像——{@code RuleDrafter} 的输入（设计文档 §4.5）。
 *
 * 本期画像只有两样东西：簇键（行为形状，见 {@code ShapeBucketer}）与该簇的支撑度。
 * 这不是留白，是当前事件流能提供的全部：§4.4 原定的三个维度里，「是否有 provider」与
 * 「是否只读未写」在真实数据上取不到（工作台不写 {@code layer='provider'}，且每个 tool
 * 结果与模型调用都记 {@code WRITE}），要先补埋点（B4）才能加进来。
 *
 * 所以：**画像会随埋点补强而变宽，撰写器的签名不变。**
 */
public record ClusterProfile(
        String clusterKey,   // 行为形状的簇标识，如 read|steps:4-10
        long turns           // 该簇的支撑度（落桶 turn 数）
) {
}
