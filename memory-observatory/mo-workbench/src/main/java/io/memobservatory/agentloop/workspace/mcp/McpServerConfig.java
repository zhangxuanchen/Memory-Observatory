/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · mcp
 * 【文件】McpServerConfig.java（io.memobservatory.agentloop.workspace.mcp）
 * 【核心功能】MCP Server 接入配置：一条配置对应一个外部 MCP server（Streamable HTTP）。
 * 【核心改动】2026-09-01 新增（MCP Client 接入，供 Skill 调用）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Map;

/**
 * MCP Server 接入配置。
 *
 * @param id        唯一 id（小写短横线，技能 tools 里以 mcp:{id}/{tool} 引用）
 * @param name      展示名
 * @param url       Streamable HTTP 端点（如 https://host/mcp）
 * @param headers   附加请求头（如 Authorization）
 * @param enabled   是否启用（停用后不参与装配）
 * @param note      备注
 * @param createdAt 创建时间（ISO 字符串）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(builder = McpServerConfig.Builder.class)
public record McpServerConfig(
        String id,
        String name,
        String url,
        Map<String, String> headers,
        boolean enabled,
        String note,
        String createdAt) {

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private String id, name, url, note, createdAt;
        private Map<String, String> headers;
        private boolean enabled = true;

        public Builder id(String v) { this.id = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder headers(Map<String, String> v) { this.headers = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder note(String v) { this.note = v; return this; }
        public Builder createdAt(String v) { this.createdAt = v; return this; }
        public McpServerConfig build() { return new McpServerConfig(id, name, url, headers, enabled, note, createdAt); }
    }
}
