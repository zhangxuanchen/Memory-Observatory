/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】WorkflowRunStore.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】工作流运行状态持久化：每个 workflowId 在目标工作区
 *            $ROOT/.workbench/runs/&lt;workflowId&gt;.json 落一份断点存档，记录整体状态、
 *            各阶段状态与阶段产出文本。断线/崩溃后可续跑：已完成阶段直接复用存档
 *            产出拼接上下文，从第一个未完成阶段继续。观测类上报仍走 EventReporter。
 * 【核心改动】2026-08-24 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流断点存档。线程安全（synchronized 落盘）。
 */
public class WorkflowRunStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunStore.class);

    private final ObjectMapper om = new ObjectMapper();

    public WorkflowRunStore() {
    }

    /** 单阶段记录。 */
    public record PhaseRec(String status, String output, String at) {
    }

    /** 一次运行的存档快照。message 记录触发本次运行的需求原文，用于续跑校验。 */
    public record RunState(String wfId, String status, String at, String message,
                           Map<String, PhaseRec> phases) {
    }

    private Path file(Workspace ws, String wfId) {
        String safe = wfId.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return ws.root().toAbsolutePath().normalize()
                .resolve(WorkspaceManager.META_DIR).resolve("runs").resolve(safe + ".json");
    }

    /** 读取存档；无则返回 null。 */
    public synchronized RunState load(Workspace ws, String wfId) {
        Path f = file(ws, wfId);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            return om.readValue(json, RunState.class);
        } catch (Exception e) {
            log.warn("[workflow-store] 读取存档失败 {}: {}", f, e.toString());
            return null;
        }
    }

    /** 记录整体状态（start/done/error）。保留已有 message。 */
    public synchronized void setStatus(Workspace ws, String wfId, String status) {
        RunState cur = load(ws, wfId);
        Map<String, PhaseRec> phases = cur != null && cur.phases() != null
                ? new LinkedHashMap<>(cur.phases()) : new LinkedHashMap<>();
        save(ws, wfId, new RunState(wfId, status, Instant.now().toString(),
                cur != null ? cur.message() : null, phases));
    }

    /** 登记触发本次运行的需求原文，用于续跑时校验"需求未变"。 */
    public synchronized void recordMessage(Workspace ws, String wfId, String message) {
        RunState cur = load(ws, wfId);
        Map<String, PhaseRec> phases = cur != null && cur.phases() != null
                ? new LinkedHashMap<>(cur.phases()) : new LinkedHashMap<>();
        save(ws, wfId, new RunState(wfId, cur != null ? cur.status() : "running",
                Instant.now().toString(), message, phases));
    }

    /** 记录某阶段结果（产出覆盖更新，供续跑复用）。保留已有 message。 */
    public synchronized void recordPhase(Workspace ws, String wfId, String phaseId,
                                         String status, String output) {
        RunState cur = load(ws, wfId);
        Map<String, PhaseRec> phases = cur != null && cur.phases() != null
                ? new LinkedHashMap<>(cur.phases()) : new LinkedHashMap<>();
        phases.put(phaseId, new PhaseRec(status, output, Instant.now().toString()));
        String overall = cur != null && cur.status() != null ? cur.status() : "running";
        if ("error".equals(status) || "done".equals(status)) {
            overall = status;
        }
        save(ws, wfId, new RunState(wfId, overall, Instant.now().toString(),
                cur != null ? cur.message() : null, phases));
    }

    private void save(Workspace ws, String wfId, RunState state) {
        Path f = file(ws, wfId);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, om.writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[workflow-store] 写入存档失败 {}: {}", f, e.toString());
        }
    }
}