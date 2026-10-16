package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillDiscoverAccepted;
import io.memobservatory.exoskeleton.model.SkillDiscoverCancel;
import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillDiscoverStatus;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link SkillDiscoverer} 的互斥保护 + 异步 / 取消 / 进度（任务 B）：
 * <ul>
 *   <li>同一 Agent 在跑时重复进入如实跳过（同步）；</li>
 *   <li>已在跑时 async 被拒（{@code accepted=false}）；</li>
 *   <li>取消后主循环<b>真的不再发 LLM 调用</b>，报告标记 {@code cancelled=true}；</li>
 *   <li>进度计数：分母是「过门槛的类数」，不是枚举的 8 个。</li>
 * </ul>
 *
 * <p>不用 Mockito（本机 JDK 26 上 Byte Buddy 不支持），改用零依赖的手写替身。
 */
class SkillDiscovererTest {

    /** LLM 替身：总是「已配置」，并统计被调用的次数（用来验「取消后不再发 LLM」）。 */
    private static class CountingLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        /** 每次 chat 之后执行（可用来在「第一类跑完后」触发取消）。 */
        volatile Runnable onCall;

        CountingLlm() {
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

        @Override
        public String chat(String system, String user, io.memobservatory.exoskeleton.tasks.LlmUsageSink sink) {
            calls.incrementAndGet();
            Runnable r = onCall;
            if (r != null) {
                r.run();
            }
            if (sink != null) {
                sink.accept(true, new io.memobservatory.exoskeleton.tasks.LlmUsage(10, 1, 11));
            }
            // 返回「无技能」的合法 JSON：走 quiet 分支，不落库（无需 store 替身）
            return "{\"skill\": null, \"reason\": \"这类没有完整闭环技能\"}";
        }
    }

    /** 取数替身：进入后卡在闩上，直到被释放；类清单与摘要都为空。 */
    private static class BlockingRepo extends SkillProposalRepository {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingRepo(CountDownLatch entered, CountDownLatch release) {
            super((JdbcTemplate) null);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public List<ClassCount> classDistribution(String agentId, String sessionId) {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        @Override
        public List<String> classTurnTexts(String agentId, String taskType) {
            return List.of();
        }
    }

    /** 取数替身：两个达标类、摘要非空；提议一律不存在、上轮无记账。 */
    private static class TwoClassRepo extends SkillProposalRepository {
        TwoClassRepo() {
            super((JdbcTemplate) null);
        }

        @Override
        public List<ClassCount> classDistribution(String agentId, String sessionId) {
            return List.of(new ClassCount("doc_write", 5, 828),
                    new ClassCount("refactor", 1, 540));
        }

        @Override
        public List<String> classTurnTexts(String agentId, String taskType) {
            return List.of("反复出现的做法：请帮我做 " + taskType + " 这件事。");
        }

        @Override
        public SkillProposal find(String proposalId) {
            return null;   // 无既有提议：不触发「证据未变」跳过
        }

        @Override
        public ClassRun findClassRun(String agentId, String taskType) {
            return null;   // 从没记账：断点续跑的跳过闸门不生效
        }

        @Override
        public void recordClassRun(String agentId, String taskType, String digestHash,
                                   String outcome, long turns, String reason) {
            // 无操作：记账是旁路，不影响提炼本身
        }
    }

    /** 取数替身：把记账真的存进内存，用来验「上轮 quiet → 本轮跳过」「上轮 failed → 本轮重试」。 */
    private static class RecordingRepo extends TwoClassRepo {
        final Map<String, ClassRun> runs = new ConcurrentHashMap<>();

        @Override
        public ClassRun findClassRun(String agentId, String taskType) {
            return runs.get(agentId + "/" + taskType);
        }

        @Override
        public void recordClassRun(String agentId, String taskType, String digestHash,
                                   String outcome, long turns, String reason) {
            runs.put(agentId + "/" + taskType,
                    new ClassRun(agentId, taskType, digestHash, outcome, turns, reason, null));
        }
    }

    /** LLM 替身：可按开关在 chat 时抛异常，用来造「失败类」。 */
    private static class FailingLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail = false;

        FailingLlm() {
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

        @Override
        public String chat(String system, String user, io.memobservatory.exoskeleton.tasks.LlmUsageSink sink) {
            calls.incrementAndGet();
            if (fail) {
                if (sink != null) {
                    sink.accept(false, null);
                }
                throw new RuntimeException("boom");
            }
            if (sink != null) {
                sink.accept(true, new io.memobservatory.exoskeleton.tasks.LlmUsage(10, 1, 11));
            }
            return "{\"skill\": null, \"reason\": \"这类没有完整闭环技能\"}";
        }
    }

