/*******************************************************************************
 * 【模块】Agent 工作流 Workflow
 * 【文件】TaskBoardTest.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】任务黑板的隔离与幂等回归：workspace+session 键隔离、putAll 覆盖复位、
 *            completeAll 清板、进度日志追加与摘要格式。
 * 【核心改动】2026-10-12 架构审视「workbench 零测试」补齐。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TaskBoard} 行为测试（内存实现，直接构造，无替身）。
 */
class TaskBoardTest {

    private static List<TaskBoard.Task> tasks(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new TaskBoard.Task(id, "desc-" + id)).toList();
    }

    /** 入板即可 pending；同键重复 putAll 是覆盖并复位，不是追加。 */
    @Test
    void 入板覆盖与复位() {
        TaskBoard b = new TaskBoard();
        b.putAll("w1", "s1", tasks("T1", "T2"));
        assertThat(b.pending("w1", "s1")).extracting(TaskBoard.Task::id).containsExactly("T1", "T2");

        b.putAll("w1", "s1", tasks("T3"));
        assertThat(b.pending("w1", "s1")).extracting(TaskBoard.Task::id).containsExactly("T3");
        assertThat(b.all("w1", "s1")).allSatisfy(ts ->
                assertThat(ts.status()).isEqualTo(TaskBoard.Status.PENDING));
    }

    /** 跨工作流/跨会话绝不串任务——这是黑板的第一职责。 */
    @Test
    void 键隔离() {
        TaskBoard b = new TaskBoard();
        b.putAll("w1", "s1", tasks("A"));
        b.putAll("w1", "s2", tasks("B"));
        b.putAll("w2", "s1", tasks("C"));
        assertThat(b.pending("w1", "s1")).extracting(TaskBoard.Task::id).containsExactly("A");
        assertThat(b.pending("w1", "s2")).extracting(TaskBoard.Task::id).containsExactly("B");
        assertThat(b.pending("w2", "s1")).extracting(TaskBoard.Task::id).containsExactly("C");
        // 没写过的键读到的是空板，不是 NPE
        assertThat(b.pending("w9", "s9")).isEmpty();
    }

    /** completeAll 是清板语义：任务与状态一起消失（验收通过后黑板不留残迹）。 */
    @Test
    void 清板语义() {
        TaskBoard b = new TaskBoard();
        b.putAll("w1", "s1", tasks("T1"));
        b.completeAll("w1", "s1");
        assertThat(b.all("w1", "s1")).isEmpty();
        assertThat(b.pending("w1", "s1")).isEmpty();
    }

    /** 进度日志按会话追加、顺序保持；摘要行格式固定供 PM 上下文拼接。 */
    @Test
    void 进度日志与摘要() {
        TaskBoard b = new TaskBoard();
        assertThat(b.progressSummary("w1", "s1")).isEmpty();

        b.recordProgress("w1", "s1", "plan", TaskBoard.Status.DONE, "拆出 3 任务");
        b.recordProgress("w1", "s1", "dev", TaskBoard.Status.FAILED, null);
        assertThat(b.progress("w1", "s1")).extracting(TaskBoard.Progress::phase)
                .containsExactly("plan", "dev");

        String summary = b.progressSummary("w1", "s1");
        assertThat(summary).contains("- plan [DONE] 拆出 3 任务\n").contains("- dev [FAILED]\n");
        // 别的会话读不到这条日志
        assertThat(b.progress("w1", "s2")).isEmpty();
    }
}
