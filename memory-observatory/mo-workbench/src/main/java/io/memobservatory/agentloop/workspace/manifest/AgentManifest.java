/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】AgentManifest.java（io.memobservatory.agentloop.workspace.manifest）
 * 【核心功能】Agent 清单 POJO（record + Jackson 序列化）。对应
 *            $ROOT/.workbench/agents/{agentId}/manifest.json，声明 model / tools / skills /
 *            subagents / hooks 全部配置。装配期由 AgentFactory 消费。
 * 【核心改动】2026-08-23 新增；2026-08-23 落盘路径改为 $ROOT/.workbench/agents/（第 2.3 节修正）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Agent 清单：一个工作区内的 Agent 全部配置。
 *
 * @param schema       清单 schema 版本
 * @param id           Agent id（同目录名，工作区内唯一）
 * @param name         展示名
 * @param description  给 LLM 与用户的能力描述
 * @param model        模型覆盖（provider + model 名），空则用全局配置
 * @param systemPrompt 系统提示覆盖，空则用「工作区自动生成」的提示
 * @param tools        内置工具开关（false 的不可用）
 * @param skills       能力包插件清单
 * @param subagents    子 Agent 声明（本期仅配置 + 骨架，不调度）
 * @param hooks        钩子插件清单
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentManifest(
        @JsonProperty("schema") int schema,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("model") ModelRef model,
        @JsonProperty("systemPrompt") String systemPrompt,
        @JsonProperty("tools") ToolFlags tools,
        @JsonProperty("skills") List<SkillRef> skills,
        @JsonProperty("subagents") List<SubagentRef> subagents,
        @JsonProperty("hooks") List<HookRef> hooks,
        @JsonProperty("admin") boolean admin) {

    /** 模型引用：provider + 模型名，任一为空都回落全局配置。 */
    public record ModelRef(String provider, String model) {
    }

    /** 六个内置工具开关。 */
    public record ToolFlags(boolean readFile, boolean writeFile, boolean editFile,
                            boolean bash, boolean webSearch, boolean askUser) {
        public static ToolFlags defaults() {
            return new ToolFlags(true, true, true, true, true, true);
        }
    }

    /** 能力包引用。@param id 能力包 id，@param enabled 启用开关。 */
    public record SkillRef(String id, boolean enabled) {
    }

    /** 子 Agent 声明。@param agentId 目标 Agent（须存在于工作区 agents 索引）。 */
    public record SubagentRef(String id, String agentId, String description, boolean enabled) {
    }

    /** 钩子声明。@param type 内置钩子类型（audit / memory-report），@param config 扩展配置。 */
    public record HookRef(String type, boolean enabled, java.util.Map<String, Object> config) {
    }

    /** 新建 Agent 清单（默认全部工具开启、空插件）。 */
    public static AgentManifest defaults(String id, String name) {
        return create(id, name, "");
    }

    /** 新建 Agent 清单（带描述）。 */
    public static AgentManifest create(String id, String name, String description) {
        return create(id, name, description, List.of());
    }

    /** 新建 Agent 清单（带描述与已启用的能力包 id 列表）。 */
    public static AgentManifest create(String id, String name, String description, List<String> skills) {
        List<SkillRef> refs = skills == null ? List.of()
                : skills.stream().filter(s -> s != null && !s.isBlank())
                        .map(s -> new SkillRef(s, true)).toList();
        return new AgentManifest(1, id, name, description == null ? "" : description,
                null, "",
                ToolFlags.defaults(),
                refs, List.of(), List.of(), false);
    }

    /** 新建工作区主 Agent（工作区管理员）：admin=true，永久保留，不可删除。 */
    public static AgentManifest createAdmin(String id, String name) {
        return new AgentManifest(1, id, name,
                "工作区管理员：与当前工作区绑定，负责工作区的常规管理与对话",
                null, "",
                ToolFlags.defaults(),
                List.of(), List.of(), List.of(), true);
    }

    /** 返回一份改名（或清空名）后的清单副本，其余字段原样保留。 */
    public AgentManifest withName(String name, String description) {
        return new AgentManifest(schema, id,
                name == null ? "" : name,
                description == null ? "" : description,
                model, systemPrompt, tools, skills, subagents, hooks, admin);
    }
}