    /** 运行态落库替身：把开跑 / 分母 / 计数 / 终态记进内存，用来验提炼确实落运行态（P1-3）。 */
    private static class RecordingRunRepo extends SkillRunRepository {
        final List<String> started = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        final AtomicInteger bumps = new AtomicInteger();
        volatile int totals = -1;

        RecordingRunRepo() {
            super((JdbcTemplate) null);   // 不连库：所有方法都被覆盖
        }

        @Override
        public void start(String runId, String agentId) {
            started.add(agentId);
        }

        @Override
        public void setTotals(String runId, int totalClasses) {
            totals = totalClasses;
        }

        @Override
        public void setCurrent(String runId, String currentClass) {
            // 当前类是纯展示，不参与断言
        }

        @Override
        public void bumpDone(String runId) {
            bumps.incrementAndGet();
        }

        @Override
        public void finish(String runId, String status) {
            finished.add(status);
        }

        @Override
        public RunStat lastRun(String agentId) {
            return null;
        }
    }

    /** 取数替身：两个达标类；<b>第一类</b>取数时睡一下，用来在「类间」越过总时长预算（P2）。 */
    private static class SlowFirstRepo extends TwoClassRepo {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<String> classTurnTexts(String agentId, String taskType) {
            if (calls.incrementAndGet() == 1) {
                try {
                    Thread.sleep(200);   // 远大于测试设的 budget-ms，保证第二类开始前必已越线
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.classTurnTexts(agentId, taskType);
        }
    }

    /** 装好替身并设好配置项（单测不跑 Spring，@Value 默认值不会注入）。 */
    private static SkillDiscoverer discoverer(LlmChatClient llm, SkillProposalRepository repo) {
        return discoverer(llm, repo, null);
    }

    /** 带运行态落库替身的装配（P1-3 用）。 */
    private static SkillDiscoverer discoverer(LlmChatClient llm, SkillProposalRepository repo,
                                              SkillRunRepository runRepo) {
        SkillDiscoverer d = new SkillDiscoverer(llm, repo,
                new io.memobservatory.exoskeleton.tasks.LlmUsageMeter(), null, runRepo);
        ReflectionTestUtils.setField(d, "enabled", true);
        ReflectionTestUtils.setField(d, "minTurns", 30);
        ReflectionTestUtils.setField(d, "maxClasses", 8);
        ReflectionTestUtils.setField(d, "digestMaxChars", 20000);
        ReflectionTestUtils.setField(d, "parallelism", 1);
        return d;
    }

    /** 轮询等待某 Agent 的提炼结束（异步场景用）。 */
    private static void awaitNotRunning(SkillDiscoverer d, String agentId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!d.status(agentId).running()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("提炼未在超时内结束");
    }

    @Test
    @DisplayName("同一 Agent 运行中再次提炼：如实跳过，不报错也不伪装成功")
    void 同一Agent运行中再次提炼如实跳过() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        SkillDiscoverer discoverer = discoverer(new CountingLlm(), new BlockingRepo(entered, release));

        Thread first = new Thread(() -> discoverer.runOnce("agent-x"));
        first.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个提炼应已进入");

        SkillDiscoverReport second = discoverer.runOnce("agent-x");

        assertTrue(second.enabled(), "跳过不是「未启用」，enabled 仍应为 true");
        assertEquals(0, second.offered());
        assertEquals(0, second.failed());
        assertTrue(second.note().contains("正在提炼中"), second.note());

        release.countDown();
        first.join(5000);
        assertFalse(first.isAlive(), "第一个提炼应在释放后跑完并释放运行标志");
    }

    @Test
    @DisplayName("已在跑时 async 被拒：accepted=false，不重复发 LLM 调用")
    void 已在跑时async被拒() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountingLlm llm = new CountingLlm();
        SkillDiscoverer d = discoverer(llm, new BlockingRepo(entered, release));

        SkillDiscoverAccepted first = d.startAsync("agent-y");
        assertTrue(first.accepted(), "第一轮应被受理");
        assertTrue(entered.await(5, TimeUnit.SECONDS), "第一轮应已进入");

        SkillDiscoverAccepted second = d.startAsync("agent-y");
        assertFalse(second.accepted(), "已有在跑时第二次 async 必须被拒");
        assertTrue(second.running(), "running 应为 true（确实在跑）");
        assertTrue(second.note().contains("正在提炼中"), second.note());
        assertEquals(0, llm.calls.get(), "被拒的这次不应发 LLM 调用");

        release.countDown();
        awaitNotRunning(d, "agent-y", 5000);
    }

