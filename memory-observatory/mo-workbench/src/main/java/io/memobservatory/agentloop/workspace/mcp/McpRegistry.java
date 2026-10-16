/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · mcp
 * 【文件】McpRegistry.java（io.memobservatory.agentloop.workspace.mcp）
 * 【核心功能】MCP Client 注册中心：外部 MCP server（Streamable HTTP）的配置落盘、
 *            连接缓存、连接测试、工具列举与调用；并产出可挂到 Agent Toolkit 的
 *            McpTool 实例（供 skill 的 tools[] 里 mcp:{serverId}/{toolName} 引用）。
 * 【依赖】AgentScope 自带 MCP 客户端（io.agentscope.core.tool.mcp.McpClientBuilder，
 *         底层为官方 MCP Java SDK），仅支持 Streamable HTTP（一期范围）。
 * 【核心改动】2026-09-01 新增（MCP Client 接入，供 Skill 调用）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** MCP Server 接入注册中心（全局共享，落盘 $MO_HOME/mcp/servers.json）。 */
@Component
public class McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final Path storeFile;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();

    public McpRegistry(WorkspaceManager manager) {
        this.storeFile = Path.of(WorkspaceManager.GLOBAL_DIR, "mcp", "servers.json");
    }

    // ---------- 配置 CRUD ----------

    public synchronized List<McpServerConfig> list() {
        if (!Files.isRegularFile(storeFile)) {
            return List.of();
        }
        try {
            return mapper.readValue(Files.readAllBytes(storeFile), new TypeReference<List<McpServerConfig>>() { });
        } catch (IOException e) {
            log.warn("MCP 配置读取失败 {}: {}", storeFile, e.toString());
            return List.of();
        }
    }

    public synchronized McpServerConfig save(String id, McpServerConfig req) {
        List<McpServerConfig> all = new ArrayList<>(list());
        String rid = (id == null || id.isBlank()) ? deriveId(req) : id;
        McpServerConfig cfg = new McpServerConfig(
                rid,
                req.name() == null ? rid : req.name(),
                req.url(),
                req.headers() == null ? Map.of() : req.headers(),
                req.enabled(),
                req.note(),
                req.createdAt() == null ? Instant.now().toString() : req.createdAt());
        all.removeIf(c -> c.id().equals(rid));
        all.add(cfg);
        persist(all);
        evict(rid);
        return cfg;
    }

    public synchronized void delete(String id) {
        List<McpServerConfig> all = new ArrayList<>(list());
        all.removeIf(c -> c.id().equals(id));
        persist(all);
        evict(id);
    }

    public McpServerConfig byId(String id) {
        return list().stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }

    private void persist(List<McpServerConfig> all) {
        try {
            Files.createDirectories(storeFile.getParent());
            Files.writeString(storeFile, mapper.writeValueAsString(all));
        } catch (IOException e) {
            throw new IllegalStateException("MCP 配置写入失败: " + e.getMessage(), e);
        }
    }

    private String deriveId(McpServerConfig req) {
        String base = req.name() == null ? "mcp" : req.name();
        String id = base.trim().toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (id.isBlank()) {
            id = "mcp-" + System.currentTimeMillis();
        }
        Set<String> taken = list().stream().map(McpServerConfig::id).collect(java.util.stream.Collectors.toSet());
        String cand = id;
        int i = 2;
        while (taken.contains(cand)) {
            cand = id + "-" + i++;
        }
        return cand;
    }

    // ---------- 连接管理 ----------

    /** 取（或建立）某 server 的已初始化连接。配置不存在/未启用返回异常。 */
    public McpClientWrapper client(String id) {
        McpServerConfig cfg = byId(id);
        if (cfg == null) {
            throw new IllegalArgumentException("MCP server 不存在: " + id);
        }
        if (!cfg.enabled()) {
            throw new IllegalStateException("MCP server 已停用: " + id);
        }
        McpClientWrapper w = clients.get(id);
        if (w != null && w.isInitialized()) {
            return w;
        }
        synchronized (this) {
            w = clients.get(id);
            if (w != null && w.isInitialized()) {
                return w;
            }
            if (w != null) {
                try { w.close(); } catch (Exception ignore) { }
            }
            w = connect(cfg);
            clients.put(id, w);
            return w;
        }
    }

    /** 建连 + initialize 握手（独立实例，不进缓存，用于测试）。 */
    private McpClientWrapper connect(McpServerConfig cfg) {
        try {
            McpClientBuilder b = McpClientBuilder.create(cfg.id())
                    .streamableHttpTransport(cfg.url())
                    .timeout(CONNECT_TIMEOUT)
                    .initializationTimeout(INIT_TIMEOUT);
            if (cfg.headers() != null && !cfg.headers().isEmpty()) {
                b.headers(cfg.headers());
            }
            McpClientWrapper w = b.buildSync();
            if (w == null) {
                throw new IllegalStateException("连接无响应");
            }
            if (!w.isInitialized()) {
                w.initialize().block(INIT_TIMEOUT);
            }
            return w;
        } catch (Exception e) {
            throw new IllegalStateException("MCP 连接失败(" + cfg.name() + "): " + rootMessage(e), e);
        }
    }

    private void evict(String id) {
        McpClientWrapper w = clients.remove(id);
        if (w != null) {
            try { w.close(); } catch (Exception ignore) { }
        }
    }

    // ---------- 工具列举 / 调用 ----------

    /** 连接测试：返回 server 名称 + 工具清单。 */
    public Map<String, Object> test(McpServerConfig cfg) {
        McpClientWrapper w = connect(cfg);
        try {
            List<Map<String, Object>> tools = describe(w.listTools().block(CALL_TIMEOUT));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("toolCount", tools.size());
            out.put("tools", tools);
            return out;
        } finally {
            try { w.close(); } catch (Exception ignore) { }
        }
    }

    /** 某个 server 的工具清单。 */
    public List<Map<String, Object>> listTools(String id) {
        return describe(client(id).listTools().block(CALL_TIMEOUT));
    }

    /** 手动调用某工具（管理页测试用）。 */
    public Map<String, Object> callTool(String id, String tool, Map<String, Object> args) {
        McpSchema.CallToolResult r = client(id).callTool(tool, args == null ? Map.of() : args).block(CALL_TIMEOUT);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) {
            out.put("ok", false);
            out.put("text", "无返回");
            return out;
        }
        StringBuilder sb = new StringBuilder();
        if (r.content() != null) {
            for (Object c : r.content()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                if (c instanceof McpSchema.TextContent tc) {
                    sb.append(tc.text());
                } else {
                    sb.append(String.valueOf(c));
                }
            }
        }
        out.put("ok", !r.isError());
        out.put("text", sb.toString());
        return out;
    }

    /** 某个 server 的单个工具，包装成可注册 Toolkit 的 McpTool。 */
    public McpTool toolFor(String serverId, String toolName) {
        McpClientWrapper w = client(serverId);
        McpSchema.Tool t = w.listTools().block(CALL_TIMEOUT).stream()
                .filter(x -> x.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "MCP 工具不存在: " + serverId + "/" + toolName));
        Map<String, Object> params = convertParams(t);
        return new McpTool(t.name(), t.description() == null ? "" : t.description(), params, w);
    }

    /** 全部启用 server 的工具清单（skill 弹窗选择用）。 */
    public List<Map<String, Object>> allTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerConfig cfg : list()) {
            if (!cfg.enabled()) {
                continue;
            }
            try {
                for (Map<String, Object> t : listTools(cfg.id())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("serverId", cfg.id());
                    row.put("serverName", cfg.name());
                    row.put("tool", t.get("name"));
                    row.put("ref", "mcp:" + cfg.id() + "/" + t.get("name"));
                    row.put("description", t.get("description"));
                    out.add(row);
                }
            } catch (Exception e) {
                log.warn("MCP 工具列举失败 {}: {}", cfg.id(), rootMessage(e));
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("serverId", cfg.id());
                err.put("serverName", cfg.name());
                err.put("error", rootMessage(e));
                out.add(err);
            }
        }
        return out;
    }

    private List<Map<String, Object>> describe(List<McpSchema.Tool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tools == null) {
            return out;
        }
        for (McpSchema.Tool t : tools) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.name());
            row.put("description", t.description() == null ? "" : t.description());
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> convertParams(McpSchema.Tool t) {
        try {
            return McpTool.convertMcpSchemaToParameters(t.inputSchema(), Set.of());
        } catch (Exception e) {
            log.warn("MCP 工具参数转换失败 {}: {}", t.name(), e.toString());
            return Map.of();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    /** 供 Controller 做运行时调用的响应结构。 */
    public record CallResult(boolean ok, String text) {
        public static CallResult of(Map<String, Object> m) {
            return new CallResult(Boolean.TRUE.equals(m.get("ok")), String.valueOf(m.get("text")));
        }
    }
}
