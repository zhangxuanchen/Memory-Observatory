/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】SkillController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】能力包（Skill）管理端点：列取/新建/更新/删除全局与工作区技能。
 *            全局技能走 /api/agent/skills；工作区技能走 /api/agent/workspaces/{wsId}/skills。
 *            存储由 SkillLibrary 落到 .workbench/skill/<id>.yaml。
 * 【核心改动】2026-08-26 新增（Skill 管理菜单）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workspace.SkillLibrary;
import io.memobservatory.agentloop.workspace.SkillView;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 能力包管理端点。skills 的 scope=global 存全局目录（所有工作区共享），
 * scope=workspace 存当前工作区目录（私有）。
 */
@RestController
@RequestMapping("/api/agent")
public class SkillController {

    private final SkillLibrary library;
    private final WorkspaceManager manager;

    public SkillController(SkillLibrary library, WorkspaceManager manager) {
        this.library = library;
        this.manager = manager;
    }

    // ---------- 全局技能 ----------

    /** 列取全部全局技能。 */
    @GetMapping("/skills")
    public List<SkillView> listGlobal() {
        return library.global();
    }

    /** 新建全局技能（id 为空则由 name 派生）。 */
    @PostMapping("/skills")
    public SkillView createGlobal(@RequestBody SkillRequest req) {
        return library.save(req.id(), req.name(), req.description(), req.prompt(), req.tools(), null, null);
    }

    /** 更新全局技能（按 id 定位文件，不改文件名）。 */
    @PutMapping("/skills/{id}")
    public SkillView updateGlobal(@PathVariable String id, @RequestBody SkillRequest req) {
        return library.save(id, req.name(), req.description(), req.prompt(), req.tools(), null, null);
    }

    /** 删除全局技能。 */
    @DeleteMapping("/skills/{id}")
    public void deleteGlobal(@PathVariable String id) {
        library.delete(id, null, null);
    }

    /** 下载单个全局技能：只把一个技能 yaml 打进 zip。 */
    @GetMapping("/skills/{id}/download")
    public ResponseEntity<byte[]> downloadGlobalSkill(@PathVariable String id) {
        return zipOneFile(manager.globalSkillDir().resolve(sanitize(id) + ".yaml"),
                "skill-" + id + ".zip", id);
    }

    // ---------- 工作区技能 ----------

    /** 列取当前工作区的私有技能。 */
    @GetMapping("/workspaces/{wsId}/skills")
    public List<SkillView> listWorkspace(@PathVariable String wsId) {
        return library.workspace(workspace(wsId));
    }

    /** 新建工作区私有技能。 */
    @PostMapping("/workspaces/{wsId}/skills")
    public SkillView createWorkspace(@PathVariable String wsId, @RequestBody SkillRequest req) {
        return library.save(req.id(), req.name(), req.description(), req.prompt(), req.tools(), workspace(wsId), null);
    }

    /** 更新工作区私有技能（按 id 定位文件，不改文件名）。 */
    @PutMapping("/workspaces/{wsId}/skills/{id}")
    public SkillView updateWorkspace(@PathVariable String wsId, @PathVariable String id,
                                     @RequestBody SkillRequest req) {
        return library.save(id, req.name(), req.description(), req.prompt(), req.tools(), workspace(wsId), null);
    }

    /** 删除工作区私有技能。 */
    @DeleteMapping("/workspaces/{wsId}/skills/{id}")
    public void deleteWorkspace(@PathVariable String wsId, @PathVariable String id) {
        library.delete(id, workspace(wsId), null);
    }

    /** 下载单个工作区私有技能：只把一个技能 yaml 打进 zip。 */
    @GetMapping("/workspaces/{wsId}/skills/{id}/download")
    public ResponseEntity<byte[]> downloadWorkspaceSkill(@PathVariable String wsId, @PathVariable String id) {
        return zipOneFile(manager.workspaceSkillDir(workspace(wsId)).resolve(sanitize(id) + ".yaml"),
                "skill-" + id + ".zip", id);
    }

    private ResponseEntity<byte[]> zipOneFile(Path file, String zipName, String entryBase) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            if (Files.isRegularFile(file)) {
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("README.txt"));
            String readme = (Files.isRegularFile(file) ? "" : "(未找到该技能文件)\n")
                    + "由 Memory-Observatory 导出。把 zip 里的 yaml 放入目标工作区 .workbench/skill/ 目录即可生效。\n";
            zos.write(readme.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("技能打包失败: " + zipName, e);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(baos.toByteArray());
    }

    /** 技能文件名安全化（与 SkillLibrary 一致，防路径穿越）。 */
    private static String sanitize(String id) {
        String s = (id == null ? "" : id.trim()).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return s.isBlank() ? "skill" : s;
    }

    // ---------- Agent 自身技能 ----------

    /** 列取某 Agent 自身私有技能（三级渐进披露的「自身」这一级）。 */
    @GetMapping("/workspaces/{wsId}/agents/{agentId}/skills")
    public List<SkillView> listAgent(@PathVariable String wsId, @PathVariable String agentId) {
        return library.agent(workspace(wsId), agentId);
    }

    /** 新建 Agent 自身私有技能（新建时向上一级查重：不得与工作区/全局技能重名）。 */
    @PostMapping("/workspaces/{wsId}/agents/{agentId}/skills")
    public SkillView createAgent(@PathVariable String wsId, @PathVariable String agentId,
                                 @RequestBody SkillRequest req) {
        return library.save(req.id(), req.name(), req.description(), req.prompt(), req.tools(),
                workspace(wsId), agentId);
    }

    /** 更新 Agent 自身私有技能（按 id 定位文件，不改文件名）。 */
    @PutMapping("/workspaces/{wsId}/agents/{agentId}/skills/{id}")
    public SkillView updateAgent(@PathVariable String wsId, @PathVariable String agentId,
                                 @PathVariable String id, @RequestBody SkillRequest req) {
        return library.save(id, req.name(), req.description(), req.prompt(), req.tools(),
                workspace(wsId), agentId);
    }

    /** 删除 Agent 自身私有技能。 */
    @DeleteMapping("/workspaces/{wsId}/agents/{agentId}/skills/{id}")
    public void deleteAgent(@PathVariable String wsId, @PathVariable String agentId,
                            @PathVariable String id) {
        library.delete(id, workspace(wsId), agentId);
    }

    private Workspace workspace(String wsId) {
        return manager.byId(wsId);
    }

    public record SkillRequest(String id, String name, String description,
                               String prompt, List<String> tools) {
    }
}