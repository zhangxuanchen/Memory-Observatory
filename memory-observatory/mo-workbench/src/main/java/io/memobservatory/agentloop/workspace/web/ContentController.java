/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】ContentController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】预装配置管理端点：列取/新建/更新/删除「规则 rules / 记忆 memory / 钩子 hook」的
 *            全局与工作区配置。全局走 /api/agent/{type}；工作区走 /api/agent/workspaces/{wsId}/{type}。
 *            创建时由 ContentLibrary 校验「同 type 同 id 不在另一作用域重复」。
 * 【核心改动】2026-08-26 新增（全局预装基线 + 工作区合并）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workspace.ContentItem;
import io.memobservatory.agentloop.workspace.ContentLibrary;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 预装配置管理端点（独立前缀 content，避免与 /api/agent/memory、/skills、/workspaces 等
 *  既有字面量路由冲突）。type 限 rules / memory / hook；scope=global 存全局目录（所有工作区共享），
 *  scope=workspace 存当前工作区目录（私有）。 */
@RestController
@RequestMapping("/api/agent/content")
public class ContentController {

    private final ContentLibrary library;
    private final WorkspaceManager manager;

    public ContentController(ContentLibrary library, WorkspaceManager manager) {
        this.library = library;
        this.manager = manager;
    }

    // ---------- 全局预装 ----------

    /** 列取指定 type 的全局预装项。 */
    @GetMapping("/{type}")
    public List<ContentItem> listGlobal(@PathVariable String type) {
        return library.global(requireType(type));
    }

    /** 新建全局预装项。 */
    @PostMapping("/{type}")
    public ContentItem createGlobal(@PathVariable String type, @RequestBody ContentRequest req) {
        return library.save(requireType(type), req.id(), req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), null, null);
    }

    /** 更新全局预装项（按 id 定位文件，不改文件名）。 */
    @PutMapping("/{type}/{id}")
    public ContentItem updateGlobal(@PathVariable String type, @PathVariable String id,
                                    @RequestBody ContentRequest req) {
        return library.save(requireType(type), id, req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), null, null);
    }

    /** 删除全局预装项。 */
    @DeleteMapping("/{type}/{id}")
    public void deleteGlobal(@PathVariable String type, @PathVariable String id) {
        library.delete(requireType(type), id, null, null);
    }

    // ---------- 工作区预装 ----------

    /** 列取指定工作区的私有预装项。 */
    @GetMapping("/workspaces/{wsId}/{type}")
    public List<ContentItem> listWorkspace(@PathVariable String wsId, @PathVariable String type) {
        return library.workspace(requireType(type), workspace(wsId));
    }

    /** 新建工作区私有预装项。 */
    @PostMapping("/workspaces/{wsId}/{type}")
    public ContentItem createWorkspace(@PathVariable String wsId, @PathVariable String type,
                                       @RequestBody ContentRequest req) {
        return library.save(requireType(type), req.id(), req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), workspace(wsId), null);
    }

    /** 更新工作区私有预装项。 */
    @PutMapping("/workspaces/{wsId}/{type}/{id}")
    public ContentItem updateWorkspace(@PathVariable String wsId, @PathVariable String type,
                                       @PathVariable String id, @RequestBody ContentRequest req) {
        return library.save(requireType(type), id, req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), workspace(wsId), null);
    }

    /** 删除工作区私有预装项。 */
    @DeleteMapping("/workspaces/{wsId}/{type}/{id}")
    public void deleteWorkspace(@PathVariable String wsId, @PathVariable String type,
                                @PathVariable String id) {
        library.delete(requireType(type), id, workspace(wsId), null);
    }

    // ---------- Agent 自身预装 ----------

    /** 列取某 Agent 自身私有预装项（三级渐进披露的「自身」这一级）。 */
    @GetMapping("/workspaces/{wsId}/agents/{agentId}/{type}")
    public List<ContentItem> listAgent(@PathVariable String wsId, @PathVariable String agentId,
                                       @PathVariable String type) {
        return library.agent(requireType(type), workspace(wsId), agentId);
    }

    /** 新建 Agent 自身私有预装项（新建时向上一级查重：不得与工作区/全局配置重名）。 */
    @PostMapping("/workspaces/{wsId}/agents/{agentId}/{type}")
    public ContentItem createAgent(@PathVariable String wsId, @PathVariable String agentId,
                                   @PathVariable String type, @RequestBody ContentRequest req) {
        return library.save(requireType(type), req.id(), req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), workspace(wsId), agentId);
    }

    /** 更新 Agent 自身私有预装项。 */
    @PutMapping("/workspaces/{wsId}/agents/{agentId}/{type}/{id}")
    public ContentItem updateAgent(@PathVariable String wsId, @PathVariable String agentId,
                                   @PathVariable String type, @PathVariable String id,
                                   @RequestBody ContentRequest req) {
        return library.save(requireType(type), id, req.name(), req.content(), req.ref(),
                req.enabled(), req.config(), workspace(wsId), agentId);
    }

    /** 删除 Agent 自身私有预装项。 */
    @DeleteMapping("/workspaces/{wsId}/agents/{agentId}/{type}/{id}")
    public void deleteAgent(@PathVariable String wsId, @PathVariable String agentId,
                            @PathVariable String type, @PathVariable String id) {
        library.delete(requireType(type), id, workspace(wsId), agentId);
    }

    private String requireType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase();
        if (!t.equals("rules") && !t.equals("memory") && !t.equals("hook")) {
            throw new IllegalArgumentException("不支持的预装配置类型: " + type);
        }
        return t;
    }

    private Workspace workspace(String wsId) {
        return manager.byId(wsId);
    }

    public record ContentRequest(String id, String name, String content, String ref,
                                 Boolean enabled, Map<String, Object> config) {
    }
}