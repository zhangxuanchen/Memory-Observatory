/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】TaskBoard.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】任务黑板：按 (workspaceId, sessionId) 隔离的任务池。PM 拆解任务后入黑板，
 *            开发子 agent 按任务执行、出黑板；PM 验收后据反馈更新（经验回馈）。
 *            保证不会在不同工作流/会话间串任务。
 * 【核心改动】2026-08-24 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务黑板。MVP 用内存实现并做并发安全：同一负载键（workspace+session）下用锁保护。
 * 无外部依赖，服务重启后清空（工作流状态持久化见 WorkflowRunStore）。
 */
public class TaskBoard {

    /** 任务状态。 */
    public enum Status {
        PENDING, RUNNING, DONE, FAILED
    }

    /** 一条任务（含当前状态，进度回写黑板）。 */
    public record Task(String id, String description) {
        public Task {
            // record 便捷构造，保持旧调用兼容
        }

        /** 任务及其黑板状态。 */
        public record TaskState(Task task, Status status) {
        }
    }

    /** 阶段进度回执：哪个阶段产出什么结论。 */
    public record Progress(String phase, Status status, String summary) {
    }

    private final Map<String, List<Task>> store = new LinkedHashMap<>();
    private final Map<String, List<Integer>> doneIdx = new LinkedHashMap<>();
    private final Map<String, List<Progress>> progressLog = new LinkedHashMap<>();

    /** 黑板负载键：workspace + session 隔离。 */
    private String key(String workspaceId, String sessionId) {
        return (workspaceId == null ? "" : workspaceId) + "::" + (sessionId == null ? "" : sessionId);
    }

    /** 记录 PM 拆解的任务清单（幂等，重复前台覆盖该会话任务，状态复位 PENDING）。 */
    public synchronized void putAll(String workspaceId, String sessionId, List<Task> tasks) {
        String k = key(workspaceId, sessionId);
        store.put(k, new ArrayList<>(tasks));
        doneIdx.put(k, new ArrayList<>());
    }

    /** 取某会话的待执行任务（未完成的）。 */
    public synchronized List<Task> pending(String workspaceId, String sessionId) {
        return all(workspaceId, sessionId).stream().filter(t -> t.status() != Status.DONE)
                .map(Task.TaskState::task).toList();
    }

    /** 取某会话全量任务与真实状态（供观测/前端展示/PM 读进度）。 */
    public synchronized List<Task.TaskState> all(String workspaceId, String sessionId) {
        List<Task> all = store.getOrDefault(key(workspaceId, sessionId), List.of());
        List<Integer> done = doneIdx.getOrDefault(key(workspaceId, sessionId), List.of());
        List<Task.TaskState> out = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            out.add(new Task.TaskState(all.get(i), done.contains(i) ? Status.DONE : Status.PENDING));
        }
        return out;
    }

    /** 标记某会话全部任务完成（验收通过后），并返回黑板负载键是否已清理。 */
    public synchronized void completeAll(String workspaceId, String sessionId) {
        store.remove(key(workspaceId, sessionId));
        doneIdx.remove(key(workspaceId, sessionId));
    }

    /** 记录一条阶段进度回执（追加到该会话黑板进度日志）。 */
    public synchronized void recordProgress(String workspaceId, String sessionId,
                                            String phase, Status status, String summary) {
        List<Progress> log = progressLog.computeIfAbsent(key(workspaceId, sessionId), k -> new ArrayList<>());
        log.add(new Progress(phase, status, summary));
    }

    /** 读某会话黑板进度日志（PM/验收据此掌握进度）。 */
    public synchronized List<Progress> progress(String workspaceId, String sessionId) {
        return new ArrayList<>(progressLog.getOrDefault(key(workspaceId, sessionId), List.of()));
    }

    /** 黑板进度摘要文本，供拼进 PM 观察/验收上下文。 */
    public synchronized String progressSummary(String workspaceId, String sessionId) {
        List<Progress> log = progressLog.getOrDefault(key(workspaceId, sessionId), List.of());
        if (log.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Progress p : log) {
            sb.append("- ").append(p.phase()).append(" [").append(p.status()).append("]")
                    .append(p.summary() == null || p.summary().isBlank() ? "" : " " + p.summary()).append("\n");
        }
        return sb.toString();
    }
}