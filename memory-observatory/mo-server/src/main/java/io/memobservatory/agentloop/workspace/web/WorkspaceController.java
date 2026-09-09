/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · web
 * 【文件】WorkspaceController.java（io.memobservatory.agentloop.workspace.web）
 * 【核心功能】工作区管理端点：POST 登记工作区、GET 取工作区、创建/枚举/删除工作区内 Agent。
 *            校验失败抛 IllegalArgumentException，由全局异常处理器转成 400 提示。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 6 章）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.web;

import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import io.memobservatory.agentloop.workspace.manifest.AgentManifest;
import io.memobservatory.agentloop.workbench.core.AgentKeyStore;
import io.memobservatory.server.storage.EventRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 工作区管理端点。会话的对话仍走 AgentController /chat（带 workspaceId + agentId）。
 */
@RestController
@RequestMapping("/api/agent/workspaces")
public class WorkspaceController {

    private final WorkspaceManager manager;
    private final AgentKeyStore keyStore;
    private final EventRepository eventRepo;

    public WorkspaceController(WorkspaceManager manager, AgentKeyStore keyStore, EventRepository eventRepo) {
        this.manager = manager;
        this.keyStore = keyStore;
        this.eventRepo = eventRepo;
    }

    /** 列出全部已登记工作区（供下拉选择）。 */
    @GetMapping
    public List<WorkspaceView> list() {
        return manager.list().stream().map(WorkspaceController::view).toList();
    }

    /** 登记（或打开）一个工作区：root 为服务端已存在目录的绝对路径。 */
    @PostMapping
    public WorkspaceView openOrCreate(@RequestBody OpenWorkspaceRequest req) {
        return view(manager.openOrCreate(req.root(), req.name()));
    }

    /** 按 id 取工作区。 */
    @GetMapping("/{id}")
    public WorkspaceView get(@PathVariable String id) {
        return view(manager.byId(id));
    }

    /** 枚举工作区内的 Agent。 */
    @GetMapping("/{id}/agents")
    public List<AgentView> agents(@PathVariable String id) {
        Workspace ws = manager.byId(id);
        return manager.listAgents(ws).stream().map(WorkspaceController::view).toList();
    }

    /** 在工作区内创建 Agent（可挂载能力包 skills）。 */
    @PostMapping("/{id}/agents")
    public AgentView createAgent(@PathVariable String id, @RequestBody CreateAgentRequest req) {
        List<String> skills = req.skills() == null ? List.of() : req.skills();
        AgentManifest am = manager.createAgent(manager.byId(id), req.name(), req.description(), skills);
        return view(am);
    }

    /** 重命名（或清空名）工作区内 Agent（name 为空则前端回退显示 id）。 */
    @PatchMapping("/{id}/agents/{agentId}")
    public AgentView renameAgent(@PathVariable String id, @PathVariable String agentId,
                                 @RequestBody RenameAgentRequest req) {
        AgentManifest am = manager.renameAgent(manager.byId(id), agentId, req.name(), req.description());
        return view(am);
    }

    /** 从工作区移除 Agent（软删除）。 */
    @DeleteMapping("/{id}/agents/{agentId}")
    public void deleteAgent(@PathVariable String id, @PathVariable String agentId) {
        manager.deleteAgent(manager.byId(id), agentId);
    }

    /** 读取某 Agent 绑定的模型 AK 掩码态：provider → 掩码串（null=未绑定，回落全局默认）。 */
    @GetMapping("/{id}/agents/{agentId}/keys")
    public Map<String, Object> agentKeys(@PathVariable String id, @PathVariable String agentId) {
        return keyStore.status(manager.byId(id), agentId);
    }

    /**
     * 保存/维护某 Agent 绑定的模型 AK。body 为 {provider → 值}，语义：
     * null=保持原值不覆盖；空串=清除该绑定（回落全局默认）；非空=覆盖为新值。
     *
     * @return 更新后的掩码态
     */
    @PutMapping("/{id}/agents/{agentId}/keys")
    public Map<String, Object> saveAgentKeys(@PathVariable String id, @PathVariable String agentId,
                                             @RequestBody Map<String, String> body) {
        return keyStore.applyKeys(manager.byId(id), agentId,
                body == null ? Map.of() : body);
    }

    /** 读取工作区会话（Agent 即会话）：{active, msgs:{agentId:[{role,text,ts}]}}。 */
    @GetMapping("/{id}/conversations")
    public Map<String, Object> conversations(@PathVariable String id) {
        return manager.loadConversations(manager.byId(id));
    }

    /** 覆盖写工作区会话，落盘到工作区 .workbench/conversations.json。 */
    @PutMapping("/{id}/conversations")
    public Map<String, Object> saveConversations(@PathVariable String id, @RequestBody Map<String, Object> conv) {
        manager.saveConversations(manager.byId(id), conv);
        return conv;
    }

