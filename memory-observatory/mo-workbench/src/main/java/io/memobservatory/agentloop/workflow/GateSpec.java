/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】GateSpec.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】一条门禁规则：由 PM 动态生成（kind 区分后端/前端构建）或回落到默认值。
 *            真实门禁由 BuildVerifier 在工作区实际执行 mvn/npm 构建，以退出码判定。
 * 【核心改动】2026-08-24 新增。替代原 WorkflowPrompts.FAILURE_SIGNALS 关键词扫描。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

/**
 * 门禁规则规格。kind 决定执行方式：
 * <ul>
 *   <li>{@link #KIND_BACKEND}：工作区存在 pom.xml 时执行 `mvn -q test`，退出码 0 通过。</li>
 *   <li>{@link #KIND_FRONTEND}：工作区存在 package.json 时执行 `npm run build`，退出码 0 通过。</li>
 *   <li>对应工程文件缺失时判定"不适用"（跳过，不算失败）。</li>
 * </ul>
 */
public record GateSpec(String id, String kind, String name) {

    public static final String KIND_BACKEND = "backend";
    public static final String KIND_FRONTEND = "frontend";

    /** 默认门禁：后端 = mvn test，前端 = npm build；PM 未给出结构化门禁时回落。 */
    public static final java.util.List<GateSpec> DEFAULTS = java.util.List.of(
            new GateSpec("gate-backend", KIND_BACKEND, "后端构建测试"),
            new GateSpec("gate-frontend", KIND_FRONTEND, "前端构建"));

    public static GateSpec backend(String name) {
        return new GateSpec("gate-backend", KIND_BACKEND, name);
    }

    public static GateSpec frontend(String name) {
        return new GateSpec("gate-frontend", KIND_FRONTEND, name);
    }
}