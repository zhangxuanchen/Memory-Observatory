/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】UserSkill.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】能力包（Skill）数据模型：用户可管理的 yaml 能力包，含名称/描述/
 *            系统提示片段 + 可用工具。落盘为 .workbench/skill/<id>.yaml。
 *            序列化由 SkillLibrary 用 jackson-dataformat-yaml 完成。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 用户能力包（Skill）核心数据。scope 不入盘，由 SkillLibrary 按所在目录判定。
 *
 * @param id          技能 id（同文件名，全局/工作区内唯一）
 * @param name        展示名
 * @param description 给 LLM 与用户的能力描述（装配期追加进系统提示）
 * @param prompt      系统提示片段（装配期追加进系统提示）
 * @param tools       该技能启用的内置工具（readFile/writeFile/editFile/bash/webSearch）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSkill(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("tools") List<String> tools) {

    /** 归一化构造：空值统一填空列表，避免装配期 NPE。 */
    public static UserSkill of(String id, String name, String description, String prompt, List<String> tools) {
        return new UserSkill(id,
                name == null ? "" : name,
                description == null ? "" : description,
                prompt == null ? "" : prompt,
                tools == null ? List.of() : List.copyOf(tools));
    }
}