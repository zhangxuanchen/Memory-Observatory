package io.memobservatory.exoskeleton.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.tasks.LlmChatClient;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import io.memobservatory.exoskeleton.tasks.LlmUsageSink;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提炼思考历程（{@code reasoning}）：把「技能凭什么被提炼出来」摊给用户核对。
 *
 * <p>三条要验的事：
 * <ol>
 *   <li>模型给了 reasoning → 解析出自述四项，并拼上确定性证据（依据的会话、会话代表原话）；</li>
 *   <li>旧模型没给 reasoning → 不报错、不硬塞：自述四项为空，仅留确定性证据（<b>向后兼容</b>）；</li>
 *   <li>reasoning 的 JSON 往返一致——库里那一列就是这份 JSON，Jackson 认得出记录（否则入口会静默消失）。</li>
 * </ol>
 *
 * <p>不用 Mockito（本机 JDK 26 上 Byte Buddy 不支持），沿用本模块既有的手写替身范式。
 */
class SkillReasoningTest {

    private static final String AGENT = "agent-x";

    private static final String WITH_REASON = """
            {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
             "prompt": "1. 通读\\n2. 改写\\n禁止：跳过通读直接改写", "tools": ["readFile"],
             "reasoning": {"patterns": ["先通读再改写", "改动集中在术语"],
              "preferences": ["偏好保留原结构"],
              "keyPoints": ["每步都有验收"],
              "evidence": "从三个会话的反复做法归纳"}}}
            """;
    /** 旧口径：技能里没有 reasoning 字段。 */
    private static final String NO_REASON = """
            {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
             "prompt": "1. 通读\\n2. 改写\\n禁止：跳过通读直接改写", "tools": ["readFile"]}}
            """;

    /** LLM 替身：按脚本回一句话。 */
    private static class ScriptedLlm extends LlmChatClient {
        private final String reply;

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
        public String chat(String system, String user, LlmUsageSink sink) {
            if (sink != null) {
                sink.accept(true, new io.memobservatory.exoskeleton.tasks.LlmUsage(10, 1, 11));
            }
            return reply;
        }
    }

    /** 取数 / 落库替身：捕获落库的提议，并给出可控的「依据会话」与「类原文」。 */
    private static class CapRepo extends SkillProposalRepository {
        volatile SkillProposal offered = null;
        /** 让「读依据会话」抛异常，验旁路：读不到不得掀翻提炼。 */
        volatile boolean sessionsFail = false;
        /** 该类时间跨度（硬统计用）；默认 null = 读不到。 */
        SkillProposalRepository.Span span = null;
        /** 让「读时间跨度」抛异常，验旁路。 */
        volatile boolean spanFail = false;
        private final List<String> sessions;
        /** 该类原文——要验抽样 / 截断时换掉它（默认 30 条，够样本门槛）。 */
        List<String> texts = turns();

        CapRepo(List<String> sessions) {
            super((JdbcTemplate) null);
            this.sessions = sessions;
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
        public List<String> classSessions(String agentId, String taskType) {
            if (sessionsFail) {
                throw new RuntimeException("sessions store down");
            }
            return sessions;
        }

        @Override
        public SkillProposalRepository.Span classSpan(String agentId, String taskType) {
            if (spanFail) {
                throw new RuntimeException("span store down");
            }
            return span;
        }

        @Override
        public SkillProposal find(String proposalId) {
            return null;
        }

        @Override
        public ClassRun findClassRun(String agentId, String taskType) {
            return null;
        }

        @Override
        public void recordClassRun(String agentId, String taskType, String digestHash,
                                   String outcome, long turns, String reason) {
            // 记账是旁路，本测不看
        }

        @Override
        public SkillProposal store(SkillProposal p) {
            this.offered = p;
            return p;
        }

        @Override
        public void recordEvent(String kind, String actor, String proposalId, String agentId,
                                String taskType, Integer version, String digestHash, String detail) {
            // 事件流水是旁路，本测不看
        }
    }

