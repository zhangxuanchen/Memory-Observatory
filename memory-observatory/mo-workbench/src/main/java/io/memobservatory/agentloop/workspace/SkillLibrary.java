/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】SkillLibrary.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】能力包（Skill）库服务：从「全局 skill 目录 + 工作区 skill 目录 + Agent 自身 skill 目录」
 *            读写 *.yaml，按 id 解析出 UserSkill。既是 Skill 管理端点的存储后端，也是
 *            AgentFactory 装配动态技能时的解析来源。写盘用原子写避免半截文件。
 * 【作用域】global 技能存 $MO_HOME/.workbench/skill（所有工作区共享）；
 *             workspace 技能存 $ROOT/.workbench/skill（工作区私有）；
 *             agent 技能存 $ROOT/.workbench/agents/<agentId>/skill（Agent 自身私有）。
 * 【装载语义】三级渐进披露：加载时逐级并入（全局→工作区→自身），自身优先覆盖工作区/全局同名；
 *             新建时向上一级查重（自身级不得与工作区/全局重名，工作区级不得与全局重名）。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）；2026-08-27 扩展到 Agent 自身级（三级渐进披露）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 能力包库：yaml 即数据库。scope=global 存全局目录，scope=workspace 存工作区目录。
 */
public class SkillLibrary {

    private static final Logger log = LoggerFactory.getLogger(SkillLibrary.class);

    private final WorkspaceManager manager;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public SkillLibrary(WorkspaceManager manager) {
        this.manager = manager;
    }

    // ---------- 列取 ----------

    /** 全部全局技能（所有工作区共享）。 */
    public List<SkillView> global() {
        return read(manager.globalSkillDir(), "global");
    }

    /** 工作区私有技能。 */
    public List<SkillView> workspace(Workspace ws) {
        return read(manager.workspaceSkillDir(ws), "workspace");
    }

    /** 某 Agent 自身私有技能：$ROOT/.workbench/agents/<agentId>/skills。 */
    public List<SkillView> agent(Workspace ws, String agentId) {
        return read(manager.agentConfigDir(ws, agentId, WorkspaceManager.SKILLS_DIR), "agent");
    }

    /** 同一工作区某 Agent 三级渐进披露合并视图：global + workspace + agent（全局在前、自身在后，按 id 去重）。 */
    public List<SkillView> all(Workspace ws, String agentId) {
        List<SkillView> out = new ArrayList<>(global());
        out.addAll(workspace(ws));
        out.addAll(agent(ws, agentId));
        return out;
    }

    /** 解析指定 id 的能力（自身优先 → 工作区 → 全局兜底）；未知返回 null。 */
    public SkillView find(Workspace ws, String agentId, String id) {
        SkillView v = findIn(manager.agentConfigDir(ws, agentId, WorkspaceManager.SKILLS_DIR), id, "agent");
        if (v != null) return v;
        v = findIn(manager.workspaceSkillDir(ws), id, "workspace");
        return v != null ? v : findIn(manager.globalSkillDir(), id, "global");
    }

    // ---------- 写盘 / 删除 ----------

    /**
     * 保存一个能力到指定作用域：ws=null 记全局；ws 非空且 agentId 为空记工作区私有；
     * ws 与 agentId 均非空记 Agent 自身私有。id 为空时由 name 派生；写盘文件名 = &lt;id&gt;.yaml。
     * <p>同名约束（向上一级查重）：id 即文件名，落入更小作用域时不允许与更上级作用域重名——
     * 自身级不得与工作区/全局重名，工作区级不得与全局重名；全局级则反向扫描所有工作区与自身级，
     * 保证每个 id 全程唯一、合并即拼接、无覆盖歧义。冲突抛 {@link IllegalArgumentException}。</p>
     */
    public SkillView save(String id, String name, String description, String prompt,
                          List<String> tools, Workspace ws, String agentId) {
        String sid = (id == null || id.isBlank()) ? slug(name) : sanitize(id);
        if (skillExistsInOtherScope(sid, ws, agentId)) {
            throw new IllegalArgumentException(
                    "已存在同名技能 [" + sid + "]（位于更高/其他作用域），请更换 id");
        }
        Path dir;
        String scope;
        if (ws == null) {
            dir = manager.globalSkillDir();
            scope = "global";
        } else if (agentId == null || agentId.isBlank()) {
            dir = manager.workspaceSkillDir(ws);
            scope = "workspace";
        } else {
            dir = manager.agentConfigDir(ws, agentId, WorkspaceManager.SKILLS_DIR);
            scope = "agent";
        }
        try {
            Files.createDirectories(dir);
            atomicWrite(dir.resolve(sid + ".yaml"), UserSkill.of(sid, name, description, prompt, tools));
        } catch (IOException e) {
            throw new IllegalStateException("技能写入失败: " + sid, e);
        }
        return SkillView.of(UserSkill.of(sid, name, description, prompt, tools), scope);
    }

