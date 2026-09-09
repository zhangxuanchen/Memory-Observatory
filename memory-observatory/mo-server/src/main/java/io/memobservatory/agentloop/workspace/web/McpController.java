/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】McpController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】MCP Server 接入（Client）管理端点：配置 CRUD、连接测试、工具列举、
 *            手动调用测试、全量工具清单（skill 弹窗选择用）。
 * 【核心改动】2026-09-01 新增（MCP Client 接入，供 Skill 调用）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workspace.mcp.McpRegistry;
import io.memobservatory.agentloop.workspace.mcp.McpServerConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** MCP Server 接入管理端点（/api/mcp）。 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final McpRegistry registry;

    public McpController(McpRegistry registry) {
        this.registry = registry;
    }

    /** 配置清单。 */
    @GetMapping
    public List<McpServerConfig> list() {
        return registry.list();
    }

    /** 新建配置（id 缺省由 name 派生）。 */
    @PostMapping
    public McpServerConfig create(@RequestBody McpServerConfig req) {
        return registry.save(null, req);
    }

    /** 更新配置。 */
    @PutMapping("/{id}")
    public McpServerConfig update(@PathVariable String id, @RequestBody McpServerConfig req) {
        return registry.save(id, req);
    }

    /** 删除配置并断开连接。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        registry.delete(id);
        return Map.of("ok", true);
    }

    /** 连接测试：握手 + 工具清单（不落缓存）。 */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String id) {
        McpServerConfig cfg = registry.byId(id);
        if (cfg == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "配置不存在"));
        }
        try {
            return ResponseEntity.ok(registry.test(cfg));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    /** 连接测试（不入库、不落缓存）：body 为表单当前值，供弹窗「测试连接」内联出结果。 */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testBody(@RequestBody McpServerConfig req) {
        McpServerConfig cfg = new McpServerConfig(
                req.id() == null || req.id().isBlank() ? "temp-test" : req.id(),
                req.name() == null || req.name().isBlank() ? "临时测试" : req.name(),
                req.url(),
                req.headers() == null ? Map.of() : req.headers(),
                true, null, null);
        try {
            return ResponseEntity.ok(registry.test(cfg));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    /** 某个 server 的工具清单（走缓存连接）。 */
    @GetMapping("/{id}/tools")
    public ResponseEntity<Map<String, Object>> tools(@PathVariable String id) {
        try {
            return ResponseEntity.ok(Map.of("ok", true, "tools", registry.listTools(id)));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    /** 手动调用某工具（管理页测试）。body: {tool, arguments} */
    @PostMapping("/{id}/call")
    public ResponseEntity<Map<String, Object>> call(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String tool = String.valueOf(body.getOrDefault("tool", ""));
        if (tool.isBlank() || "null".equals(tool)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "缺少 tool"));
        }
        Object args = body.get("arguments");
        try {
            Map<String, Object> r = registry.callTool(id, tool,
                    args instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("ok", false, "error", rootMessage(e)));
        }
    }

    /** 全部启用 server 的工具清单（skill 弹窗 MCP 工具区用）。 */
    @GetMapping("/all-tools")
    public Map<String, Object> allTools() {
        return Map.of("ok", true, "tools", registry.allTools());
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
