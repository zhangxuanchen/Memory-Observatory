package io.memobservatory.exoskeleton.model;

import java.time.Instant;

/**
 * 技能事件流水的一行（操作历史 + 历史视图，一张只增不改的表）。
 *
 * <p><b>为什么</b>{@code HUMAN} <b>与</b> {@code SYSTEM} <b>合成一张</b>：两者答的是同一个问题——
 * 「谁在何时、对哪一格、做了什么、为什么」，只是 actor 不同。拆两张表只会让「按时间倒序看这一格发生过什么」
 * 要并两处，且两处字段高度重叠（见 schema.sql 的表注释）。
 *
 * <p><b>与</b>{@code memory_skill_class_runs} <b>的分工</b>：class_runs 是覆盖式的断点续跑缓存
 * （一格一行、只留最近一次，用来判「这轮要不要跳过」）；本记录是只增不改的流水（每轮追加，
 * 用来答「历史上发生过什么」）。前者可被覆盖、后者不可——正如 rules（当前态）vs rule_evals（历史读数）。
 *
 * @param eventId    自增主键（时间倒序的稳定次序）
 * @param proposalId 所属提议（可空：无提议的 QUIET / FAILED 事件没有提议行）
 * @param agentId    归属 Agent
 * @param taskType   任务类型（事实为 {@code __fact__}；可空）
 * @param kind       {@link #OFFER} / {@link #QUIET} / {@link #FAILED} / {@link #SKIP} /
 *                   {@link #ADOPT} / {@link #DISMISS} / {@link #REVISE} / {@link #DEDUP}
 * @param actor      {@link #HUMAN}（人拍板）或 {@link #SYSTEM}（提炼轮次 / 去重）
 * @param version    事件涉及的版本号（无版本概念时为 null）
 * @param digestHash 当轮证据指纹（无则为 null）
 * @param detail     给人看的一句话：为什么这么做
 * @param createdAt  事件发生时间
 */
public record SkillEvent(
        long eventId,
        String proposalId,
        String agentId,
        String taskType,
        String kind,
        String actor,
        Integer version,
        String digestHash,
        String detail,
        Instant createdAt) {

    /** 本轮提炼出并落了提议（含版本升级）。 */
    public static final String OFFER = "OFFER";
    /** 本轮判过、这类没有完整闭环技能（证据未变则下轮不再送 LLM）。 */
    public static final String QUIET = "QUIET";
    /** 超时 / 解析失败 / 异常（下轮必须重试）。 */
    public static final String FAILED = "FAILED";
    /** 因证据指纹未变被跳过（不白花一次 LLM 调用）。 */
    public static final String SKIP = "SKIP";
    /** 人采纳（actor=HUMAN）。 */
    public static final String ADOPT = "ADOPT";
    /** 人不用（actor=HUMAN）。 */
    public static final String DISMISS = "DISMISS";
    /** 内容变化 + 证据增长，写了新版本（actor=SYSTEM）。 */
    public static final String REVISE = "REVISE";
    /** 硬重复自动去重：同 Agent 同 skill_id，保留证据多者，弱者作废（actor=SYSTEM）。 */
    public static final String DEDUP = "DEDUP";

    /** 人的操作。 */
    public static final String HUMAN = "HUMAN";
    /** 系统的动作（提炼轮次 / 去重）。 */
    public static final String SYSTEM = "SYSTEM";
}