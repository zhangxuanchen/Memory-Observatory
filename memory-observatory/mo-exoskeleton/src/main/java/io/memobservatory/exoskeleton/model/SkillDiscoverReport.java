package io.memobservatory.exoskeleton.model;

/**
 * 一轮技能提炼的结果（手动触发或分类收尾自动触发都走它）。
 *
 * <p><b>只报结果</b>：提炼绝不自行把技能写进工作台技能库——一律先落成 {@link SkillProposal}
 * （PENDING），等人在「外骨骼」页点「启用」。这条与「自己学、少打扰」并不矛盾：
 * 学是自己学，但「要不要把这个技能装进工作台」是一次真实的能力变更，必须人拍板。
 *
 * @param enabled  LLM 通道是否可用（不可用则其余字段全 0）
 * @param agentId  本轮提炼针对的 Agent（{@code memory_turns.agent_id}）；未指定为 null
 * @param classes  达样本门槛、本轮<b>真正送 LLM</b> 的类数（恒等于 offered + quiet + failed）
 * @param offered  本轮新落 / 刷新的提议数（指纹未变的与已采纳的不计）
 * @param quiet    提炼了但判定「无新增技能」的类数（宁可少而精，不硬凑）
 * @param failed   LLM 不可达 / 解析失败的类数（不猜、留待下轮）
 * @param skipped  证据指纹未变、本轮<b>没送 LLM</b> 的类数——同一份证据不重复打扰，也不白花一次调用
 * @param note     口径说明
 */
public record SkillDiscoverReport(
        boolean enabled,
        String agentId,
        int classes,
        int offered,
        int quiet,
        int failed,
        int skipped,
        String note) {
}