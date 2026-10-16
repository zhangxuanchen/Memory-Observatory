package io.memobservatory.exoskeleton.model;

/**
 * 异步发起一轮提炼的受理回执（{@code POST /skills/discover/async} 的响应体）。
 *
 * <p><b>为什么需要一个「受理」对象而不是直接返回报告</b>：异步提炼立即返回、真正的报告要等
 * 几分钟后才由 {@code status} 取。若这里同步等到跑完，就退回了原先那条「点了按钮干等」的老路。
 * 故这里只回答一件事：<b>这一轮受理了没有</b>。
 *
 * <p>{@code accepted=false} 表示<b>没有新起一轮</b>：该 Agent 已在跑（不重复发 LLM 调用）、
 * 或未启用、或未指定 Agent——具体缘由写在 {@code note} 里，如实说明，不伪装成已开始。
 *
 * @param accepted 是否新受理了一轮
 * @param agentId  目标 Agent
 * @param running  该 Agent 当前是否有提炼在跑（被拒且确实在跑时为 true）
 * @param note     如实说明（受理 / 已在跑 / 未启用 / 未指定 Agent）
 */
public record SkillDiscoverAccepted(boolean accepted, String agentId, boolean running, String note) {
}