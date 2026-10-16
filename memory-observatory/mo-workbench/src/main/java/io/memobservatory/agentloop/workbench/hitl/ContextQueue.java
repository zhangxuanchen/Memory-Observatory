/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · hitl
 * 【文件】ContextQueue.java（io.memobservatory.agentloop.workbench.hitl）
 * 【核心功能】"执行中补充上下文"的排队容器：Agent 本轮执行期间，用户可以连续
 *            追加多条上下文，先入队暂存；本轮跑完后由 chat 消费循环 pop 出
 *            下一条继续执行，队列空才结束流。按会话 key 隔离，各会话互不干扰。
 * 【核心改动】2026-08-24 新增，配合 AgentController 的循环消费实现"本轮后继续"。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.hitl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 按会话隔离的"补充上下文"FIFO 队列。<br>
 * enqueue 追加一条用户补充；poll 轮询取出下一条（队空返回 null）。
 * 只做入队/出队，不含阻塞等待，供 chat 消费循环在每条消息跑完后调用。
 * 带参 key = workspaceId + '/' + agentId + '/' + sessionId，保证不同工作区/会话隔离。
 */
public class ContextQueue {

    private final Map<String, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();

    /** 入队一条用户补充上下文。 */
    public void enqueue(String key, String message) {
        if (message == null || message.isBlank()) return;
        queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(message);
    }

    /** 取出下一条（先进先出）；队空或 key 不存在返回 null。 */
    public String poll(String key) {
        ConcurrentLinkedQueue<String> q = queues.get(key);
        return q == null ? null : q.poll();
    }

    /** 当前 key 尚未消费的补充条数。 */
    public int size(String key) {
        ConcurrentLinkedQueue<String> q = queues.get(key);
        return q == null ? 0 : q.size();
    }
}