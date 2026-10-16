package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillDiscoverReport;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FactDiscoverer} 的三条纪律、两道跳过闸门与「失败不固化」：
 * <ul>
 *   <li>不算就不写：样本不足不提炼、解析失败计入 failed；</li>
 *   <li>少打扰：已有提议同指纹 → 跳过；上轮判过「无事实」且同指纹 → 跳过；</li>
 *   <li>绝不自行启用：只落 PENDING；</li>
 *   <li>失败不固化：上轮 FAILED 即便指纹相同也必须重试。</li>
 * </ul>
 *
 * <p>不用 Mockito（本机 JDK 26 上 Byte Buddy 不支持），改用零依赖的手写替身。
 */
class FactDiscovererTest {

    private static final String AGENT = "agent-x";
    private static final String PID = "agent:agent-x:task:__fact__";

    /** LLM 替身：按脚本回一句话（或抛异常），并统计调用次数。 */
    private static class ScriptedLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile String reply;
        volatile boolean fail = false;

        ScriptedLlm(String reply) {
            super("", "", "", "", 1, 0, 0);
            this.reply = reply;
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
        public String chat(String system, String user,
                           io.memobservatory.exoskeleton.tasks.LlmUsageSink sink) {
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
            return reply;
        }
    }

    /** 取数 / 落库替身：把记账真的存进内存，用来验两道跳过闸门与「失败不固化」。 */
    private static class FactRepo extends SkillProposalRepository {
        List<String> texts = List.of();
        volatile SkillProposal existing = null;
        volatile SkillProposal stored = null;
        final Map<String, ClassRun> runs = new ConcurrentHashMap<>();

        FactRepo() {
            super((JdbcTemplate) null);
        }

        @Override
        public List<String> agentTurnTexts(String agentId) {
            return texts;
        }

        @Override
        public SkillProposal find(String proposalId) {
            return existing;
        }

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

        @Override
        public SkillProposal store(SkillProposal p) {
            this.stored = p;
            return p;
        }
    }

