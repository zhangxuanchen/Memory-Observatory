/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】SkillLibraryPortAdapter.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】把 SkillLibrary 的「落全局技能」能力适配成 SkillLibraryPort，
 *            供 server 侧（技能提议采纳）依赖倒置取用（架构重构 M2 / 步骤 10）。
 * 【核心改动】2026-10-12 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import io.memobservatory.contract.SkillLibraryPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link SkillLibrary} 的端口适配器：把调用方固定的「全局作用域」落盘收敛成
 * {@link SkillLibraryPort#saveGlobal}，并把工作台领域类型 {@link SkillView}
 * 投影成契约的 {@link SavedSkill}，使 server 侧不认识工作台内部类型。
 */
@Component
public class SkillLibraryPortAdapter implements SkillLibraryPort {

    private final SkillLibrary library;

    public SkillLibraryPortAdapter(SkillLibrary library) {
        this.library = library;
    }

    @Override
    public SavedSkill saveGlobal(String id, String name, String description, String prompt, List<String> tools) {
        SkillView v = library.save(id, name, description, prompt, tools, null, null);
        return new SavedSkill(v.id(), v.scope(), v.name());
    }
}