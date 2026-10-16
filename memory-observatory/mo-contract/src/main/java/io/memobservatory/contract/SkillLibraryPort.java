/*******************************************************************************
 * 【模块】Contract（单向依赖契约）
 * 【文件】SkillLibraryPort.java（io.memobservatory.contract）
 * 【核心功能】工作台技能库的落盘端口（只收「采纳技能提议」用到的那一面）。
 *            由工作台提供实现，server 侧（ExoskeletonSkillController）只依赖本接口。
 * 【核心改动】2026-10-12 新增（架构重构 M2 / 步骤 10，治 R3）。
 *******************************************************************************/
package io.memobservatory.contract;

import java.util.List;

/**
 * 工作台技能库的落盘端口（依赖倒置）。
 *
 * <p><b>为什么需要它</b>：外骨骼技能提议的「采纳」这一步必须由本服务落盘技能 YAML，
 * 原先 {@code server/exoprox/ExoskeletonSkillController} 直接 import 工作台的
 * {@code SkillLibrary} / {@code SkillView}（含工作区概念的两级作用域 API）。
 * 抽象成「只落全局技能」的窄端口后，{@code server} 不再认识工作台的领域类型。
 *
 * <p><b>为什么只暴露全局落盘</b>：调用方（采纳）固定落全局作用域（ws=null / agentId=null），
 * 工作区与 Agent 私有作用域只属工作台内部；窄接口同时避免把 {@code Workspace} 这类
 * 工作台领域类型带进契约（否则契约会反向依赖 agentloop）。
 */
public interface SkillLibraryPort {

    /**
     * 把一条技能落成<b>全局</b>技能（所有工作区共享）。id 为空时由 name 派生。
     *
     * @return 落盘后的最小视图（id / scope / name）
     * @throws IllegalArgumentException 同名技能已存在于更高/其他作用域
     * @throws IllegalStateException    写盘失败
     */
    SavedSkill saveGlobal(String id, String name, String description, String prompt, List<String> tools);

    /** 落盘后的最小视图：调用方只需要这三项，不暴露工作台领域类型。 */
    record SavedSkill(String id, String scope, String name) {
    }
}