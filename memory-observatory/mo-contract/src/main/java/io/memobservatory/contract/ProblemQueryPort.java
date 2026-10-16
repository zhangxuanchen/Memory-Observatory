/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】ProblemQueryPort.java（io.memobservatory.contract）
 * 【核心功能】阈值问题分析的只读端口。由 server（AnalyticsProblemService）实现，
 *            mo-support 的告警采集（FindingCollector）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M3 / 步骤 14，防 mo-support ⇄ mo-server 循环依赖）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.util.Map;

/**
 * 阈值问题分析端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：告警采集 {@code FindingCollector} 原直接 import server 的
 * {@code AnalyticsProblemService}。M3 把 notify 下沉到 mo-support 后，若仍直接 import，
 * 就变成 {@code mo-support → mo-server}；而 mo-server 又依赖 mo-support（api 控制器要用它），
 * 形成 Maven 循环依赖。端口化后双方只指向契约。
 *
 * <p><b>只读</b>：本端口只暴露「算一批问题」，不暴露任何写库能力。
 */
public interface ProblemQueryPort {

    /** 计算近 {@code days} 天某 agent 的阈值问题（{@code semantic=true} 时对弱格式规则做语义确认）。 */
    Map<String, Object> problems(int days, String agent, boolean semantic);
}