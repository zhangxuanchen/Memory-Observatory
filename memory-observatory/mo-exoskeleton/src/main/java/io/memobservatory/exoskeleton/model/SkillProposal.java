package io.memobservatory.exoskeleton.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
 * @param version    当前版本号（从 1 起）：已采纳技能随「内容变化 + 证据增长」出新版时递增，
 *                   提议行本身<b>不翻 PENDING</b>，新版本以 {@link #pendingVersion} 告知人「有新版可用」
 * @param pendingVersion 有一版更新的内容（已写进版本链、尚未落进工作台）时是它的版本号，否则 null。
 *                       只在 {@code status=ADOPTED} 时有意义——它答的是「库里那版是不是最新的」，
 *                       是卡片上「有新版 vN / 更新」按钮的唯一依据（见 {@code VersionPolicy}）
 * @param reasoning  「这个技能是怎么被提炼出来的」心路历程（见 {@link Reasoning}）：让人据此判断
 *                   提炼对不对、从而决定要不要用。旧行没有这一列时为 null，卡片上不显示这个入口
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
        Instant decidedAt,
        int version,
        Integer pendingVersion,
        Reasoning reasoning) {

    /**
     * 「提炼思考历程」：把「凭什么说这类任务沉淀出了这个技能」摊开给人核对。
     *
     * <p><b>为什么把模型自述与确定性证据分列</b>：项目的一条纪律是「模型自述不能当标签」（A2）。
     * {@code patterns / preferences / keyPoints / evidence} 是模型自己写的心路（仅供理解，不作判据）；
     * {@code sessions / samples} 是系统从证据里如实抽出来的（依据了哪些会话、会话里的代表原话），
     * 可以逐条核对。两者分开列，人才分得清「哪句能信、哪句只是模型的一面之词」。
     *
     * @param patterns    模型自述：这类任务里反复出现的规律
     * @param preferences 模型自述：用户的偏好 / 习惯
     * @param keyPoints   模型自述：支撑这个技能的关键判断点
     * @param evidence    模型自述：一句话依据（从哪些做法归纳出来的）
     * @param sessions    确定性证据：依据的会话 id（最多若干条，超出截断）
     * @param samples     确定性证据：会话里的代表原话抽样（留头留尾，供核对）
     * @param logSummary  日志总结（模型口径）：一两句话概括「用户近期在这些日志里在做什么、反复出现什么做法」。
     *                    面板里排在自述之前，先把「被提炼的那批日志」讲清楚，再给结论
     * @param logFacts    日志总结（系统口径）：硬统计，如「5 个会话」「828 条对话」「08-10 → 09-18」。
     *                    与模型总结分列，延续 A2「模型自述不能当标签」——数字可核对，话不可尽信
     */
    public record Reasoning(
            List<String> patterns,
            List<String> preferences,
            List<String> keyPoints,
            String evidence,
            List<String> sessions,
            List<String> samples,
            String logSummary,
            List<String> logFacts) {

        /**
         * 是否没有任何可展示的内容（旧数据 / 模型没给心路时为空，卡片据此不显示入口）。
         *
         * <p><b>为什么要 {@code @JsonIgnore}</b>：本记录会被序列化进库列、再读回来。Jackson 会把
         * 形如 {@code isEmpty()} 的布尔方法当成 getter（序列化出多余的 {@code "empty"} 字段），
         * 读回时又因「无此分量」而报错——入口就会静默消失。故显式排除出 JSON。
         */
        @JsonIgnore
        public boolean isEmpty() {
            return isEmpty(patterns) && isEmpty(preferences) && isEmpty(keyPoints)
                    && (evidence == null || evidence.isBlank())
                    && isEmpty(sessions) && isEmpty(samples)
                    && (logSummary == null || logSummary.isBlank())
                    && isEmpty(logFacts);
        }

        private static boolean isEmpty(List<String> xs) {
            return xs == null || xs.isEmpty();
        }
    }

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

    /** 是否有一版更新的内容等着人点「更新」（只在已采纳时有意义）。 */
    public boolean hasNewVersion() {
        return pendingVersion != null;
    }
}