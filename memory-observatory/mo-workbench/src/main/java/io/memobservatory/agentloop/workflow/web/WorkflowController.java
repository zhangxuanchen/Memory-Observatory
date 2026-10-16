/*******************************************************************************
 * 【模块】Agent 工作流 Workflow · web 端点
 * 【文件】WorkflowController.java（io.memobservatory.agentloop.workflow.web）
 * 【核心功能】工作流宿主端点：POST /workflow/run 返回 SSE 流，按角色/阶段透传
 *            token / tool / phase（阶段进度）；开一个独立线程跑 WorkflowEngine。
 * 【核心改动】2026-08-24 新增。
 * 【设计要点】仿 AgentController 的 SseEmitter 模式；引擎阻塞在独立线程，控制器仅负责
 *            流式转发与收尾。阶段进度事件 name=phase，token 事件 name=token。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow.web;

import io.memobservatory.agentloop.workflow.WorkflowEngine;
import io.memobservatory.agentloop.workflow.WorkflowRunStore;
import io.memobservatory.agentloop.workflow.WorkflowTemplateInitializer;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 多角色工作流宿主端点（Servlet SseEmitter，SSE 流式）。
 * <ul>
 *   <li>POST /api/workflow/run 返回 SSE 流，事件名：phase / token / tool / gate / task /
 *       reuse / phase_end / done / error。</li>
 *   <li>GET  /api/workflow/status 查询某工作流断点存档（供续跑/观测）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowController.class);

    private final WorkflowEngine engine;
    private final WorkflowTemplateInitializer templateInitializer;
    private final WorkflowRunStore runStore;
    private final WorkspaceManager workspaceManager;
    /** 可选鉴权：非空时要求请求头 X-Api-Key 匹配才放行；为空则开放（便于本地自测）。 */
    private final String apiKey;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public WorkflowController(WorkflowEngine engine, WorkflowTemplateInitializer templateInitializer,
                              WorkflowRunStore runStore, WorkspaceManager workspaceManager,
                              @Value("${mo.agent.workflow.api-key:}") String apiKey) {
        this.engine = engine;
        this.templateInitializer = templateInitializer;
        this.runStore = runStore;
        this.workspaceManager = workspaceManager;
        this.apiKey = apiKey;
    }

    /** 一次工作流运行请求。 */
    public record WorkflowRequest(String workspaceId, String sessionId, String userId,
                                  String message, String workflowId) {
    }

    /** 鉴权：api-key 未配置则放行；配置后必须匹配。 */
    private boolean authorized(String key) {
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        return apiKey.equals(key);
    }

    /** 查询工作流断点存档。 */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam String workflowId, @RequestParam String workspaceId,
                                    @RequestHeader(value = "X-Api-Key", required = false) String key) {
        if (!authorized(key)) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
        }
        try {
            Workspace ws = workspaceManager.byId(workspaceId);
            WorkflowRunStore.RunState st = runStore == null ? null : runStore.load(ws, workflowId);
            if (st == null) {
                return ResponseEntity.ok(Map.of("workflowId", workflowId, "found", false));
            }
            return ResponseEntity.ok(Map.of(
                    "workflowId", st.wfId(),
                    "status", st.status(),
                    "at", st.at(),
                    "found", true,
                    "phases", st.phases()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 启动一条工作流并以 SSE 流返回各阶段/角色进度与最终交付。 */
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody WorkflowRequest req,
                          @RequestHeader(value = "X-Api-Key", required = false) String key) {
        final SseEmitter emitter = new SseEmitter(90 * 60_000L);
        if (!authorized(key)) {
            try {
                emitter.send(SseEmitter.event().name("error").data("unauthorized"));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }
        // 多阶段构建耗时较长，给足时间；用户中断/出错会提前 complete
        final AtomicBoolean completed = new AtomicBoolean(false);
        final WorkflowEngine.RunRequest runReq = new WorkflowEngine.RunRequest(
                req.workspaceId(), req.sessionId(), req.userId(),
                req.message(), req.workflowId());

        // 引擎阻塞执行，放入独立线程，避免阻塞 HTTP 请求线程
        executor.submit(() -> {
            try {
                // 先确保 java-mvn / frontend 模板清单在该工作区存在
                templateInitializer.ensureTemplates(req.workspaceId());
                engine.run(runReq, (name, data) -> send(emitter, completed, name, data));
            } catch (Throwable t) {
                // 记录异常栈，避免被 finally 静默吞掉导致前端只见空 SSE
                log.error("[workflow] run aborted by exception", t);
                send(emitter, completed, "error", "ERROR: " + t.getMessage());
            } finally {
                // engine 内部已发 done/error；这里兜底关闭连接
                try {
                    if (completed.compareAndSet(false, true)) {
                        emitter.complete();
                    } else {
                        emitter.completeWithError(new IllegalStateException("workflow closed"));
                    }
                } catch (Exception ignored) {
                }
            }
        });
        return emitter;
    }

    /** 写一条 SSE 事件；前端断连后置标记并停止后续发送（不向外抛，避免中断引擎流程）。 */
    private void send(SseEmitter emitter, AtomicBoolean completed, String name, String data) {
        if (completed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            log.warn("workflow sse send failed, close: {}", e.toString());
            completed.set(true);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }
}