package io.memobservatory.exoskeleton.model;

import java.time.Instant;
import java.util.List;

/**
 * 技能提议：从「一类任务」的对话里提炼出的可复用技能，等用户决定要不要用。
 *
 * <p><b>为什么不是 §4.10 的决策卡片</b>：卡片问「记忆行为这条规则要不要上线」，对象是规则；
 * 本记录问「这类任务沉淀出的技能要不要启用到工作台」，对象是<strong>技能</strong>。
 * 前者退后台自动跑，后者才是人面唯一要人拍板的一件事——与用户初衷「自己学、少打扰」对齐。
 *
 * <p><b>为什么不存 LLM 置信度</b>：沿用 {@code A2}「模型自述不能当标签」的纪律。
 * 技能够不够格由「是否有完整闭环」这条口径与人的采纳决定，不由模型自报的分数决定。
 *
 * @param proposalId 一格一 Agent 一类：{@code agent:<agent_id>:task:<task_type>}
 * @param agentId    提炼所依据的 Agent 身份（{@code memory_turns.agent_id}）
 * @param taskType   8 值词表中的取值（{@code fix_bug} / …）
 * @param taskLabel  中文标签（缺陷修复 / …），只用于展示
 * @param turns      提炼时该类对话的 turn 数
 * @param skillId    提议的技能 id（交给 mo-server 的 {@code SkillLibrary.save} 用）
 * @param name       技能展示名
 * @param description 给 LLM 与用户的一句话能力描述
 * @param prompt     技能本体：完整流程步骤 + 注意/禁止 + 验收自检
 * @param tools      该技能启用的内置工具（readFile/writeFile/editFile/bash/webSearch）
 * @param status     {@link #PENDING} / {@link #ADOPTED} / {@link #DISMISSED}
 * @param digestHash 该类对话证据的指纹：变了才允许把已 DISMISSED 的类重新提上来
 * @param createdAt  提议生成时间
 * @param decidedAt  人拍板的时间（未决为 null）
 */
public record SkillProposal(
        String proposalId,
        String agentId,
        String taskType,
        String taskLabel,
        long turns,
        String skillId,
        String name,
        String description,
        String prompt,
        List<String> tools,
        String status,
        String digestHash,
        Instant createdAt,
        Instant decidedAt) {

    /** 待采纳：人面待办。 */
    public static final String PENDING = "PENDING";
    /** 已采纳：技能已落到工作台技能库，同一类不再重复提议。 */
    public static final String ADOPTED = "ADOPTED";
    /** 已决定不用：同一类的同一份证据不再打扰（指纹变了才重提）。 */
    public static final String DISMISSED = "DISMISSED";

    /** 是否还在等人拍板。 */
    public boolean isPending() {
        return PENDING.equals(status);
    }
}