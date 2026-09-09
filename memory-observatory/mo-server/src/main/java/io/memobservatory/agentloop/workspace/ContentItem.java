/*******************************************************************************
 * 【模块】Workspace-Agent · workspace
 * 【文件】ContentItem.java（io.memobservatory.agentloop.workspace）
 * 【核心功能】预装配置（规则 rules / 记忆 memory / 钩子 hook）的统一数据模型，序列化为
 *            <type>/<id>.yaml。rules/memory 用 name+content，hook 用 ref+enabled+config。
 *            scope 由所在目录判定（不入业务语义）。
 * 【核心改动】2026-08-26 新增（全局+工作区合并装载）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 预装配置项。三类共用一个模型：
 * <ul>
 *   <li>rules / memory：<b>content</b> 为正文（规则或预装记忆），name 为标题；</li>
 *   <li>hook：<b>ref</b> 为钩子类型（audit / memory-report），enabled 是否启用，config 扩展配置。</li>
 * </ul>
 *
 * @param id      配置 id（同一 type 下全局与工作区唯一）
 * @param type    类型：rules / memory / hook
 * @param name    标题（rules/memory 用）
 * @param content 正文（rules/memory 用）
 * @param ref     钩子类型（hook 用）
 * @param enabled 是否启用（默认 true）
 * @param config  扩展配置（hook 用，可空）
 * @param scope   作用域（global / workspace，由目录判定）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentItem(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("name") String name,
        @JsonProperty("content") String content,
        @JsonProperty("ref") String ref,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("config") Map<String, Object> config,
        @JsonProperty("scope") String scope) {

    /** 归一化构造：空值与 null 统一成安全默认，enabled 缺省视为启用。 */
    public static ContentItem of(String id, String type, String name, String content,
                                 String ref, Boolean enabled, Map<String, Object> config, String scope) {
        return new ContentItem(id, type,
                name == null ? "" : name,
                content == null ? "" : content,
                ref,
                enabled == null ? Boolean.TRUE : enabled,
                config == null ? Map.of() : config,
                scope);
    }
}