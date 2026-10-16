/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】EventQueryPort.java（io.memobservatory.contract）
 * 【核心功能】记忆事件的只读查询端口。由 server（EventRepository）实现，
 *            工作台（WorkspaceController 的报表）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M2 / 步骤 11，治 R3）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 记忆事件的只读查询端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：工作区的「日志分类报表」要按 Agent 聚合 {@code memory_events}，
 * 原先 {@code WorkspaceController} 直接 import server 的 {@code EventRepository}——
 * 形成 {@code agentloop → server} 的反向依赖。端口化后，工作台只依赖这条**只读**能力，
 * 不接触事件仓储的写侧与模型类型。
 *
 * <p><b>只读</b>：本端口刻意只暴露聚合查询，{@code memory_events} 的 owner 仍是 mo-server
 * （见表所有权矩阵 §4.4），工作台不得经此写库。
 */
public interface EventQueryPort {

    /** 按 Agent 聚合事件：返回 {@code agent_id / events / tokens / avg_latency / operation / layer / time_from / time_to} 行。 */
    List<Map<String, Object>> queryAgentClassify(List<String> agentIds, Instant from, Instant to);

    /**
     * 列出最近有活动的 Agent（按最后活动时间倒序，最多 {@code limit} 个）：
     * 返回 {@code agentId / lastTs / eventCount} 行。供告警采集（notify）枚举待评估对象。
     */
    List<Map<String, Object>> queryAgents(int limit);
}