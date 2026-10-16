package io.memobservatory.exoskeleton.model;

import java.time.Instant;
import java.util.List;

/**
 * 技能效果验证的一次判决（采纳前后对照）。
 *
 * <p><b>与晋升闸门（§4.11）的关系</b>：那条闸门管的是「记忆行为这条规则要不要上线」，
 * 判据挂在一个行为形状的簇上；本条管的是「采纳的技能到底有没有用」，对象是一条流程文本，
 * 没有簇、也没有命中日志——规则线那三层闸门套不上来。故另立一条更朴素的判据。
 *
 * <p><b>「技能被用上」的埋点（本闸门口径）</b>：工作台里技能是以「提示片段 + 内置工具」装配进
 * Agent 的，逐 turn 调用与否不在遥测里。故本闸门以提议的<b>采纳时刻</b> {@code decidedAt} 为界，
 * 把同 Agent 同任务类型的会话按「首 turn 在界前 / 界后」分成两组作对照。这是<b>暴露组的上界近似</b>
 * ——它假设采纳之后的每一轮同类工作都沾到了这份技能，可能高估暴露；要更准需要逐 turn 的技能装载埋点，
 * 目前没有（登记口 B17）。
 *
 * <p><b>判决与动作分离</b>：本报告只回答「用了之后有没有变差」，<b>不改任何状态</b>。
 * 撤回采纳是人拍板的事——闸门越权自动改能力，正是「自己学、少打扰」要防的。
 *
 * @param proposalId 提议 id
 * @param agentId    提议所属 Agent
 * @param taskType   任务类型取值
 * @param taskLabel  任务类型中文标签
 * @param status     提议当前状态（PENDING / ADOPTED / DISMISSED）
 * @param decidedAt  人拍板时刻；未决为 null，此时无从分界
 * @param before     采纳前一组（按会话首 turn 归组）
 * @param after      采纳后一组
 * @param items      三项逐条判决（准 / 失败 / 回合）
 * @param verdict    {@link #PASS} / {@link #FAIL} / {@link #UNKNOWN}
 * @param tripwires  §4.11 同款：监控信号，不拦截——只把「形状很怪」的数字喊出来
 * @param note       口径说明（埋点定义与局限）
 */
public record SkillEffectReport(
        String proposalId,
        String agentId,
        String taskType,
        String taskLabel,
        String status,
        Instant decidedAt,
        Bucket before,
        Bucket after,
        List<Item> items,
        String verdict,
        List<String> tripwires,
        String note) {

    /** 判决通过：三项都没变差，且两组样本都达标。 */
    public static final String PASS = "PASS";
    /** 判决不通过：至少一项明显变差。 */
    public static final String FAIL = "FAIL";
    /** 不可测：样本不足 / 未采纳 / 无从分界——如实报，不假装通过也不假装失败。 */
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * 一组（采纳前 / 采纳后）的读数。
     *
     * @param sessions      会话数
     * @param turns         turn 数
     * @param correctedRate 准·隐式纠正率（该组 turn 中 corrected=true 的占比，↓ 越好）
     * @param failureRate   失败率（outcome 命中失败词表的 turn 占比，↓ 越好）
     * @param rounds        回合数（该组平均每个会话的 turn 数，↓ 越省）
     * @param measurable    该组样本是否达到门槛（低于门槛则该组判不了）
     */
    public record Bucket(long sessions, long turns, double correctedRate,
                         double failureRate, double rounds, boolean measurable) {
    }

    /**
     * 一项逐条判决。
     *
     * @param label  中文名（准 / 失败 / 回合）
     * @param before 采纳前取值；不可测为 null
     * @param after  采纳后取值；不可测为 null
     * @param outcome {@link #PASS} / {@link #FAIL} / {@link #UNKNOWN}
     * @param text   人类可读的一行说明
     */
    public record Item(String label, Double before, Double after, String outcome, String text) {
    }
}