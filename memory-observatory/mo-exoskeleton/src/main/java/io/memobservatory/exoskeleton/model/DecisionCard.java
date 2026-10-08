package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 决策卡片（对应设计文档 §4.10.4 的 JSON 契约）。
 *
 * <p>这是人与系统之间唯一的操作面：看图上的证据，在三档里点一个，点完即走。
 * 默认项是「不动」——漏答不会造成行为改变（§4.10.2 约束一 / §5.3）。
 *
 * <p>四条设计约束落在本记录的形状上：② 选项固定三档、位置固定（{@link #options} 恒为 3 条）；
 * ③ 连续量离散成档位（档位由 {@code CardPlanner} 定，不在契约里）；④ 每个选项自带后果（{@link Option#effect}）；
 * ① 默认值必须是「不动」（{@link #defaultOption} 恒指向三档里「不动」的那一档，由模板保证）。
 *
 * <p><b>{@code subjects} 而不是 {@code cluster_key}</b>：§4.10.5 的「合并同类」会把证据一致的多个候选合成一张卡
 * （「这 5 条证据一致，是否一并采纳」），故一张卡可能覆盖多个簇。单簇卡为单元素列表，合并卡为多元素。
 * 卡片身份仍由 {@link #cardId} 承载：单簇卡 {@code T?:cluster:<簇键>}，合并卡 {@code T?:merged:<键集哈希>}。
 *
 * <p><b>{@code note} 是诚实通道</b>：本卡的证据从哪来、哪一项量表不到（沿用 B7.3 的可得性标注），
 * 免得生成器把「没有对照数」的候选包装成一张看起来有证据的卡。
 */
public record DecisionCard(
        String cardId,           // {template}:cluster:{簇键} 或 {template}:merged:{键集哈希}
        CardTemplate template,
        List<String> subjects,   // 本卡覆盖的簇键；无簇的模板（T4 / T6）为空列表
        String title,            // 簇名 + 建议（一句）
        List<Evidence> evidence, // 证据：每项带对照（没有对照的数字不上卡，§4.8 / §4.10.4）
        List<Option> options,    // 恒三条：采纳 / 等一等 / 否掉（T2 为 更保守/保持/更激进）
        String defaultOption,    // 选项 key，固定指向「不动」那一档
        String reversible,       // 可后悔说明（原文案，非布尔——契约里它就是一句给人看的话）
        int expiresInDays,       // 到期天数；到期未答则不再展示（不替你作决定）
        String note              // 可得性说明：证据来源与缺项
) {
    /** 一条证据：指标名 + 本周期值 + 对照值 + 是否改善。{@code compare} / {@code better} 可为 null（如纯计数项）。 */
    public record Evidence(String label, String value, String compare, Boolean better) {
    }

    /** 一个可选动作：key 与位置固定，{@code effect} 写清后果（不给裸选项）。 */
    public record Option(String key, String label, String effect) {
    }
}