    @Test
    @DisplayName("取消后不再发 LLM：主循环在下一类开始前停下，报告标记 cancelled")
    void 取消后不再发LLM() {
        CountingLlm llm = new CountingLlm();
        SkillDiscoverer d = discoverer(llm, new TwoClassRepo());
        // 第一类（doc_write）跑完后立刻取消：第二类（refactor）开始前应被拦下，不再发 LLM
        llm.onCall = () -> d.cancel("agent-x");

        SkillDiscoverReport r = d.runOnce("agent-x");

        assertEquals(1, llm.calls.get(), "取消后不应再为第二个类发 LLM 调用");
        assertEquals(SkillDiscoverReport.STOP_CANCEL, r.stopReason(), "报告应如实标记停下原因为「已取消」");
        assertEquals(1, r.classes(), "只处理了一类（真正送 LLM 的计数）");
    }

    @Test
    @DisplayName("进度计数：分母是过门槛的类数（2），不是枚举的 8 个")
    void 进度计数() throws Exception {
        CountingLlm llm = new CountingLlm();
        SkillDiscoverer d = discoverer(llm, new TwoClassRepo());

        SkillDiscoverAccepted a = d.startAsync("agent-z");
        assertTrue(a.accepted());
        awaitNotRunning(d, "agent-z", 5000);

        SkillDiscoverStatus st = d.status("agent-z");
        assertEquals(2, st.totalClasses().intValue(), "两个类达标：分母应为 2，而不是枚举的 8");
        assertEquals(2, st.doneClasses().intValue(), "两个类都应已处理");
        assertFalse(st.running());
        assertNotNull(st.lastReport(), "跑完后应能读到上一次报告");
        assertNull(st.lastReport().stopReason(), "正常跑完：stopReason 应为 null（无提前停下）");
        assertEquals(2, llm.calls.get(), "两个类各发一次 LLM 调用");
    }

    @Test
    @DisplayName("空闲时取消：如实回 cancelRequested=false，不谎报已取消")
    void 空闲时取消如实回绝() {
        CountingLlm llm = new CountingLlm();
        SkillDiscoverer d = discoverer(llm, new TwoClassRepo());
        SkillDiscoverCancel c = d.cancel("agent-never");
        assertFalse(c.cancelRequested());
        assertTrue(c.note().contains("无需取消"), c.note());
    }

    @Test
    @DisplayName("断点续跑：上轮判过「无技能」且证据未变 → 本轮不再送 LLM")
    void 上轮无技能且证据未变则本轮跳过() {
        CountingLlm llm = new CountingLlm();
        RecordingRepo repo = new RecordingRepo();
        SkillDiscoverer d = discoverer(llm, repo);

        SkillDiscoverReport r1 = d.runOnce("agent-q");
        assertEquals(2, llm.calls.get(), "首轮两个类各发一次 LLM");
        assertEquals(2, r1.quiet(), "两类都判为无技能");
        assertEquals(0, r1.skipped(), "首轮没有可跳过的记账");

        SkillDiscoverReport r2 = d.runOnce("agent-q");
        assertEquals(2, llm.calls.get(), "第二轮证据未变，quiet 的类不得再送 LLM");
        assertEquals(2, r2.skipped(), "两类都应因「上轮判过无技能」跳过");
        assertEquals(0, r2.quiet(), "跳过不是重新判定，quiet 计数应为 0");
        assertEquals(0, r2.classes(), "没送 LLM，送 LLM 的类数应为 0");
    }

    @Test
    @DisplayName("断点续跑：失败类不跳过——指纹相同也必须重试（不把错误固化）")
    void 失败类指纹相同仍重试() {
        FailingLlm llm = new FailingLlm();
        llm.fail = true;
        RecordingRepo repo = new RecordingRepo();
        SkillDiscoverer d = discoverer(llm, repo);

        SkillDiscoverReport r1 = d.runOnce("agent-f");
        assertEquals(2, r1.failed(), "两类都应失败");
        assertEquals(2, llm.calls.get(), "失败发生在送 LLM 之时，故各发一次");

        llm.fail = false;
        SkillDiscoverReport r2 = d.runOnce("agent-f");
        assertEquals(0, r2.skipped(), "失败类不参与跳过——否则等于把错误固化");
        assertEquals(4, llm.calls.get(), "第二轮必须重试两类（累计 4 次）");
        assertEquals(2, r2.classes(), "两类都真正送了 LLM");
    }

    @Test
    @DisplayName("并发提炼：计数守恒（送 LLM 数 = 提议 + 无技能 + 失败）")
    void 并发提炼计数守恒() {
        CountingLlm llm = new CountingLlm();
        RecordingRepo repo = new RecordingRepo();
        SkillDiscoverer d = discoverer(llm, repo);
        ReflectionTestUtils.setField(d, "parallelism", 2);

        SkillDiscoverReport r = d.runOnce("agent-p");

        assertEquals(2, llm.calls.get(), "两个类并发各发一次 LLM");
        assertEquals(2, r.classes());
        assertEquals(r.classes(), r.offered() + r.quiet() + r.failed(), "送 LLM 的类数必须等于三类结果之和");
        assertEquals(2, r.quiet());
    }

