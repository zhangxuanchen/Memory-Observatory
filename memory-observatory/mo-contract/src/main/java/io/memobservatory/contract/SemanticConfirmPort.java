/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】SemanticConfirmPort.java（io.memobservatory.contract）
 * 【核心功能】语义确认（假阳性过滤）端口。由 server（SemanticFilterService）实现，
 *            mo-support 的风险扫描（RiskScanService）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M3 / 步骤 14，防 mo-support ⇄ mo-server 循环依赖）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 语义确认端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：风险扫描 {@code RiskScanService} 原直接 import server 的
 * {@code SemanticFilterService}。M3 把 risk 下沉到 mo-support 后，直接 import 会造成
 * {@code mo-support → mo-server} 与 mo-server → mo-support 的 Maven 循环依赖。
 * 端口化后双方只指向契约。
 *
 * <p><b>fail-open</b>：实现侧（laya 后端）不可达时应原样保留全部候选，并把该状态如实
 * 反映在 {@link Result#meta()} 的 {@code status} 里——调用方据此区分「真的过滤了」与
 * 「因故障跳过」，后者不应被当成「没有问题」。
 */
public interface SemanticConfirmPort {

    /**
     * 一次确认的结果。
     *
     * @param hits 保留项（实现侧可能就地给命中打上语义标记，但不删除原对象）
     * @param meta 供 REST 层直接塞进响应的元数据（已按 kind 组装好）
     */
    record Result(List<Map<String, Object>> hits, Map<String, Object> meta) {
    }

    /**
     * 对一批候选做语义确认并剔除假阳性。
     *
     * @param hits    确定层产出的候选
     * @param kind    场景（决定阈值与问题措辞）
     * @param stateOf 把一条候选映射为送检状态；返回 null 表示该条不参与语义判断（直接保留）
     */
    Result confirm(List<Map<String, Object>> hits,
                   ConfirmKind kind,
                   Function<Map<String, Object>, Map<String, Object>> stateOf);
}