/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】WorkspaceManifest.java（io.memobservatory.agentloop.workspace.manifest）
 * 【核心功能】工作区清单 POJO（record + Jackson 序列化）。对应 $ROOT/.workbench/manifest.json，
 *            记录工作区 id/name/agents 索引；schema 字段用于装配期版本校验。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 2.2 节）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.manifest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作区清单（文件系统即数据库）：落在工作区根目录下的 {@code .workbench/manifest.json}。
 *
 * @param schema    清单 schema 版本，当前为 1
 * @param id        工作区全局唯一 id（{@code ws_<base36 时间戳>}）
 * @param name      工作区展示名
 * @param createdAt 创建时间戳（ms）
 * @param agents    该工作区内已注册的 Agent id 列表
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceManifest(
        @JsonProperty("schema") int schema,
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("createdAt") long createdAt,
        @JsonProperty("agents") List<String> agents) {

    /** 新建工作区清单。 */
    public static WorkspaceManifest create(String id, String name) {
        return new WorkspaceManifest(1, id, name, System.currentTimeMillis(), new ArrayList<>());
    }
}