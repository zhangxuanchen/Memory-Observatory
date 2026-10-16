/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】SkillLoadPort.java（io.memobservatory.contract）
 * 【核心功能】技能装载回流写侧端口。由 server（SkillLoadRepository）实现，
 *            工作台（AgentFactory）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M2 / 步骤 11，治 R3）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.util.Collection;

/**
 * 技能装载回流的写侧端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：工作台装配 Agent 时，要把本次装载的动态技能旁路记一笔（回流观测）。
 * 该写侧的表 {@code memory_skill_loads} 归 server 侧的 {@code SkillLoadRepository}，
 * 原先 {@code AgentFactory} 直接 import 它——形成 {@code agentloop → server} 的反向依赖。
 * 端口化后，工作台只认「记一笔装载」这件事，不认识具体仓库。
 *
 * <p><b>旁路语义保持不变</b>：实现方仍是异步、失败只 WARN、表未建不报错——本接口只是形状，
 * 不改变那条「回流不得回溅到装配主链路」的纪律。
 */
public interface SkillLoadPort {

    /**
     * 记一次装配装载（旁路）。
     *
     * @param agentId     装载这些技能的 Agent
     * @param workspaceId 所属工作区（可空）
     * @param sessionId   触发本次装配的会话（可空）
     * @param skillIds    本次装载的技能 id 集合
     */
    void recordLoads(String agentId, String workspaceId, String sessionId, Collection<String> skillIds);
}