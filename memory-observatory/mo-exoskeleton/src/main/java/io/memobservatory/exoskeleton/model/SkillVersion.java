package io.memobservatory.exoskeleton.model;

import java.time.Instant;
import java.util.List;

/**
 * 技能某个版本的内容快照（技能版本链）。
 *
 * <p><b>为什么与 {@link SkillEvent} 分开</b>：事件记「动作」（谁在何时做了什么），
 * 拿不回当时的 {@code prompt / tools}；本记录是「内容」——要回答「v2 的流程长什么样」，
 * 只有事件答不出。分工同 {@code memory_rules}（当前态）vs {@code memory_rule_evals}（历史读数）。
 *
 * <p><b>skill_id 从 v1 起钉死</b>：文件名即身份（mo-server 落 {@code <skill_id>.yaml}），
 * 版本演进只换内容、不换 id——换 id 会留下没人认领的孤儿 yaml（登记口 B18.1 定案）。
 *
 * @param proposalId  所属提议（{@code agent:<agent_id>:task:<task_type>}）
 * @param version     版本号，从 1 起
 * @param agentId     提炼所依据的 Agent
 * @param taskType    任务类型（事实为 {@code __fact__}）
 * @param skillId     技能 id（版本间不变）
 * @param name        展示名
 * @param description 一句话能力描述
 * @param prompt      技能本体：完整流程步骤 + 注意/禁止 + 验收自检
 * @param tools       启用的内置工具
 * @param digestHash  本版本提炼时依据的<b>证据</b>指纹（非内容指纹）
 * @param status      {@link #PENDING} / {@link #ADOPTED} / {@link #SUPERSEDED}
 * @param createdAt   本版本生成时间
 */
public record SkillVersion(
        String proposalId,
        int version,
        String agentId,
        String taskType,
        String skillId,
        String name,
        String description,
        String prompt,
        List<String> tools,
        String digestHash,
        String status,
        Instant createdAt) {

    /** 待采纳：已写进版本链，但尚未（由人点「更新」）落进工作台。 */
    public static final String PENDING = "PENDING";
    /** 已采纳：这一版就是当前装进工作台的那一版。 */
    public static final String ADOPTED = "ADOPTED";
    /** 被新版顶下去：内容已成历史，但它是「上一条已采纳」的记录，不可删（只增不改）。 */
    public static final String SUPERSEDED = "SUPERSEDED";
}