    /** 造够门槛的对话（30 条）。 */
    private static List<String> enoughTurns() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            texts.add("请按项目的模块划分与目录约定，帮我把这个功能放到 " + i + " 号模块。（第 " + i + " 次反复提到同一套约定）");
        }
        return texts;
    }

    private static final String FACT_JSON = """
            {"fact": {"id": "project-conventions", "name": "项目结构约定",
             "description": "本项目的模块划分与目录约定",
             "body": "· mo-server 负责落盘\\n· mo-exoskeleton 负责提炼"}}
            """;

    private static FactDiscoverer discoverer(LlmChatClient llm, SkillProposalRepository repo,
                                             LlmUsageMeter meter) {
        FactDiscoverer d = new FactDiscoverer(llm, repo, meter);
        ReflectionTestUtils.setField(d, "minTurns", 30);
        ReflectionTestUtils.setField(d, "digestMaxChars", 20000);
        return d;
    }

    @Test
    @DisplayName("未指定 Agent：如实回绝，不发 LLM")
    void 未指定Agent如实回绝() {
        ScriptedLlm llm = new ScriptedLlm(FACT_JSON);
        FactDiscoverer d = discoverer(llm, new FactRepo(), new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(null);

        assertTrue(r.enabled(), "这不是「未启用」，enabled 仍应为 true");
        assertNull(r.agentId());
        assertEquals(0, r.classes());
        assertTrue(r.note().contains("未指定 Agent"), r.note());
        assertEquals(0, llm.calls.get(), "未指定 Agent 不应发 LLM 调用");
    }

    @Test
    @DisplayName("样本不足：不提炼、不发 LLM（不算就不写）")
    void 样本不足不提炼() {
        ScriptedLlm llm = new ScriptedLlm(FACT_JSON);
        FactRepo repo = new FactRepo();
        repo.texts = List.of("只有三条", "远远不够门槛", "不硬凑");
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(0, r.classes(), "没送 LLM，送 LLM 的类数应为 0");
        assertEquals(0, r.offered());
        assertEquals(0, llm.calls.get(), "样本不足不得发 LLM");
        assertTrue(r.note().contains("样本不足"), r.note());
        assertNull(repo.stored, "不得落任何提议");
    }

    @Test
    @DisplayName("提炼出事实：只落 PENDING，id 带 Agent 前缀，用量记在 fact 能力下")
    void 提炼出事实落成待采纳提议() {
        ScriptedLlm llm = new ScriptedLlm(FACT_JSON);
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        LlmUsageMeter meter = new LlmUsageMeter();
        FactDiscoverer d = discoverer(llm, repo, meter);

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.classes());
        assertEquals(1, r.offered(), "应落一条待采纳提议");
        assertEquals(0, r.failed());
        assertNotNull(repo.stored);
        assertEquals(PID, repo.stored.proposalId());
        assertEquals("__fact__", repo.stored.taskType());
        assertEquals("agent-x--project-conventions", repo.stored.skillId(), "事实 id 须带 Agent 前缀，互不覆盖");
        assertEquals(SkillProposal.PENDING, repo.stored.status(), "绝不自行启用");
        assertTrue(repo.stored.tools().isEmpty(), "事实不是技能，不带工具");
        assertNull(repo.stored.reasoning(), "事实无「技能提炼心路」——卡片据此不显示入口（刻意边界）");
        assertTrue(meter.rows().stream()
                        .anyMatch(row -> "fact".equals(row.capability()) && AGENT.equals(row.agentId())),
                "用量须记在 fact 能力下");
    }

    @Test
    @DisplayName("LLM 判定无稳定事实：计入 quiet，不落提议")
    void 判定无事实计入quiet() {
        ScriptedLlm llm = new ScriptedLlm("{\"fact\": null, \"reason\": \"没有反复出现的稳定约定\"}");
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.classes());
        assertEquals(1, r.quiet());
        assertEquals(0, r.offered());
        assertNull(repo.stored);
        assertEquals("QUIET", repo.runs.get(AGENT + "/__fact__").outcome());
    }

    @Test
    @DisplayName("解析失败：计入 failed，留待下轮（不算就不写）")
    void 解析失败计入failed() {
        ScriptedLlm llm = new ScriptedLlm("抱歉，我无法按 JSON 输出。");
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.classes(), "送了 LLM，故计入一类");
        assertEquals(1, r.failed());
        assertEquals(0, r.offered());
        assertNull(repo.stored);
        assertEquals("FAILED", repo.runs.get(AGENT + "/__fact__").outcome());
    }

    @Test
    @DisplayName("缺 name/body 的输出按「没提炼出来」处理，不硬塞空事实")
    void 缺字段不硬塞() {
        ScriptedLlm llm = new ScriptedLlm("{\"fact\": {\"id\": \"x\", \"name\": \"\", \"body\": \"\"}}");
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.quiet(), "缺必需字段 = 无合格事实");
        assertNull(repo.stored);
    }

    @Test
    @DisplayName("body / name 写成字符串数组也照样收（模型口味，不是没答）")
    void body为数组也收() {
        // 实测 qwen3 常把「分条列出」的 body 回成 JSON 数组；若只认字符串会误判成「缺 body」
        ScriptedLlm llm = new ScriptedLlm("""
                {"fact": {"id": "project-conventions", "name": "项目结构约定",
                 "description": "模块与目录约定",
                 "body": ["mo-server 负责落盘", "mo-exoskeleton 负责提炼"]}}
                """);
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.offered(), "数组形态的 body 应被收下，而不是判成缺字段");
        assertNotNull(repo.stored);
        assertTrue(repo.stored.prompt().contains("mo-server 负责落盘"), repo.stored.prompt());
        assertTrue(repo.stored.prompt().contains("mo-exoskeleton 负责提炼"), repo.stored.prompt());
    }

    @Test
    @DisplayName("少打扰：已有提议且证据指纹未变 → 本轮不送 LLM")
    void 证据未变则跳过() {
        ScriptedLlm llm = new ScriptedLlm(FACT_JSON);
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        String digest = EvidenceDigest.render(repo.texts, 20000);
        String hash = EvidenceDigest.sha256(AGENT + "\n__fact__\n" + digest);
        repo.existing = new SkillProposal(PID, AGENT, "__fact__", "项目结构约定", 30,
                "agent-x--project-conventions", "项目结构约定", "d", "b", List.of(),
                SkillProposal.PENDING, hash, null, null, 1, null, null);
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r = d.runOnce(AGENT);

        assertEquals(1, r.skipped(), "同一份证据不重复提炼");
        assertEquals(0, r.classes());
        assertEquals(0, llm.calls.get(), "跳过不得白花一次调用");
    }

    @Test
    @DisplayName("少打扰：上轮判过无事实且证据未变 → 本轮跳过")
    void 上轮无事实且证据未变则跳过() {
        ScriptedLlm llm = new ScriptedLlm("{\"fact\": null, \"reason\": \"没有稳定约定\"}");
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r1 = d.runOnce(AGENT);
        assertEquals(1, llm.calls.get(), "首轮应发一次 LLM");
        assertEquals(1, r1.quiet());
        assertEquals(0, r1.skipped());

        SkillDiscoverReport r2 = d.runOnce(AGENT);
        assertEquals(1, llm.calls.get(), "第二轮证据未变，quiet 的同一份证据不得再送 LLM");
        assertEquals(1, r2.skipped());
        assertEquals(0, r2.classes());
    }

    @Test
    @DisplayName("失败不固化：上轮 FAILED 即便指纹相同也必须重试")
    void 失败不固化仍重试() {
        ScriptedLlm llm = new ScriptedLlm(FACT_JSON);
        llm.fail = true;
        FactRepo repo = new FactRepo();
        repo.texts = enoughTurns();
        FactDiscoverer d = discoverer(llm, repo, new LlmUsageMeter());

        SkillDiscoverReport r1 = d.runOnce(AGENT);
        assertEquals(1, r1.failed());

        llm.fail = false;
        SkillDiscoverReport r2 = d.runOnce(AGENT);
        assertEquals(0, r2.skipped(), "失败类不参与跳过——否则等于把错误固化");
        assertEquals(1, r2.classes(), "第二轮必须重试");
        assertEquals(2, llm.calls.get(), "累计两次调用");
        assertEquals(1, r2.offered());
        assertNull(r2.stopReason(), "事实提炼不涉及单轮预算/熔断，stopReason 应为 null");
    }
}