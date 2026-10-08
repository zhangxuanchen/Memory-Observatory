package io.memobservatory.exoskeleton.model;

import java.util.List;

/**
 * 「外骨骼」页的看板数据：Agent / Session 两张筛选面 + 分类清单 + 待采纳提议。
 *
 * <p><b>为什么「分成了哪些类」要按 Agent 切开</b>：技能是「一类任务怎么做」的手艺，
 * 而不同 Agent 干的活、留下的做法各异——把 23 个 Agent 的对话混在一起按 task_type 提炼，
 * 得到的是拼接过的伪手艺。故清单与提炼都以「Agent × task_type」为单位（登记口 A15）。
 *
 * <p><b>为什么 Agent 清单始终全量、而类清单要选到 Agent 才出</b>：{@code agents} 是筛选器
 * 自己的选项面，必须全量（与 §4.7 {@code taskTypes} 同一规矩）；{@code classes} 是筛选结果，
 * 未选 Agent 时为空——不拿全库聚合冒充「这个 Agent 的分类」。
 *
 * @param enabled    LLM 通道是否可用（不可用时提议不会生成，如实告知）
 * @param minTurns   样本门槛：该类 turn 数低于它就「不提炼」，只显示为未达标
 * @param agentId    当前选中的 Agent（未选为 null）
 * @param sessionId  当前选中的会话（未选为 null；只在选了 Agent 时有意义）
 * @param agents     Agent 清单（全量，带各自待采纳数）
 * @param sessions   所选 Agent 的会话清单（未选 Agent 时为空）
 * @param classes    所选范围内的分类清单（未选 Agent 时为空）
 * @param proposals  所选 Agent 的提议列表（未选 Agent 时为空）
 * @param note       口径说明
 */
public record SkillBoard(
        boolean enabled,
        int minTurns,
        String agentId,
        String sessionId,
        List<AgentStat> agents,
        List<SessionStat> sessions,
        List<ClassStat> classes,
        List<SkillProposal> proposals,
        String note) {

    /**
     * Agent 筛选面的一行。
     *
     * @param agentId  上报遥测的 Agent 身份（{@code memory_turns.agent_id}）
     * @param sessions 该 Agent 有多少个会话
     * @param turns    该 Agent 有多少条对话
     * @param pending  该 Agent 名下还有几条待采纳提议（0 表示无需去看）
     */
    public record AgentStat(String agentId, long sessions, long turns, long pending) {
    }

    /**
     * Session 筛选面的一行。
     *
     * @param sessionId 会话 id
     * @param taskType  该会话判成的任务类型（可能为 null = 未分类）
     * @param label     中文标签（未分类单列）
     * @param turns     该会话多少条对话
     */
    public record SessionStat(String sessionId, String taskType, String label, long turns) {
    }

    /**
     * 分类清单的一行。
     *
     * @param taskType       8 值词表取值；{@code __unclassified__} 表示还没跑分类
     * @param label          中文标签
     * @param sessions       该类包含多少个会话
     * @param turns          该类包含多少条 turn（「每类有多少对话」的读数面）
     * @param eligible       该类是否达到样本门槛（达到才提炼）
     * @param proposalStatus 该类当前提议的状态（无提议为 null）
     */
    public record ClassStat(String taskType, String label, long sessions, long turns,
                            boolean eligible, String proposalStatus) {
    }
}