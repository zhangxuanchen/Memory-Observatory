/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】Workspace.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】工作区运行态句柄：清单 + 根目录。Agent 的绝对影响边界 = root()。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 2 / 5 章）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import io.memobservatory.agentloop.workspace.manifest.WorkspaceManifest;

import java.nio.file.Path;

/**
 * 工作区运行态句柄：承载清单与根目录。
 *
 * @param manifest 工作区清单
 * @param root     工作区根目录绝对路径（Agent 影响边界）
 */
public record Workspace(WorkspaceManifest manifest, Path root) {

    public String id() {
        return manifest.id();
    }

    public String name() {
        return manifest.name();
    }
}