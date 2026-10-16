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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link SkillDiscoverer} 的事件流水记账（{@code memory_skill_events}）：
 * 每类每轮恰好记一条，人机共用同一张表、系统动作的 {@code actor=SYSTEM}；
 * 且记账是<b>旁路</b>——记不上不得掀翻提炼本身。
 */
class SkillDiscovererEventTest {

    private static final String AGENT = "agent-x";
    private static final String PID = "agent:agent-x:task:doc_write";

    private static final String SKILL_JSON = """
            {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
             "prompt": "1. 通读原文\\n2. 按目标改写\\n禁止：跳过通读直接改写", "tools": ["readFile", "editFile"]}}
            """;
    private static final String QUIET_JSON = "{\"skill\": null, \"reason\": \"这类没有完整闭环技能\"}";

    /** LLM 替身：按脚本回一句话（或抛异常），并统计调用次数。 */
    private static class ScriptedLlm extends LlmChatClient {
        final AtomicInteger calls = new AtomicInteger();
        final String reply;
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

    /** 取数 / 记账替身：把事件真的存进内存，用来数「每类每轮记了几条、什么种类」。 */
    private static class EventRepo extends SkillProposalRepository {
        List<String> texts = List.of();
        volatile SkillProposal existing = null;
        volatile SkillProposal offered = null;
        volatile boolean eventFails = false;
        final List<String> events = new ArrayList<>();

        EventRepo() {
            super((JdbcTemplate) null);
        }

        @Override
        public List<ClassCount> classDistribution(String agentId, String sessionId) {
            return List.of(new ClassCount("doc_write", 5, 828));
        }

        @Override
        public List<String> classTurnTexts(String agentId, String taskType) {
            return texts;
        }

        @Override
        public SkillProposal find(String proposalId) {
            return existing;
        }

        @Override
        public ClassRun findClassRun(String agentId, String taskType) {
            return null;   // 从没记账：断点续跑的跳过闸门不生效
        }

        @Override
        public void recordClassRun(String agentId, String taskType, String digestHash,
                                   String outcome, long turns, String reason) {
            // 无操作：本测只看事件流水
        }

        @Override
        public SkillProposal store(SkillProposal p) {
            this.offered = p;
            return p;
        }

        @Override
        public void recordEvent(String kind, String actor, String proposalId, String agentId,
                                String taskType, Integer version, String digestHash, String detail) {
            if (eventFails) {
                throw new RuntimeException("event store down");
            }
            events.add(kind + ":" + actor);
        }
    }

    private static List<String> enoughTurns() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            texts.add("请按项目的目录约定，帮我把这个文档改到第 " + i + " 稿。（反复提到同一套改写做法）");
        }
        return texts;
    }

    private static EventRepo repo() {
        EventRepo repo = new EventRepo();
        repo.texts = enoughTurns();
        return repo;
    }

    private static SkillDiscoverer discoverer(LlmChatClient llm, SkillProposalRepository repo) {
        SkillDiscoverer d = new SkillDiscoverer(llm, repo, new LlmUsageMeter());
        ReflectionTestUtils.setField(d, "enabled", true);
        ReflectionTestUtils.setField(d, "minTurns", 30);
        ReflectionTestUtils.setField(d, "maxClasses", 8);
        ReflectionTestUtils.setField(d, "digestMaxChars", 20000);
        ReflectionTestUtils.setField(d, "parallelism", 1);
        return d;
    }

    @Test
    @DisplayName("出提议：记且只记一条 OFFER 事件，actor=SYSTEM")
    void 出提议记一条OFFER() {
        EventRepo repo = repo();

        SkillDiscoverReport r = discoverer(new ScriptedLlm(SKILL_JSON), repo).runOnce(AGENT);

        assertEquals(1, r.offered());
        assertEquals(List.of("OFFER:SYSTEM"), repo.events);
        assertNotNull(repo.offered);
        assertEquals(SkillProposal.PENDING, repo.offered.status(), "只落 PENDING，绝不自行启用");
    }

    @Test
    @DisplayName("判无技能：记且只记一条 QUIET 事件，actor=SYSTEM")
    void 判无技能记一条QUIET() {
        EventRepo repo = repo();

        SkillDiscoverReport r = discoverer(new ScriptedLlm(QUIET_JSON), repo).runOnce(AGENT);

        assertEquals(1, r.quiet());
        assertEquals(List.of("QUIET:SYSTEM"), repo.events);
    }

    @Test
    @DisplayName("证据未变跳过：记且只记一条 SKIP 事件，且不再白花一次 LLM 调用")
    void 跳过记一条SKIP() {
        EventRepo repo = repo();
        String digest = EvidenceDigest.render(repo.texts, 20000);
        String hash = EvidenceDigest.sha256(AGENT + "\ndoc_write\n" + digest);
        repo.existing = new SkillProposal(PID, AGENT, "doc_write", "文档撰写", 828, "agent-x--doc-refine",
                "文档精修", "d", "p", List.of(), SkillProposal.ADOPTED, hash, null, null, 1, null, null);
        ScriptedLlm llm = new ScriptedLlm(SKILL_JSON);

        SkillDiscoverReport r = discoverer(llm, repo).runOnce(AGENT);

        assertEquals(1, r.skipped());
        assertEquals(0, llm.calls.get(), "跳过不得白花一次调用");
        assertEquals(List.of("SKIP:SYSTEM"), repo.events);
    }

    @Test
    @DisplayName("提炼失败：记且只记一条 FAILED 事件，actor=SYSTEM")
    void 失败记一条FAILED() {
        EventRepo repo = repo();
        ScriptedLlm llm = new ScriptedLlm(SKILL_JSON);
        llm.fail = true;

        SkillDiscoverReport r = discoverer(llm, repo).runOnce(AGENT);

        assertEquals(1, r.failed());
        assertEquals(List.of("FAILED:SYSTEM"), repo.events);
    }

    @Test
    @DisplayName("记事件抛异常：不影响提炼结果（历史是旁路，不是主流程的一环）")
    void 记事件失败不影响提炼() {
        EventRepo repo = repo();
        repo.eventFails = true;

        SkillDiscoverReport r = discoverer(new ScriptedLlm(SKILL_JSON), repo).runOnce(AGENT);

        assertEquals(1, r.offered(), "记不上历史不该掀翻提炼");
        assertEquals(0, r.failed());
        assertNotNull(repo.offered);
    }
}