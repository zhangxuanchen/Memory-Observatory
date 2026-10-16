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
 *   <li>没有把握时**不产出，且说清为什么**（见 {@link Draft#reason()}）。宁可不产规则，也不要产一条错的；
 *       也不要用一句放之四海的「无对应策略」把真因掩掉——报告面要能看出到底为什么没产出（登记口 B20.1）。</li>
 * </ul>
 */
public interface RuleDrafter {

    /**
     * 起草结果：要么一句草稿（{@link #body()} 有值），要么一句「为什么没产出」（{@link #reason()} 有值）。
     * 二者**必居其一**，绝不回一句空话凑数。
     *
     * <p>为什么要带 {@code reason}：撰写器是**唯一知道「为什么没产出」的一层**——替身＝表里没这个簇；
     * LLM＝未配置 / 调用失败 / 返回空。只回 {@link Optional#empty()} 会让编排层只能猜，
     * 报告面就退化成一句「撰写器无对应策略」（登记口 B20.1）。
     */
    record Draft(Optional<String> body, String reason) {
        public Draft {
            body = body == null ? Optional.empty() : body;
        }

        /** 产出了一句草稿（无原因）。 */
        public static Draft of(String body) {
            return new Draft(Optional.of(body), null);
        }

        /** 没产出，并说明原因——该原因会原样进 {@code RuleDraftService} 的报告面。 */
        public static Draft none(String reason) {
            return new Draft(Optional.empty(), reason);
        }

        /** 是否产出了一句草稿。 */
        public boolean produced() {
            return body.isPresent();
        }
    }

    /** 输入簇的特征画像，输出规则草稿（只写不生效），或「为什么没产出」。 */
    Draft draft(ClusterProfile profile);
}
