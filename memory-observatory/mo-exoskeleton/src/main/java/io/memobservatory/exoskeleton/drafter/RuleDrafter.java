package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.model.ClusterProfile;

import java.util.Optional;

/**
 * 规则撰写器（设计文档 §4.5）。
 *
 * 把「规则由谁写」从机制里解耦，是做这件事最重要的一个工程决定：
 * **闭环机制成不成立，与撰写器是不是 LLM 无关。** 分开之后，任何撰写器的失效都被隔离在这一层。
 *
 * 契约：
 * <ul>
 *   <li>只产出**草稿文本**，不决定生效——写库一律落 {@code SHADOW}，上线的唯一入口是晋升闸门（§4.11）。</li>
 *   <li>只写**记忆策略**（该记什么 / 该忘什么 / 何时读回 / 用哪个工具），不得出现推理策略（§1.4）。</li>
 *   <li>对同一个画像必须产出同样的文本——判定与对照都要求确定性（§4.6）。</li>
 *   <li>没有把握时返回 {@link Optional#empty()}，**不要编**。宁可不产规则，也不要产一条错的。</li>
 * </ul>
 */
public interface RuleDrafter {

    /** 输入簇的特征画像，输出规则草稿（只写不生效）。 */
    Optional<String> draft(ClusterProfile profile);
}