    @Test
    @DisplayName("运行态落库（P1-3）：一轮跑完先落 RUNNING，分母为过门槛类数，逐类自增，末了写 DONE")
    void 运行态落库一轮跑完() {
        RecordingRunRepo runRepo = new RecordingRunRepo();
        SkillDiscoverer d = discoverer(new CountingLlm(), new TwoClassRepo(), runRepo);

        d.runOnce("agent-r");

        assertEquals(List.of("agent-r"), runRepo.started, "开跑应为本 Agent 落一行 RUNNING");
        assertEquals(2, runRepo.totals, "分母是过门槛的类数（2），不是枚举的 8 个");
        assertEquals(2, runRepo.bumps.get(), "两类各完成一次，落库计数应为 2");
        assertEquals(List.of(SkillRunRepository.DONE), runRepo.finished, "正常跑完终态应为 DONE");
    }

    // ------------------------------------------------------------------
    // P2 单轮预算 + 熔断
    // ------------------------------------------------------------------

    @Test
    @DisplayName("总 token 预算：一类跑完越线，第二类不再送 LLM，报告 stopReason=budget-tokens")
    void 总token预算越线即停() {
        CountingLlm llm = new CountingLlm();   // 每类回 11 token（total=11）
        RecordingRepo repo = new RecordingRepo();
        RecordingRunRepo runRepo = new RecordingRunRepo();
        SkillDiscoverer d = discoverer(llm, repo, runRepo);
        ReflectionTestUtils.setField(d, "budgetTokens", 11L);

        SkillDiscoverReport r = d.runOnce("agent-bt");

        assertEquals(1, llm.calls.get(), "第一类用掉 11 token 已触预算，第二类不得再送 LLM");
        assertEquals(SkillDiscoverReport.STOP_BUDGET_TOKENS, r.stopReason(), "停下原因应为「总 token 预算」");
        assertEquals(1, r.classes(), "只处理了一类");
        assertEquals(List.of(SkillRunRepository.HALTED), runRepo.finished, "预算提前停下，落库终态应为 HALTED（非 DONE）");
    }

    @Test
    @DisplayName("总时长预算：第一类耗时越线，第二类不再送 LLM，报告 stopReason=budget-time")
    void 总时长预算越线即停() {
        CountingLlm llm = new CountingLlm();
        SkillDiscoverer d = discoverer(llm, new SlowFirstRepo());
        ReflectionTestUtils.setField(d, "budgetMs", 30L);   // 第一类取数睡 200ms，必越线

        SkillDiscoverReport r = d.runOnce("agent-bm");

        assertEquals(1, llm.calls.get(), "第一类已把时长预算耗光，第二类不得再送 LLM");
        assertEquals(SkillDiscoverReport.STOP_BUDGET_TIME, r.stopReason(), "停下原因应为「总时长预算」");
        assertEquals(1, r.classes(), "只处理了一类");
    }

    @Test
    @DisplayName("熔断：LLM 连续失败达阈值即停发后续调用，报告 stopReason=breaker")
    void 连续失败触熔断即停() {
        FailingLlm llm = new FailingLlm();
        llm.fail = true;   // 每次都失败
        RecordingRepo repo = new RecordingRepo();
        SkillDiscoverer d = discoverer(llm, repo);
        ReflectionTestUtils.setField(d, "breakerMaxFailures", 1);   // 失败 1 次即熔断

        SkillDiscoverReport r = d.runOnce("agent-cb");

        assertEquals(1, llm.calls.get(), "连续失败达阈值后不得再发后续调用");
        assertEquals(SkillDiscoverReport.STOP_BREAKER, r.stopReason(), "停下原因应为「熔断」");
        assertEquals(1, r.failed(), "已失败的类计一次失败");
    }

    @Test
    @DisplayName("熔断：失败后夹一次成功即清零，不误伤后续类")
    void 成功一次即清零熔断计数() {
        CountingLlm llm = new CountingLlm();   // 恒成功
        SkillDiscoverer d = discoverer(llm, new TwoClassRepo());
        ReflectionTestUtils.setField(d, "breakerMaxFailures", 1);   // 阈值 1：若失败不清零就会误停

        SkillDiscoverReport r = d.runOnce("agent-cb2");

        assertEquals(2, llm.calls.get(), "全程成功，两类都应跑完（成功清零，不误触熔断）");
        assertNull(r.stopReason(), "没有被熔断，stopReason 应为 null");
    }
}