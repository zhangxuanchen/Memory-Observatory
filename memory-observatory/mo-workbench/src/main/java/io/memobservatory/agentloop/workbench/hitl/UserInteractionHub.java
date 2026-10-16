/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · hitl
 * 【文件】UserInteractionHub.java（io.memobservatory.agentloop.workbench.hitl）
 * 【核心功能】ask_user 挂起-恢复中枢：实现 UserInteraction 抽象；Agent 侧 suspend
 *            挂起等待，Web 侧 submitAnswer 提交回答唤醒，超时兜底防永久挂起。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程；实现 tools.UserInteraction 抽象。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.hitl;

import io.memobservatory.agentloop.workbench.tools.UserInteraction;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ask_user 的挂起-恢复中枢：实现 {@link UserInteraction} 抽象，Agent 侧调用
 * {@link #suspend} 挂起等待，Web 侧通过 {@link #submitAnswer} 提交回答唤醒。
 * 超时兜底避免循环永远挂着。
 */
public class UserInteractionHub implements UserInteraction {

    public record PendingQuestion(String questionId, String sessionId,
                                  String question, List<String> options, String reason) {
    }

    private static final Duration ASK_TIMEOUT = Duration.ofMinutes(10);

    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Map<String, PendingQuestion> questions = new ConcurrentHashMap<>();

    /**
     * Agent 侧调用：登记问题并返回一个"用户回答时才会完成"的 Future。
     * 超时（10 分钟）后 Future 会以异常结束，由 AskUserTool 的 onErrorResume 兜底。
     *
     * @param sessionId 当前会话
     * @param question  问题文本
     * @param options   可选答案（可空）
     * @param reason    为何提问的原因/背景（可空）
     * @return 用户回答时完成的 Future
     */
    @Override
    public CompletableFuture<String> suspend(String sessionId,
                                             String question,
                                             List<String> options,
                                             String reason) {
        String qid = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        future.orTimeout(ASK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        pending.put(qid, future);
        questions.put(qid,
                new PendingQuestion(qid, sessionId, question,
                        options == null ? List.of() : options,
                        reason == null ? "" : reason));
        return future;
    }

    /**
     * Web 侧调用：用户提交回答，唤醒挂起的工具。
     *
     * @param questionId 问题 ID
     * @param answer     用户回答
     * @return true = 成功唤醒；false = 问题不存在或已超时消失
     */
    public boolean submitAnswer(String questionId, String answer) {
        CompletableFuture<String> future = pending.remove(questionId);
        if (future == null) return false;
        questions.remove(questionId);
        return future.complete(answer);
    }

    /** Web 侧查询：当前所有待回答的问题。 */
    public Collection<PendingQuestion> openQuestions() {
        return questions.values();
    }

    /**
     * Web 侧调用：用户点击"停止"，让挂起的 ask_user 以取消结束。
     * 移出待答队列，并以 CancellationException 结束 Future，由 AskUserTool 的
     * onErrorResume 解析为"用户请求停止"，下达停止指令给 Agent。
     *
     * @param questionId 要取消的问题 ID
     * @return true = 成功取消；false = 问题不存在或已超时消失
     */
    public boolean cancel(String questionId) {
        CompletableFuture<String> future = pending.remove(questionId);
        if (future == null) return false;
        questions.remove(questionId);
        future.completeExceptionally(new CancellationException("user cancelled"));
        return true;
    }
}