    /** 工作区日志分类报表：按 Agent 内容（agent_id）分组汇总 memory_events。
     *  返回每个 Agent 的事件数、token 合计与均值、延迟均值、操作/层级分布、首末时间范围。
     *  from/to 为可选的 ISO-8601 时间过滤。 */
    @GetMapping("/{id}/report/classify")
    public Map<String, Object> classifyReport(@PathVariable String id,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        Workspace ws = manager.byId(id);
        List<AgentManifest> agents = manager.listAgents(ws);
        List<String> ids = agents.stream().map(AgentManifest::id)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, AgentManifest> meta = new java.util.HashMap<>();
        for (AgentManifest a : agents) meta.put(a.id(), a);
        Instant f = parseInstant(from), t = parseInstant(to);

        // 折叠：把 (agent × operation × layer) 三条线汇聚成 每 agent 一个分组
        Map<String, Map<String, Object>> acc = new java.util.LinkedHashMap<>();
        // 预置当前工作区全部 Agent（含尚无事件的新 Agent），保证新建 Agent 也出现在报表里
        for (String aid : ids) {
            acc.computeIfAbsent(aid, k -> newAccGroup(k, meta));
        }
        long totalEvents = 0;
        for (Map<String, Object> row : eventRepo.queryAgentClassify(ids, f, t)) {
            String aid = (String) row.get("agent_id");
            Map<String, Object> g = acc.computeIfAbsent(aid, k -> newAccGroup(k, meta));
            long events = (Long) row.get("events");
            long tokens = (Long) row.get("tokens");
            double avgLat = (Double) row.get("avg_latency");
            g.put("count", (long) g.get("count") + events);
            g.put("totalTokens", (long) g.get("totalTokens") + tokens);
            g.put("tokensAcc", (long) g.get("tokensAcc") + tokens);
            g.put("latencyAcc", (double) g.get("latencyAcc") + avgLat * events);
            totalEvents += events;
            ((Map<String, Long>) g.get("operation")).merge((String) row.get("operation"), events, Long::sum);
            ((Map<String, Long>) g.get("layer")).merge((String) row.get("layer"), events, Long::sum);
            if (g.get("timeFrom") == null && row.get("time_from") != null) g.put("timeFrom", row.get("time_from"));
            if (row.get("time_to") != null) g.put("timeTo", row.get("time_to"));
        }

        List<Map<String, Object>> agentsReport = new java.util.ArrayList<>(acc.values());
        for (Map<String, Object> g : agentsReport) {
            long cnt = (long) g.get("count");
            long tok = (long) g.get("totalTokens");
            g.put("avgTokens", cnt == 0 ? 0 : Math.round((double) tok / cnt));
            g.put("avgLatency", cnt == 0 ? 0 : Math.round((double) g.get("latencyAcc") / cnt));
            g.remove("tokensAcc");
            g.remove("latencyAcc");
        }
        agentsReport.sort((a, b) -> Long.compare((long) b.get("totalTokens"), (long) a.get("totalTokens")));

        // Map.of 不允许 null 值，时间过滤未传时用可变 Map 承载
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("workspaceId", id);
        result.put("from", f == null ? null : f.toString());
        result.put("to", t == null ? null : t.toString());
        result.put("totalEvents", totalEvents);
        result.put("agents", agentsReport);
        return result;
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    /** 新建一个「每 Agent 一个分组」的初始壳（不含任何事件，供预置空 Agent 与增量合并复用）。 */
    private Map<String, Object> newAccGroup(String k, Map<String, AgentManifest> meta) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("agentId", k);
        m.put("name", meta.containsKey(k) && meta.get(k).name() != null && !meta.get(k).name().isBlank()
                ? meta.get(k).name() : k);
        m.put("admin", meta.containsKey(k) && meta.get(k).admin());
        m.put("count", 0L);
        m.put("totalTokens", 0L);
        m.put("tokensAcc", 0L);
        m.put("latencyAcc", 0.0);
        m.put("operation", new java.util.HashMap<String, Long>());
        m.put("layer", new java.util.HashMap<String, Long>());
        m.put("timeFrom", null);
        m.put("timeTo", null);
        return m;
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.from(ISO.parse(s));
        } catch (Exception e) {
            return null;
        }
    }

    private static WorkspaceView view(Workspace ws) {
        return new WorkspaceView(ws.id(), ws.name(), ws.root().toString(), ws.manifest().agents());
    }

    private static AgentView view(AgentManifest am) {
        return new AgentView(
                am.id(), am.name(), am.description(),
                am.skills() == null ? List.of() : am.skills().stream().map(s -> s.id()).toList(),
                am.subagents() == null ? List.of() : am.subagents().stream().map(s -> s.id()).toList(),
                am.hooks() == null ? List.of() : am.hooks().stream().map(h -> h.type()).toList(),
                am.admin());
    }

    public record OpenWorkspaceRequest(String root, String name) {
    }

    public record CreateAgentRequest(String name, String description, List<String> skills) {
    }

    public record RenameAgentRequest(String name, String description) {
    }

    public record WorkspaceView(String id, String name, String root, List<String> agents) {
    }

    public record AgentView(String id, String name, String description,
                            List<String> skills, List<String> subagents, List<String> hooks, boolean admin) {
    }
}