    /**
     * 跨作用域重名校验（返回 true 表示应拒绝创建）：
     * 自身级检查「本工作区工作区级 + 全局」，工作区级检查「全局」，
     * 全局级反向扫描所有工作区及其 Agent 的自身级。同作用域自身的既有同名不算（允许覆盖更新）。
     */
    private boolean skillExistsInOtherScope(String id, Workspace ws, String agentId) {
        // 目标=全局：反向扫描所有工作区与所有 Agent 自身级
        if (ws == null) {
            for (Workspace w : manager.list()) {
                if (Files.exists(manager.workspaceSkillDir(w).resolve(id + ".yaml"))) {
                    return true;
                }
                for (String aid : w.manifest().agents()) {
                    if (Files.exists(manager.agentConfigDir(w, aid, WorkspaceManager.SKILLS_DIR).resolve(id + ".yaml"))) {
                        return true;
                    }
                }
            }
            return false;
        }
        // 目标=自身级：向上一级检查工作区级 + 全局
        if (agentId != null && !agentId.isBlank()) {
            if (Files.exists(manager.workspaceSkillDir(ws).resolve(id + ".yaml"))) {
                return true;
            }
            return Files.exists(manager.globalSkillDir().resolve(id + ".yaml"));
        }
        // 目标=工作区级：向上一级检查全局
        return Files.exists(manager.globalSkillDir().resolve(id + ".yaml"));
    }

    /** 删除指定作用域下的能力（ws=null 删全局；ws+agentId 删 Agent 自身；否则删工作区私有）。 */
    public void delete(String id, Workspace ws, String agentId) {
        Path dir;
        if (ws == null) {
            dir = manager.globalSkillDir();
        } else if (agentId == null || agentId.isBlank()) {
            dir = manager.workspaceSkillDir(ws);
        } else {
            dir = manager.agentConfigDir(ws, agentId, WorkspaceManager.SKILLS_DIR);
        }
        try {
            Files.deleteIfExists(dir.resolve(sanitize(id) + ".yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("技能删除失败: " + id, e);
        }
    }

    // ---------- 内部 ----------

    private List<SkillView> read(Path dir, String scope) {
        List<SkillView> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yaml"))
             .sorted()
             .forEach(p -> {
                 try {
                     out.add(readUserSkill(p, scope));
                 } catch (IOException e) {
                     log.warn("技能解析失败 {}: {}", p, e.toString());
                 }
             });
        } catch (IOException e) {
            log.warn("技能目录读取失败 {}: {}", dir, e.toString());
        }
        return out;
    }

    private SkillView findIn(Path dir, String id, String scope) {
        Path p = dir.resolve(sanitize(id) + ".yaml");
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return readUserSkill(p, scope);
        } catch (IOException e) {
            log.warn("技能读取失败 {}: {}", p, e.toString());
            return null;
        }
    }

    private SkillView readUserSkill(Path p, String scope) throws IOException {
        UserSkill u = yaml.readValue(Files.readString(p, StandardCharsets.UTF_8), UserSkill.class);
        // yaml 缺 id（如手工编写的技能）时回退到文件名，保证每个技能都有可用 id，
        // 否则前端会把 id=null 拼成 skillOpenEdit('null')，导致该技能无法编辑/删除。
        String fileId = p.getFileName().toString();
        if (fileId.endsWith(".yaml")) {
            fileId = fileId.substring(0, fileId.length() - 5);
        }
        if (u.id() == null || u.id().isBlank()) {
            u = UserSkill.of(fileId, u.name(), u.description(), u.prompt(), u.tools());
        }
        return SkillView.of(u, scope);
    }

    /** 由 name 派生稳定的 id：保留 Unicode 字母/数字/._-，其余替换为连字符；空名称回退固定串。 */
    private static String slug(String name) {
        String s = (name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("(^-|-$)", "");
        return s.isBlank() ? "skill" : s;
    }

    /** 保留 Unicode 字母/数字/._- 的宽松清洗（读取/删除用，兼容手写文件名，防路径穿越）。 */
    private static String sanitize(String id) {
        String s = (id == null ? "" : id.trim()).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return s.isBlank() ? "skill" : s;
    }

    private void atomicWrite(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, yaml.writeValueAsString(value), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}