package io.memobservatory.exoskeleton.model;

import java.time.Instant;
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
 * @param duplicates 疑似重复（软重复）提示：内容指纹相同但 id 不同的一对提议（见 {@link Duplicate}）
 * @param loads      装载回流读数：每个技能被 Agent 装配装载过几次、最近一次何时（见 {@link LoadStat}）；
 *                   口径是「装载」不是「调用」（现有工具事件归因不回具体技能，见 schema.sql 表注释）
 * @param taskTypes  任务类型封闭词表（value + 中文标签）：供页面的「人工纠正」下拉列出全部合法取值——
 *                   类清单只列「这个 Agent 已有的类」，纠正却可能要改成一个它<b>还没有</b>的类型，故词表须单独给全
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
        List<Duplicate> duplicates,
        List<LoadStat> loads,
        List<TaskTypeOption> taskTypes,
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
     * @param reason         无提议时「这类为什么还没提炼出技能」的中文一句话
     *                       （样本不足 / LLM 超时 / 解析失败 / 证据未变已跳过 / 已取消等）；
     *                       已有提议时为 null——出了提议就不必再解释「为何没出」
     */
    public record ClassStat(String taskType, String label, long sessions, long turns,
                            boolean eligible, String proposalStatus, String reason) {
    }

    /**
     * 疑似重复的一对提议（软重复）：同 Agent 下 {@code skill_id} 不同、但内容指纹相同。
     *
     * <p><b>为什么只标、不自动合并</b>：两条提议可能答的是不同问题（如「技能」与「事实」共用产物形态），
     * 机器不该替人拍板「它们是不是一回事」。故只给卡片标一句「疑似重复」+ 指出对方是谁，
     * 合不合由人决定（硬重复——同 id 同技能——才自动去重，见 SkillProposalRepository.store）。
     *
     * <p>两个方向各出一条（A 看到 B、B 看到 A），前端按 {@code proposalId} 建表即可直接查。
     *
     * @param proposalId      本提议
     * @param otherProposalId 与之疑似重复的另一条
     * @param otherName       另一条的展示名（人面提示里点明「和谁像」）
     * @param otherTaskLabel  另一条的中文类名
     */
    public record Duplicate(String proposalId, String otherProposalId,
                            String otherName, String otherTaskLabel) {
    }

    /**
     * 装载回流的一行（P0-1）：某个技能被 Agent 装配装载了多少次、最近一次何时。
     *
     * <p><b>口径是「装载」不是「调用」</b>：现有工具执行事件（{@code memory_events} 里
     * {@code memory_key='tool:<name>'}）是通用工具的，yaml 技能注册的正是这些工具，
     * <b>归因不回具体技能</b>。故这里只如实报「装进去了几次、还在被装吗」，
     * 不冒充「用了几次」——那需要有归因的调用事件才能答（见 schema.sql 表注释）。
     *
     * <p>按 {@code skill_id} 跨 Agent 汇总：同一技能可能被多个 Agent 装载，此处计总、取最近。
     *
     * @param skillId      技能 id（落盘文件名即身份）
     * @param loadCount    被装载的总次数（按 Agent × 技能聚合后求和）
     * @param lastLoadedAt 最近一次装载时间（从未装载则不会出现在清单里）
     */
    public record LoadStat(String skillId, long loadCount, Instant lastLoadedAt) {
    }

    /**
     * 任务类型词表的一项（供页面「人工纠正」下拉）。
     *
     * @param value 落库取值（{@code fix_bug} / …），纠正请求体里要传的就是它
     * @param label 中文标签，只用于下拉显示
     */
    public record TaskTypeOption(String value, String label) {
    }
}