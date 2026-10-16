/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】FlowIssuePort.java（io.memobservatory.contract）
 * 【核心功能】流程问题分析的只读端口。由 server（FlowAnalysisService）实现，
 *            mo-support 的告警采集（FindingCollector）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M3 / 步骤 14，防 mo-support ⇄ mo-server 循环依赖）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.util.Map;

/**
 * 流程问题分析端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：告警采集 {@code FindingCollector} 原直接 import server 的
 * {@code FlowAnalysisService}。M3 把 notify 下沉到 mo-support 后，直接 import 会造成
 * {@code mo-support → mo-server} 与 mo-server → mo-support 的 Maven 循环依赖。
 * 端口化后双方只指向契约。
 *
 * <p><b>只读</b>：本端口只暴露「算一批流程问题」，不暴露任何写库能力。
 */
public interface FlowIssuePort {

    /** 近 {@code days} 天某 agent（可限 {@code sessionId}）的流程问题卡片。 */
    Map<String, Object> issues(String agentId, int days, String sessionId);
}