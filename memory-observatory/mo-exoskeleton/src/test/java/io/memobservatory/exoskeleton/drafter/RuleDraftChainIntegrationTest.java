package io.memobservatory.exoskeleton.drafter;

import io.memobservatory.exoskeleton.model.ClusterProfile;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起草链的端到端集成测试：把「候选簇 → 撰写器 → 清洗 → 写 SHADOW → 报告」这条链**整体跑通**。
 *
 * <p><b>为什么要「集成」而不只是单测</b>：单测分别验了 {@link LlmRuleDrafter} 与编排层，但没验过两者
 * **接在一起**时的行为——撰写器给出的一句草稿，是否被原样、正确地写进 SHADOW；撰写器不产出时，
 * 它的 {@code reason} 是否真的落到了报告面（登记口 B20.1 的修复点）。这两件事只有串起来才看得见。
 *
 * <p><b>边界在哪</b>：本模块无 Spring 测试基建、且纪律要求零新增依赖，故只对**最外层两个边界**打桩——
 * LLM 的 HTTP 出站（{@link StubLlm}）与 DB 的两条真 SQL（{@link StubRepo} 覆盖
 * {@code draftCandidates} / {@code insertShadow}）。**链上的真件一个不少**：真实的
 * {@link RuleDraftService} 编排、真实的 {@link LlmRuleDrafter}（含清洗 / 缓存 / fail-open）、
 * 真实的 {@code RulesReport.DraftResult} 装配。这是本仓能做到的「最外层」端到端。
 */
class RuleDraftChainIntegrationTest {

    /**
     * LLM 边界替身：不外发 HTTP。{@code script} 按调用次序给每步一个动作——
     * 元素是 {@link String} 即回该文本、是 {@link Exception} 即抛；为空则一律回 {@code reply}。
     */
    private static class StubLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<Object> script = new ArrayList<>();
        String reply = "命中即用，不为留痕额外写回";
        boolean enabled = true;

        StubLlm() {
            super("", "", "", "", 1, 0, 0);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public String chat(String system, String user, LlmUsageSink sink) throws Exception {
            int n = calls.incrementAndGet();
            Object step = script.isEmpty() ? reply : script.get(Math.min(n - 1, script.size() - 1));
            if (step instanceof Exception e) {
                throw e;
            }
            return (String) step;
        }
    }

    /** DB 边界替身：只挡两条真 SQL，其余链路都是真件。 */
    private static class StubRepo extends RuleRepository {
        final List<ClusterProfile> candidates = new ArrayList<>();
        final List<String[]> inserted = new ArrayList<>();   // 每次写入记下 {clusterKey, body}
        int insertResult = 1;

        StubRepo() {
            super(null);   // 本测试不碰真 JdbcTemplate：draftCandidates / insertShadow 都被覆盖
        }

        @Override
        public List<ClusterProfile> draftCandidates() {
            return candidates;
        }

        @Override
        public int insertShadow(String clusterKey, String body) {
            inserted.add(new String[]{clusterKey, body});
            return insertResult;
        }
    }

    /**
     * 草稿缓存替身：起草链的第三个边界（B20.3 引入的 {@link DraftCache}）。不碰 JdbcTemplate，
     * 只留一张内存表——链上其余部分（编排 / 撰写器 / 清洗 / 越界校验）都是真件。
     */
    private static class StubCache extends DraftCache {
        final Map<String, String> stored = new HashMap<>();

        StubCache() {
            super(null);
        }

        @Override
        public Optional<String> get(String clusterKey) {
            return Optional.ofNullable(stored.get(clusterKey));
        }

        @Override
        public void put(String clusterKey, String body, String model) {
            stored.put(clusterKey, body);
        }
    }

    @Test
    @DisplayName("全链路：候选簇经真撰写器清洗后原样写入 SHADOW，报告如实标「已写入」")
    void 全链路() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readwrite|steps:4-10", 88));

