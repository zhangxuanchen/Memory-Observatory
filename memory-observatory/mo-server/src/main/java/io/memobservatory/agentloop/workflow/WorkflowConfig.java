/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】WorkflowConfig.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】工作流运行配置：能力开关、门禁参数、角色序列（对应设计文档第 8 章工作流配置，
 *            以 Java 常量预置，暂不引入外部工作流 JSON 文件）。
 * 【核心改动】2026-08-24 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流配置（MVP 用 Java 常量预置）。capabilities 控制架构分析/BUG 修改/git 提交能力开关，
 * gate 控制修复再进入门禁的重试上限。角色序列由 {@link #roles(Boolean, Boolean)} 按需求裁剪。
 */
public class WorkflowConfig {

    /** 模板清单 id（须预置在 $ROOT/.workbench/agents/ 下）。 */
    public static final String TEMPLATE_JAVA_MVN = "backend-java";
    public static final String TEMPLATE_FRONTEND = "frontend-web";

    private final boolean architectureAnalysis;
    private final boolean bugfix;
    private final boolean gitCommit;
    /** 真实构建门禁：以 mvn/npm 退出码判定，替代文本关键词扫描。 */
    private final boolean realBuildGate;
    /** PM 动态门禁规则：解析项目经理产出生成门禁清单。 */
    private final boolean dynamicGates;
    /** 任务黑板 + 动态子 agent：PM 拆解任务→黑板→开发阶段执行。 */
    private final boolean taskBoard;
    /** 工作流断点持久化：可断线续跑/失败重跑。 */
    private final boolean persistence;
    /** 修复闭环最大重试次数（连续失败超过则中止）。 */
    private final int gateMaxRetries;
    /** 单个角色阶段执行超时（含 mvn/npm 构建）。 */
    private final Duration roleTimeout;
    /** bash（mvn/npm 构建）工具超时。 */
    private final Duration buildBashTimeout;

    public WorkflowConfig() {
        this(true, true, true, true, true, true, true, 2);
    }

    public WorkflowConfig(boolean architectureAnalysis, boolean bugfix, boolean gitCommit,
                          int gateMaxRetries) {
        this(architectureAnalysis, bugfix, gitCommit, true, true, true, true, gateMaxRetries);
    }

    public WorkflowConfig(boolean architectureAnalysis, boolean bugfix, boolean gitCommit,
                          boolean realBuildGate, boolean dynamicGates, boolean taskBoard,
                          boolean persistence, int gateMaxRetries) {
        this.architectureAnalysis = architectureAnalysis;
        this.bugfix = bugfix;
        this.gitCommit = gitCommit;
        this.realBuildGate = realBuildGate;
        this.dynamicGates = dynamicGates;
        this.taskBoard = taskBoard;
        this.persistence = persistence;
        this.gateMaxRetries = Math.max(1, gateMaxRetries);
        this.roleTimeout = Duration.ofMinutes(25);
        // 前端模板时意味着有 npm；后端一定有 mvn。宽松给到 15 分钟覆盖全量构建。
        this.buildBashTimeout = Duration.ofMinutes(15);
    }

    public boolean isArchitectureAnalysis() {
        return architectureAnalysis;
    }

    public boolean isBugfix() {
        return bugfix;
    }

    public boolean isGitCommit() {
        return gitCommit;
    }

    public boolean isRealBuildGate() {
        return realBuildGate;
    }

    public boolean isDynamicGates() {
        return dynamicGates;
    }

    public boolean isTaskBoard() {
        return taskBoard;
    }

    public boolean isPersistence() {
        return persistence;
    }

    public int getGateMaxRetries() {
        return gateMaxRetries;
    }

    public Duration getRoleTimeout() {
        return roleTimeout;
    }

    public Duration getBuildBashTimeout() {
        return buildBashTimeout;
    }

    /** 一个角色节点：id、模板、是否只读、提示片段。 */
    public record Role(String id, String template, boolean readOnly, String prompt) {
    }

    /** 按能力开关裁剪生成 fullstack 角色序列。 */
    public List<Role> roles() {
        List<Role> list = new ArrayList<>();
        list.add(new Role("pm", TEMPLATE_JAVA_MVN, true, WorkflowPrompts.PM));
        if (architectureAnalysis) {
            list.add(new Role("architect", TEMPLATE_JAVA_MVN, true, WorkflowPrompts.ARCHITECT));
        }
        list.add(new Role("backend", TEMPLATE_JAVA_MVN, false, WorkflowPrompts.BACKEND));
        list.add(new Role("frontend", TEMPLATE_FRONTEND, false, WorkflowPrompts.FRONTEND));
        list.add(new Role("review", TEMPLATE_JAVA_MVN, false, WorkflowPrompts.REVIEW));
        if (bugfix) {
            list.add(new Role("bugfix", TEMPLATE_JAVA_MVN, false, WorkflowPrompts.BUGFIX));
        }
        if (gitCommit) {
            list.add(new Role("git", TEMPLATE_JAVA_MVN, false, WorkflowPrompts.GIT));
        }
        // 收口角色固定为项目经理验收 + 总结
        list.add(new Role("summary", TEMPLATE_JAVA_MVN, true, WorkflowPrompts.SUMMARY));
        return list;
    }

    /** 按 id 取角色；不存在返回 null（用于绕过/仅保存阶段的拼接）。 */
    public Map<String, Role> roleIndex() {
        Map<String, Role> m = new LinkedHashMap<>();
        for (Role r : roles()) {
            m.put(r.id(), r);
        }
        return m;
    }
}