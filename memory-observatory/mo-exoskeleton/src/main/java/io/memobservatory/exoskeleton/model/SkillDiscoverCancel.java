package io.memobservatory.exoskeleton.model;

/**
 * 取消一轮提炼的回执（{@code POST /skills/discover/cancel} 的响应体）。
 *
 * <p><b>取消是「请求」不是「立刻停」</b>：正在跑的那个类无法从中途打断（LLM 调用已发出），
 * 故取消只置一个标志，主循环在<b>每个类开始前</b>查它，为真即停、不再往后发 LLM 调用。
 * 该 Agent 当前没有正在跑的提炼时，{@code cancelRequested=false} 并如实说明「无需取消」。
 *
 * @param cancelRequested 是否真的置上了取消标志（有在跑的本轮才为 true）
 * @param note            如实说明（已请求取消 / 当前无需取消）
 */
public record SkillDiscoverCancel(boolean cancelRequested, String note) {
}