        StubLlm llm = new StubLlm();
        llm.reply = "  边读边写：合并后再落笔。  ";   // 故意带首尾空白，验清洗确实在链上生效

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new LlmRuleDrafter(llm, new StubCache())).draftMissing();

        assertEquals(1, result.candidates());
        assertEquals(1, result.created());
        assertEquals(1, result.outcomes().size());

        RulesReport.DraftOutcome o = result.outcomes().get(0);
        assertEquals("readwrite|steps:4-10", o.clusterKey());
        assertEquals(88, o.turns());
        assertEquals("边读边写：合并后再落笔。", o.body());
        assertTrue(o.written());
        assertEquals("已写入 SHADOW", o.detail());

        // 写进库的正是清洗后的同一句、同一个簇键——编排层没有改写它
        assertEquals(1, repo.inserted.size());
        assertArrayEquals(new String[]{"readwrite|steps:4-10", "边读边写：合并后再落笔。"}, repo.inserted.get(0));
        // LLM 只被调用一次（同簇不重复付费）
        assertEquals(1, llm.calls.get());
    }

    @Test
    @DisplayName("B20.1 修复：LLM 未配置时报告面给出真因，而不是笼统的「撰写器无对应策略」")
    void 未配置时报告真因() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readonly|steps:4-10", 50));

        StubLlm llm = new StubLlm();
        llm.enabled = false;

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new LlmRuleDrafter(llm, new StubCache())).draftMissing();

        assertEquals(1, result.candidates());
        assertEquals(0, result.created());

        RulesReport.DraftOutcome o = result.outcomes().get(0);
        assertNull(o.body());
        assertFalse(o.written());
        assertEquals("LLM 通道未配置（base-url / model 为空）", o.detail());
        // fail-open 的「不写」如实体现在库上：一条都没落
        assertTrue(repo.inserted.isEmpty());
        assertEquals(0, llm.calls.get());
    }

    @Test
    @DisplayName("越界草稿（B20.4）在链上被拦：不写库、报告面说清是越界")
    void 越界草稿不进库() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readonly|steps:4-10", 50));

        StubLlm llm = new StubLlm();
        llm.reply = "第一步读记忆，第二步按结论落笔";

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new LlmRuleDrafter(llm, new StubCache())).draftMissing();

        assertEquals(1, result.candidates());
        assertEquals(0, result.created());

        RulesReport.DraftOutcome o = result.outcomes().get(0);
        assertNull(o.body());
        assertFalse(o.written());
        assertTrue(o.detail().contains("越界"), o.detail());
        assertTrue(repo.inserted.isEmpty());
    }

    @Test
    @DisplayName("隔离：一簇调用失败只毁这一簇，另一簇照常写入")
    void 单簇失败不影响其余() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readonly|steps:1-3", 30));   // 第 1 次调用：失败
        repo.candidates.add(new ClusterProfile("readonly|steps:4-10", 40));  // 第 2 次调用：成功

        StubLlm llm = new StubLlm();
        llm.script.add(new RuntimeException("connection reset"));
        llm.script.add("只读未写：命中即用");

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new LlmRuleDrafter(llm, new StubCache())).draftMissing();

        assertEquals(2, result.candidates());
        assertEquals(1, result.created());

        RulesReport.DraftOutcome failed = result.outcomes().get(0);
        assertFalse(failed.written());
        assertTrue(failed.detail().startsWith("LLM 调用失败："), failed.detail());
        assertTrue(failed.detail().contains("connection reset"), failed.detail());

        RulesReport.DraftOutcome ok = result.outcomes().get(1);
        assertTrue(ok.written());
        assertEquals("只读未写：命中即用", ok.body());
        assertEquals("已写入 SHADOW", ok.detail());

        // 只有成功那一簇进了库
        assertEquals(1, repo.inserted.size());
        assertEquals("readonly|steps:4-10", repo.inserted.get(0)[0]);
    }

    @Test
    @DisplayName("重跑幂等：库里已有该簇规则时，写入被跳过，报告标「已存在」")
    void 已存在则跳过() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readonly|steps:4-10", 40));
        repo.insertResult = 0;   // 模拟 ON CONFLICT (rule_id, version) DO NOTHING

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new LlmRuleDrafter(new StubLlm(), new StubCache())).draftMissing();

        assertEquals(1, result.candidates());
        assertEquals(0, result.created());

        RulesReport.DraftOutcome o = result.outcomes().get(0);
        assertNotNull(o.body());       // 草稿本身有
        assertFalse(o.written());      // 只是没写进去
        assertEquals("已存在，跳过", o.detail());
    }

    @Test
    @DisplayName("换实现不改编排：替身撰写器走同一条链，产出自己的固定文本并写入")
    void 替身走同一条链() {
        StubRepo repo = new StubRepo();
        repo.candidates.add(new ClusterProfile("readonly|steps:4-10", 40));

        RulesReport.DraftResult result =
                new RuleDraftService(repo, new TemplateRuleDrafter()).draftMissing();

        assertEquals(1, result.created());
        assertEquals(1, repo.inserted.size());
        assertEquals("readonly|steps:4-10", repo.inserted.get(0)[0]);
        assertTrue(repo.inserted.get(0)[1].contains("只读未写"), repo.inserted.get(0)[1]);
        assertTrue(repo.inserted.get(0)[1].contains("4–10 步"), repo.inserted.get(0)[1]);
    }
}