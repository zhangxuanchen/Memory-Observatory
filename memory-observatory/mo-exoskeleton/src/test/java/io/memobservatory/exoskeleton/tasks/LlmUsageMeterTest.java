package io.memobservatory.exoskeleton.tasks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmUsageMeter} 的记账口径：
 * <ul>
 *   <li>成败都计一次调用（调用已发出，成本真实发生）；</li>
 *   <li>token 只累加成功的，且端点未上报 usage 时<b>不累加也不当 0</b>；</li>
 *   <li>一格 = 一个「Agent × 能力 × 任务类型」，不同格不串账。</li>
 * </ul>
 */
class LlmUsageMeterTest {

    @Test
    @DisplayName("成功调用：计一次调用并累加 token")
    void successAccumulatesTokens() {
        LlmUsageMeter m = new LlmUsageMeter();
        m.record("agent-a", "skill", "doc_write", true, new LlmUsage(100, 20, 120));
        m.record("agent-a", "skill", "doc_write", true, new LlmUsage(50, 10, 60));

        List<LlmUsageMeter.Row> rows = m.rows();
        assertEquals(1, rows.size(), "同一格应合并为一行");
        LlmUsageMeter.Row r = rows.get(0);
        assertEquals(2, r.calls());
        assertEquals(0, r.failed());
        assertEquals(150, r.promptTokens());
        assertEquals(30, r.completionTokens());
        assertEquals(180, r.totalTokens());
    }

    @Test
    @DisplayName("失败调用：计一次调用与失败，但不累加 token")
    void failureCountsCallWithoutTokens() {
        LlmUsageMeter m = new LlmUsageMeter();
        m.record("agent-a", "skill", "doc_write", false, null);

        LlmUsageMeter.Row r = m.rows().get(0);
        assertEquals(1, r.calls());
        assertEquals(1, r.failed());
        assertEquals(0, r.totalTokens(), "失败拿不到用量，token 保持 0（不许凭空造数）");
    }

    @Test
    @DisplayName("成功但端未上报 usage：计调用、不算失败、不累加 token")
    void successWithoutUsageIsNotFailure() {
        LlmUsageMeter m = new LlmUsageMeter();
        m.record("agent-a", "task_type", "", true, null);

        LlmUsageMeter.Row r = m.rows().get(0);
        assertEquals(1, r.calls());
        assertEquals(0, r.failed(), "成功但无用量 ≠ 失败");
        assertEquals(0, r.totalTokens());
    }

    @Test
    @DisplayName("不同 Agent / 能力 / 任务类型分格记账，不串账")
    void keysAreIndependent() {
        LlmUsageMeter m = new LlmUsageMeter();
        m.record("agent-a", "skill", "doc_write", true, new LlmUsage(10, 1, 11));
        m.record("agent-a", "skill", "refactor", true, new LlmUsage(20, 2, 22));
        m.record("agent-b", "skill", "doc_write", true, new LlmUsage(30, 3, 33));

        List<LlmUsageMeter.Row> rows = m.rows();
        assertEquals(3, rows.size(), "三种语境三行");
        assertEquals("agent-b", rows.get(0).agentId(), "按 totalTokens 降序：agent-b 的 33 排最前");
        assertEquals(33, rows.get(0).totalTokens());
    }

    @Test
    @DisplayName("reset 清空全部读数")
    void resetClears() {
        LlmUsageMeter m = new LlmUsageMeter();
        m.record("agent-a", "skill", "doc_write", true, new LlmUsage(10, 1, 11));
        assertTrue(m.rows().size() > 0);
        m.reset();
        assertEquals(0, m.rows().size());
    }
}