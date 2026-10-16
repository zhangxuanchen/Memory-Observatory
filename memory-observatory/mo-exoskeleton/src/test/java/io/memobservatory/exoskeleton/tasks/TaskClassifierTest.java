package io.memobservatory.exoskeleton.tasks;

import io.memobservatory.exoskeleton.model.TaskClassifyReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskClassifier} 的互斥保护（任务 B）：整轮分类在跑时重复进入如实跳过。
 *
 * <p>真起一个线程卡在取数上，主线程再进一次——验的是「运行标志真的在起作用」。
 *
 * <p>不用 Mockito（本机 JDK 26 上 Byte Buddy 不支持），改用零依赖的手写替身。
 */
class TaskClassifierTest {

    /** LLM 替身：总是「已配置」。 */
    private static class FakeLlm extends LlmChatClient {
        FakeLlm() {
            super("", "", "", "", 1, 0, 0);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String model() {
            return "fake";
        }
    }

    /** 取数替身：进入后卡在闩上，直到被释放。 */
    private static class BlockingRepo extends TaskTypeRepository {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingRepo(CountDownLatch entered, CountDownLatch release) {
            super((JdbcTemplate) null);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public List<SessionDigest> pendingSessions(int limit, boolean force) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public long countStaleVersion(String version) {
            return 0L;
        }
    }

    @Test
    @DisplayName("已有分类在跑时：重复进入如实跳过，不报错也不伪装成功")
    void 已有分类在跑时如实跳过() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        TaskClassifier classifier = new TaskClassifier(new FakeLlm(), new BlockingRepo(entered, release),
                new LlmUsageMeter());
        ReflectionTestUtils.setField(classifier, "enabled", true);

        Thread first = new Thread(() -> classifier.runOnce(false));
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个分类应已进入");

        TaskClassifyReport second = classifier.runOnce(false);

        assertTrue(second.enabled(), "跳过不是「未启用」，enabled 仍应为 true");
        assertEquals(0, second.classified());
        assertEquals(0, second.failed());
        assertTrue(second.note().contains("运行中"), second.note());

        release.countDown();
        first.join(5000);
        assertFalse(first.isAlive(), "第一个分类应在释放后跑完并释放运行标志");
    }

    // ------------------------------------------------------------------
    // 多数决（改造 2）：单次 LLM 判定可靠性与掷硬币相当，故取奇数评委多数决
    // ------------------------------------------------------------------

    /** LLM 替身：按调用次序逐条返回预置输出（多数决要多判几次，故要能吃脚本）。 */
    private static class ScriptedLlm extends LlmChatClient {
        private final List<String> script;
        private int i;

        ScriptedLlm(String... outputs) {
            super("", "", "", "", 1, 0, 0);
            this.script = List.of(outputs);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String model() {
            return "fake";
        }

        @Override
        public String chat(String system, String user, LlmUsageSink sink) {
            return i < script.size() ? script.get(i++) : "";
        }
    }

    /** 取数替身：返回固定的一条 session，并捕获回写。 */
    private static class CapturingRepo extends TaskTypeRepository {
        private final List<SessionDigest> pending;
        final List<SessionTask> written = new ArrayList<>();

        CapturingRepo(List<SessionDigest> pending) {
            super((JdbcTemplate) null);
            this.pending = pending;
        }

        @Override
        public List<SessionDigest> pendingSessions(int limit, boolean force) {
            return pending;
        }

        @Override
        public int writeBackAll(List<SessionTask> tasks) {
            written.addAll(tasks);
            return tasks.size();
        }

        @Override
        public long countStaleVersion(String version) {
            return 0L;
        }
    }

    private static TaskClassifier classifier(LlmChatClient llm, TaskTypeRepository repo, int judges) {
        TaskClassifier c = new TaskClassifier(llm, repo, new LlmUsageMeter());
        ReflectionTestUtils.setField(c, "enabled", true);
        ReflectionTestUtils.setField(c, "judgeCount", judges);
        return c;
    }

    private static List<TaskTypeRepository.SessionDigest> oneSession() {
        return List.of(new TaskTypeRepository.SessionDigest("s1", "a1", "import", 2, List.of("q1", "q2")));
    }

    @Test
    @DisplayName("多数决：3 评委 2:1 → 取多数票，并落 judge_mode=multi-3 与票型")
    void 多数决取多数票() {
        CapturingRepo repo = new CapturingRepo(oneSession());
        TaskClassifier c = classifier(new ScriptedLlm(
                "{\"task_type\":\"fix_bug\",\"note\":\"修错\"}",
                "{\"task_type\":\"fix_bug\",\"note\":\"修错2\"}",
                "{\"task_type\":\"explain\",\"note\":\"答疑\"}"), repo, 3);

        TaskClassifyReport r = c.runOnce(false);

        assertEquals(1, r.classified());
        assertEquals(0, r.failed());
        assertEquals(1, repo.written.size());
        assertEquals("fix_bug", repo.written.get(0).taskType());
        assertEquals("multi-3", repo.written.get(0).judgeMode());
        assertEquals("fix_bug=2,explain=1", repo.written.get(0).judgeVotes());
    }

    @Test
    @DisplayName("多数决：3 评委各判一类（1-1-1）→ 无定论，判失败不猜、不落库")
    void 多数决并列即不猜() {
        CapturingRepo repo = new CapturingRepo(oneSession());
        TaskClassifier c = classifier(new ScriptedLlm(
                "{\"task_type\":\"fix_bug\"}",
                "{\"task_type\":\"explain\"}",
                "{\"task_type\":\"doc_write\"}"), repo, 3);

        TaskClassifyReport r = c.runOnce(false);

        assertEquals(0, r.classified());
        assertEquals(1, r.failed());
        assertTrue(repo.written.isEmpty(), "并列即不猜，不得落库");
    }

    @Test
    @DisplayName("judge-count=1 → 单次判定，judge_mode=single")
    void 单评委走single口径() {
        CapturingRepo repo = new CapturingRepo(oneSession());
        TaskClassifier c = classifier(new ScriptedLlm("{\"task_type\":\"refactor\"}"), repo, 1);

        TaskClassifyReport r = c.runOnce(false);

        assertEquals(1, r.classified());
        assertEquals("single", repo.written.get(0).judgeMode());
        assertEquals("refactor=1", repo.written.get(0).judgeVotes());
    }
}