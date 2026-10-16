/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】SkillView.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】能力包（Skill）的 API 视图：UserSkill + 作用域（global/workspace）。
 *            供前端「Skill 管理」页与创建 Agent 弹层列出技能。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import java.util.List;

/** 能力包视图：核心数据 + 作用域（global=所有工作区共享，workspace=工作区私有）。 */
public record SkillView(String id, String name, String description, String prompt,
                         List<String> tools, String scope) {

    public static SkillView of(UserSkill u, String scope) {
        return new SkillView(u.id(), u.name(), u.description(), u.prompt(), u.tools(), scope);
    }
}