    private static List<String> turns() {
        List<String> xs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            xs.add("第 " + i + " 稿：请保留原结构，先通读再改写。");
        }
        return xs;
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
    @DisplayName("模型给了 reasoning：解析出自述四项，并拼上确定性证据（依据会话 + 代表原话）")
    void 解析模型自述并拼确定性证据() {
        CapRepo repo = new CapRepo(List.of("s1", "s2", "s3"));

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        assertNotNull(repo.offered, "应落一条提议");
        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r);
        assertEquals(List.of("先通读再改写", "改动集中在术语"), r.patterns(), "模型自述：规律");
        assertEquals(List.of("偏好保留原结构"), r.preferences(), "模型自述：用户偏好");
        assertEquals(List.of("每步都有验收"), r.keyPoints(), "模型自述：关键判断点");
        assertEquals("从三个会话的反复做法归纳", r.evidence(), "模型自述：依据");
        assertEquals(List.of("s1", "s2", "s3"), r.sessions(), "确定性证据：依据的会话");
        assertEquals(3, r.samples().size(), "确定性证据：代表原话抽 3 条");
        assertFalse(r.isEmpty());
    }

    @Test
    @DisplayName("旧模型没给 reasoning：不报错、不硬塞——自述四项为空，仅留确定性证据（向后兼容）")
    void 无reasoning字段向后兼容() {
        CapRepo repo = new CapRepo(List.of("s1"));

        discoverer(new ScriptedLlm(NO_REASON), repo).runOnce(AGENT);

        assertNotNull(repo.offered, "没给 reasoning 也要能正常提炼");
        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r, "仍有确定性证据，故不为 null");
        assertTrue(r.patterns().isEmpty());
        assertTrue(r.preferences().isEmpty());
        assertTrue(r.keyPoints().isEmpty());
        assertTrue(r.evidence().isEmpty());
        assertEquals(List.of("s1"), r.sessions());
    }

    @Test
    @DisplayName("依据会话超过上限：截断到 12 条（卡片别被撑爆）")
    void 依据会话超上限则截断() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("s" + i);
        }
        CapRepo repo = new CapRepo(many);

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        assertEquals(12, repo.offered.reasoning().sessions().size());
        assertEquals("s0", repo.offered.reasoning().sessions().get(0), "保留前 N 条");
    }

    @Test
    @DisplayName("reasoning 的 JSON 往返一致：库里那一列就是这份 JSON，Jackson 认得出记录")
    void json往返一致() throws Exception {
        ObjectMapper m = new ObjectMapper();
        SkillProposal.Reasoning r = new SkillProposal.Reasoning(
                List.of("规律"), List.of("偏好"), List.of("关键"), "依据",
                List.of("s1", "s2"), List.of("原话"),
                "这批日志在改稿", List.of("5 个会话", "828 条对话"));

        String json = m.writeValueAsString(r);
        SkillProposal.Reasoning back = m.readValue(json, SkillProposal.Reasoning.class);

        assertEquals(r, back, "记录按值相等：读回来与写出去的是同一份");
    }

    @Test
    @DisplayName("代表原话不足抽样上限：有几条抽几条，不硬凑（2 条 → 2 条，首尾都在）")
    void 代表原话不足上限则不硬凑() {
        CapRepo repo = new CapRepo(List.of());
        repo.texts = List.of("第一条：先通读", "第二条：再改写");

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        List<String> s = repo.offered.reasoning().samples();
        assertEquals(2, s.size(), "只有 2 条原文就抽 2 条，不凑第 3 条");
        assertTrue(s.contains("第一条：先通读"), "首条必取");
        assertTrue(s.contains("第二条：再改写"), "末条必取");
    }

    @Test
    @DisplayName("代表原话超长：留头留尾裁到 120 字（单条别撑爆卡片）")
    void 代表原话超长则留头留尾() {
        String long180 = "头".repeat(90) + "尾".repeat(90);   // 180 字，超过上限 120
        CapRepo repo = new CapRepo(List.of());
        repo.texts = List.of(long180);

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        List<String> s = repo.offered.reasoning().samples();
        assertEquals(1, s.size());
        String q = s.get(0);
        assertEquals(120, q.length(), "裁到展示上限 120 字");
        assertTrue(q.contains("…"), "留头留尾：中间以省略号相接");
        assertTrue(q.startsWith("头"), "保住头部");
        assertTrue(q.endsWith("尾"), "保住尾部");
    }

    @Test
    @DisplayName("代表原话均匀抽、首尾必取：5 条里抽到第 0 与第 4 条（而不是只取前 3 条）")
    void 代表原话首尾必取() {
        CapRepo repo = new CapRepo(List.of());
        repo.texts = List.of("t0", "t1", "t2", "t3", "t4");

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        List<String> s = repo.offered.reasoning().samples();
        assertEquals(3, s.size());
        assertTrue(s.contains("t0"), "首条必取");
        assertTrue(s.contains("t4"), "末条必取——不是只取前 3 条");
    }

    @Test
    @DisplayName("读依据会话失败：旁路——读不到不掀翻提炼，心路仍留模型自述那一半")
    void 读依据会话失败不掀翻提炼() {
        CapRepo repo = new CapRepo(List.of("s1"));
        repo.sessionsFail = true;

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        assertNotNull(repo.offered, "读不到会话不该让提炼失败");
        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r, "模型自述仍在，心路仍可展示");
        assertEquals(List.of("先通读再改写", "改动集中在术语"), r.patterns(), "自述四项不受影响");
        assertTrue(r.sessions().isEmpty(), "读不到就留空，不瞎编会话");
    }

    @Test
    @DisplayName("模型把 reasoning 给成非对象：自述判空、证据照拼，不报错（脏输出容错）")
    void 模型自述类型异常则判空() {
        String bad = """
                {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
                 "prompt": "1. 通读\\n2. 改写\\n禁止：跳过通读直接改写", "tools": ["readFile"],
                 "reasoning": "这里本该是对象，模型却给了一句话"}}
                """;
        CapRepo repo = new CapRepo(List.of("s1", "s2"));

        discoverer(new ScriptedLlm(bad), repo).runOnce(AGENT);

        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r, "确定性证据还在，故心路不为 null");
        assertTrue(r.patterns().isEmpty(), "非对象 → 自述判空，不硬塞");
        assertTrue(r.evidence().isEmpty());
        assertEquals(List.of("s1", "s2"), r.sessions(), "确定性证据照拼");
    }

    @Test
    @DisplayName("模型没给自述、也没抽到会话与原话：日志硬统计仍在 → 心路不为 null，入口照显示")
    void 硬统计仍在则仍显示入口() {
        CapRepo repo = new CapRepo(List.of());
        repo.texts = List.of("   ");   // 空白原文：抽不出代表原话，也读不到会话

        discoverer(new ScriptedLlm(NO_REASON), repo).runOnce(AGENT);

        assertNotNull(repo.offered, "仍照常提炼");
        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r, "该类有对话 → 硬统计在，入口就该给（先总结日志，再给结论）");
        assertTrue(r.logFacts().contains("828 条对话"), r.logFacts().toString());
        assertTrue(r.logSummary().isEmpty(), "模型没给总结就是空，不硬塞");
    }

    @Test
    @DisplayName("日志总结分列：模型 summary 与系统硬统计（会话数 / 条数 / 时间跨度）各归各")
    void 日志总结模型与硬统计分列() {
        String withLog = """
                {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
                 "prompt": "1. 通读\\n2. 改写\\n禁止：跳过通读直接改写", "tools": ["readFile"],
                 "reasoning": {"logSummary": "近期在反复打磨书稿知识点，反复要求讲清论证来源。",
                  "patterns": ["先通读再改写"]}}}
                """;
        CapRepo repo = new CapRepo(List.of("s1", "s2"));
        repo.span = new SkillProposalRepository.Span("2026-08-10", "2026-09-18");

        discoverer(new ScriptedLlm(withLog), repo).runOnce(AGENT);

        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertEquals("近期在反复打磨书稿知识点，反复要求讲清论证来源。", r.logSummary(), "模型口径：日志总结");
        assertTrue(r.logFacts().contains("2 个会话"), r.logFacts().toString());
        assertTrue(r.logFacts().contains("828 条对话"), r.logFacts().toString());
        assertTrue(r.logFacts().contains("08-10 → 09-18"), r.logFacts().toString());
    }

    @Test
    @DisplayName("硬统计用「总会话数」，不是截断后的 12 条：20 个会话 → 仍报 20")
    void 硬统计用总会话数() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("s" + i);
        }
        CapRepo repo = new CapRepo(many);

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        assertEquals(12, repo.offered.reasoning().sessions().size(), "证据列表仍截断到 12");
        assertTrue(repo.offered.reasoning().logFacts().contains("20 个会话"),
                repo.offered.reasoning().logFacts().toString());
    }

    @Test
    @DisplayName("读时间跨度失败：旁路——少一项硬统计，但不掀翻提炼、入口照显示")
    void 读时间跨度失败不掀翻提炼() {
        CapRepo repo = new CapRepo(List.of("s1"));
        repo.spanFail = true;

        discoverer(new ScriptedLlm(WITH_REASON), repo).runOnce(AGENT);

        assertNotNull(repo.offered, "读不到跨度不该让提炼失败");
        SkillProposal.Reasoning r = repo.offered.reasoning();
        assertNotNull(r);
        assertTrue(r.logFacts().contains("1 个会话"), "其余硬统计照旧：" + r.logFacts());
        assertTrue(r.logFacts().stream().noneMatch(x -> x.contains("→")), "读不到就别编跨度：" + r.logFacts());
        assertEquals(List.of("先通读再改写", "改动集中在术语"), r.patterns(), "心路其余部分不受影响");
    }

    @Test
    @DisplayName("isEmpty 是入口的闸门：全空 → 不显示；日志总结 / 自述 / 证据任一非空 → 显示")
    void isEmpty决定是否显示入口() {
        List<String> none = List.of();
        // 全空：日志总结 + 自述四项 + 证据两项都空 → 卡片不显示入口
        assertTrue(new SkillProposal.Reasoning(none, none, none, "", none, none, "", none).isEmpty());
        // 依据只给了空白串：仍算空（isBlank 判定），不显示
        assertTrue(new SkillProposal.Reasoning(none, none, none, "   ", none, none, "", none).isEmpty());
        // 日志总结只给了空白串：也算空，不显示
        assertTrue(new SkillProposal.Reasoning(none, none, none, "", none, none, "   ", none).isEmpty());
        // 只要有代表原话一条，就该显示（证据可核对，值得给入口）
        assertFalse(new SkillProposal.Reasoning(none, none, none, "", none, List.of("原话"), "", none).isEmpty());
        // 只要有依据会话一条，就该显示
        assertFalse(new SkillProposal.Reasoning(none, none, none, "", List.of("s1"), none, "", none).isEmpty());
        // 自述只有非空的依据一句话，也该显示
        assertFalse(new SkillProposal.Reasoning(none, none, none, "从会话归纳", none, none, "", none).isEmpty());
        // 只给了「日志总结」这一块（模型总结或硬统计）→ 也该显示：这正是「先总结日志」的入口
        assertFalse(new SkillProposal.Reasoning(none, none, none, "", none, none, "这批日志在改稿", none).isEmpty());
        assertFalse(new SkillProposal.Reasoning(none, none, none, "", none, none, "", List.of("828 条对话")).isEmpty());
    }

    // ------------------------------------------------------------------
    // 改造 3 / 改造 4：产物中立红灯 + 三维 rubric 机器校验（2026-10-11）
    // 借 darwin-skill 的「Runtime 适配性审查」与 SkillLens 三维 rubric，落成 SkillDiscoverer 的产出闸门。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("改造 3：技能写死绝对路径（绑定单一机器）→ 判不合格、不产出提议")
    void 绑定单一运行环境则不合格() {
        String bound = """
                {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
                 "prompt": "1. 读 /Users/zxc/notes\\n2. 改写\\n禁止：跳过通读", "tools": ["readFile"]}}
                """;
        CapRepo repo = new CapRepo(List.of("s1"));

        discoverer(new ScriptedLlm(bound), repo).runOnce(AGENT);

        assertNull(repo.offered, "绑定单一运行环境的技能不该落提议");
    }

    @Test
    @DisplayName("改造 4·dim2：技能含软化措辞（视情况）→ 判不合格、不产出提议")
    void 含软化措辞则不合格() {
        String soft = """
                {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
                 "prompt": "1. 通读\\n2. 视情况改写\\n禁止：跳过通读", "tools": ["readFile"]}}
                """;
        CapRepo repo = new CapRepo(List.of("s1"));

        discoverer(new ScriptedLlm(soft), repo).runOnce(AGENT);

        assertNull(repo.offered, "含软化措辞的技能不该落提议");
    }

    @Test
    @DisplayName("改造 4·dim3：技能缺「禁止/反例」清单 → 判不合格、不产出提议")
    void 缺反例清单则不合格() {
        String noBlacklist = """
                {"skill": {"id": "doc-refine", "name": "文档精修", "description": "改稿",
                 "prompt": "1. 通读\\n2. 改写", "tools": ["readFile"]}}
                """;
        CapRepo repo = new CapRepo(List.of("s1"));

        discoverer(new ScriptedLlm(noBlacklist), repo).runOnce(AGENT);

        assertNull(repo.offered, "缺反例清单（dim3）不该落提议");
    }
}