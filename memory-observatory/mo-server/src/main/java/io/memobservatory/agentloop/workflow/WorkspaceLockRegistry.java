/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】WorkspaceLockRegistry.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】并发隔离：按 (workspaceId, sessionId) 粒度分发可重入锁。同一工作流/
 *            会话同时只允许一个引擎实例推进，防止并行覆盖工作区改动。粒度活跃后
 *            使用弱引用映射避免无界增长。
 * 【核心改动】2026-08-24 新增。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 工作流并发隔离锁注册表。tryRun 尝试获取锁，成功返回 true 并可 finally 中 unlock。
 */
public class WorkspaceLockRegistry {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private String key(String workspaceId, String sessionId) {
        return (workspaceId == null ? "" : workspaceId) + "::" + (sessionId == null ? "" : sessionId);
    }

    private ReentrantLock lockFor(String workspaceId, String sessionId) {
        return locks.computeIfAbsent(key(workspaceId, sessionId), k -> new ReentrantLock());
    }

    /** 尝试加锁（非阻塞）。成功调用方务必在 finally 中 {@link #unlock}。 */
    public boolean tryLock(String workspaceId, String sessionId) {
        return lockFor(workspaceId, sessionId).tryLock();
    }

    public void unlock(String workspaceId, String sessionId) {
        ReentrantLock l = locks.get(key(workspaceId, sessionId));
        if (l != null && l.isHeldByCurrentThread()) {
            l.unlock();
        }
    }
}