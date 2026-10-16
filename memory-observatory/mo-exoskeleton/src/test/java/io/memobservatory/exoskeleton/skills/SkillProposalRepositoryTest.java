package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.model.SkillVersion;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository.Sibling;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillProposalRepository#store} 的四条规则（已采纳可演进 / 少打扰 / 可重提 / 硬重复去重）。
 *
 * <p><b>怎么在无库处验规则</b>：用零依赖手写替身——子类覆盖读（{@code find / siblings}）与写
 * （{@code upsert / reviseAdopted / supersedeVersionsExcept / insertVersion / recordEvent / mark}）
 * 这些<b>已开成 protected 的落点</b>，把它们换成内存模型，再调真实 {@code store()}。
 * 于是验的是 {@code store()} 自己的分支逻辑，而不是替身。
 *
 * <p>不用 Mockito（本机 JDK 上 Byte Buddy 不可用），沿用本模块既有的手写替身范式。
 */
class SkillProposalRepositoryTest {

    private static final String AGENT = "a";
    private static final String TYPE = "doc_write";
    private static final String PID = "agent:a:task:doc_write";
    private static final String SKILL_ID = "a--doc";

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-01-05T00:00:00Z");

    /**
     * 内存替身：把 {@code store()} 依赖的读写落点全换成 Map / List，直接观察规则触发了什么。
     */
    private static class FakeRepo extends SkillProposalRepository {
        final Map<String, SkillProposal> rows = new LinkedHashMap<>();
        final Map<String, List<Sibling>> siblings = new LinkedHashMap<>();
        /** 记下的落库动作（{@code proposalId@version:status}）。 */
        final List<String> versions = new ArrayList<>();
        /** 记下的事件（{@code KIND:ACTOR:proposalId}）。 */
        final List<String> events = new ArrayList<>();
        /** 记下的状态流转（{@code proposalId=status}）。 */
        final List<String> marked = new ArrayList<>();
        /** {@code supersedeVersionsExcept} 保留的版本号。 */
        final List<Integer> supersededKeep = new ArrayList<>();
        boolean upsertCalled = false;

        FakeRepo(int minNewTurns) {
            super((JdbcTemplate) null, new VersionPolicy(minNewTurns));
        }

        @Override
        public SkillProposal find(String proposalId) {
            return rows.get(proposalId);
        }

        @Override
        public List<Sibling> siblings(String agentId, String skillId, String exceptProposalId) {
            List<Sibling> out = new ArrayList<>();
            for (Sibling s : siblings.getOrDefault(agentId + "/" + skillId, List.of())) {
                if (!s.proposalId().equals(exceptProposalId)) {
                    out.add(s);
                }
            }
            return out;
        }

        @Override
        protected void upsert(SkillProposal p) {
            upsertCalled = true;
            rows.put(p.proposalId(), p);
        }

        /**
         * 模拟 {@code SQL_REVISE_ADOPTED}：只改内容 / turns / version，<b>不碰</b>
         * status / created_at / decided_at；{@code skill_id} 沿用既有（钉死）。
         * {@code pendingVersion} 用新版本号——等价于真实库里那条 {@code pending_version} 子查询。
         */
        @Override
        protected void reviseAdopted(SkillProposal existing, SkillProposal p, int next) {
            rows.put(p.proposalId(), new SkillProposal(p.proposalId(), p.agentId(), p.taskType(),
                    p.taskLabel(), p.turns(), existing.skillId(), p.name(), p.description(), p.prompt(),
                    p.tools(), existing.status(), p.digestHash(), existing.createdAt(), existing.decidedAt(),
                    next, next, p.reasoning()));
        }

        @Override
        protected void supersedeVersionsExcept(String proposalId, int keepVersion) {
            supersededKeep.add(keepVersion);
        }

        @Override
        public void insertVersion(SkillProposal p, int version, String status) {
            versions.add(p.proposalId() + "@" + version + ":" + status);
        }

        @Override
        public void recordEvent(String kind, String actor, String proposalId, String agentId,
                                String taskType, Integer version, String digestHash, String detail) {
            events.add(kind + ":" + actor + ":" + proposalId);
        }

        @Override
        public SkillProposal mark(String proposalId, String status) {
            marked.add(proposalId + "=" + status);
            SkillProposal p = rows.get(proposalId);
            if (p != null) {
                rows.put(proposalId, new SkillProposal(p.proposalId(), p.agentId(), p.taskType(),
                        p.taskLabel(), p.turns(), p.skillId(), p.name(), p.description(), p.prompt(),
                        p.tools(), status, p.digestHash(), p.createdAt(), p.decidedAt(),
                        p.version(), p.pendingVersion(), p.reasoning()));
            }
            return rows.get(proposalId);
        }
    }

    private static SkillProposal proposal(String status, String digest, long turns, int version,
                                          String name, String prompt, List<String> tools) {
        return new SkillProposal(PID, AGENT, TYPE, "文档撰写", turns, SKILL_ID, name, "描述", prompt,
                tools, status, digest, CREATED, SkillProposal.PENDING.equals(status) ? null : DECIDED,
                version, null, null);
    }

    @Test
    @DisplayName("规则一：指纹未变 → 原样返回，不落库、不出新版（少打扰）")
    void 指纹未变则原样返回() {
        FakeRepo repo = new FakeRepo(10);
        SkillProposal existing = proposal(SkillProposal.ADOPTED, "h1", 100, 1,
                "写文档", "先读再写", List.of("readFile"));
        repo.rows.put(PID, existing);

        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h1", 100, 1,
                "写文档", "先读再写", List.of("readFile")));

        assertSame(existing, out, "同一份证据不重复落、不打扰");
        assertFalse(repo.upsertCalled);
        assertTrue(repo.versions.isEmpty());
        assertTrue(repo.events.isEmpty());
    }

    @Test
    @DisplayName("规则二：已采纳 + 内容变 + 证据增长 → 出新版；提议行仍 ADOPTED、不动 created_at、skill_id 钉死")
    void 已采纳技能随证据演进() {
        FakeRepo repo = new FakeRepo(10);
        SkillProposal existing = proposal(SkillProposal.ADOPTED, "h1", 100, 1,
                "写文档", "旧流程", List.of("readFile"));
        repo.rows.put(PID, existing);

        SkillProposal out = repo.store(new SkillProposal(PID, AGENT, TYPE, "文档撰写", 120,
                "a--SOMETHING-ELSE", "写文档", "描述", "新流程", List.of("readFile", "editFile"),
                SkillProposal.PENDING, "h2", null, null, 1, null, null));

        assertEquals(2, out.version(), "证据涨够（100 → 120 ≥ 100+10）：出新版 v2");
        assertEquals(SkillProposal.ADOPTED, out.status(), "提议行仍 ADOPTED，不翻 PENDING");
        assertEquals(SKILL_ID, out.skillId(), "skill_id 钉死：沿用既有，不被新提议改掉");
        assertEquals(CREATED, out.createdAt(), "不动 created_at");
        assertEquals(DECIDED, out.decidedAt(), "不动 decided_at");
        assertEquals("新流程", out.prompt(), "内容已更新为新版");
        assertTrue(out.hasNewVersion(), "卡片据此标「有新版」");
        assertEquals(2, out.pendingVersion());

        assertTrue(repo.versions.contains(PID + "@2:" + SkillVersion.PENDING), repo.versions.toString());
        assertEquals(List.of(2), repo.supersededKeep, "只保留 v2，旧版置 SUPERSEDED");
        assertTrue(repo.events.stream().anyMatch(e -> e.startsWith("REVISE:SYSTEM")), repo.events.toString());
        assertFalse(repo.upsertCalled, "出新版走 reviseAdopted，不整行 upsert（免得重置 created_at / 翻状态）");
    }

    @Test
    @DisplayName("规则二：内容变了但证据增长不足 → 保持原状，不刷版本")
    void 内容变但证据不足则保持原状() {
        FakeRepo repo = new FakeRepo(10);
        SkillProposal existing = proposal(SkillProposal.ADOPTED, "h1", 100, 1,
                "写文档", "旧流程", List.of("readFile"));
        repo.rows.put(PID, existing);

        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h2", 105, 1,
                "写文档", "新流程", List.of("readFile")));

        assertSame(existing, out, "105 < 110：证据没涨够，宁可少打扰");
        assertTrue(repo.versions.isEmpty());
        assertEquals(1, out.version());
    }

    @Test
    @DisplayName("规则二：内容一字未改、仅证据增长 → 保持原状（防措辞漂移刷版本）")
    void 仅证据增长不出新版() {
        FakeRepo repo = new FakeRepo(10);
        SkillProposal existing = proposal(SkillProposal.ADOPTED, "h1", 100, 1,
                "写文档", "先读再写", List.of("readFile"));
        repo.rows.put(PID, existing);

        // 指纹（h2）变了、turns 也大涨，但 name/prompt/tools 全同 → contentHash 相同
        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h2", 500, 1,
                "写文档", "先读再写", List.of("readFile")));

        assertSame(existing, out);
        assertTrue(repo.versions.isEmpty());
    }

    @Test
    @DisplayName("规则三：已决定不用但证据变了 → 覆盖回 PENDING，重新请人看一眼")
    void 已不用但证据变则重提() {
        FakeRepo repo = new FakeRepo(10);
        repo.rows.put(PID, proposal(SkillProposal.DISMISSED, "h1", 100, 1,
                "写文档", "旧流程", List.of("readFile")));

        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h2", 120, 1,
                "写文档", "新流程", List.of("readFile")));

        assertEquals(SkillProposal.PENDING, out.status());
        assertTrue(repo.upsertCalled);
        assertTrue(repo.versions.contains(PID + "@1:" + SkillVersion.PENDING), repo.versions.toString());
    }

    @Test
    @DisplayName("规则四：硬重复且本条证据更少 → 本条作废（置 DISMISSED、不落版本行）")
    void 硬重复本条弱者作废() {
        FakeRepo repo = new FakeRepo(10);
        repo.siblings.put(AGENT + "/" + SKILL_ID,
                List.of(new Sibling("agent:a:task:other", 200, SkillProposal.PENDING)));

        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h1", 100, 1,
                "写文档", "流程", List.of()));

        assertEquals(SkillProposal.DISMISSED, out.status(), "同 Agent 同 skill_id，留证据多的那条");
        assertTrue(repo.events.stream().anyMatch(e -> e.startsWith("DEDUP:SYSTEM:" + PID)),
                repo.events.toString());
        assertTrue(repo.versions.isEmpty(), "作废条不落版本行");
    }

    @Test
    @DisplayName("规则四：硬重复且本条证据更多 → 略弱的兄弟被标不用；已采纳的兄弟不动")
    void 硬重复本条强者留己() {
        FakeRepo repo = new FakeRepo(10);
        String weak = "agent:a:task:weak";
        String adoptedSib = "agent:a:task:adopted-other";
        repo.siblings.put(AGENT + "/" + SKILL_ID, List.of(
                new Sibling(weak, 100, SkillProposal.PENDING),
                new Sibling(adoptedSib, 50, SkillProposal.ADOPTED)));

        SkillProposal out = repo.store(proposal(SkillProposal.PENDING, "h1", 150, 1,
                "写文档", "流程", List.of()));

        assertEquals(SkillProposal.PENDING, out.status(), "证据最多的那条照常提上来");
        assertTrue(repo.versions.contains(PID + "@1:" + SkillVersion.PENDING));
        assertTrue(repo.marked.contains(weak + "=" + SkillProposal.DISMISSED), repo.marked.toString());
        assertFalse(repo.marked.contains(adoptedSib + "=" + SkillProposal.DISMISSED),
                "已采纳的兄弟已经在工作台里，不该被一条新提议掀掉");
    }

    @Test
    @DisplayName("hasNewVersion：有 pending_version 才算「有新版」（卡片「更新」按钮的唯一依据）")
    void 有新版标记() {
        SkillProposal upToDate = new SkillProposal(PID, AGENT, TYPE, "文档撰写", 100, SKILL_ID,
                "写文档", "描述", "流程", List.of(), SkillProposal.ADOPTED, "h", CREATED, DECIDED, 2, null, null);
        SkillProposal hasNew = new SkillProposal(PID, AGENT, TYPE, "文档撰写", 100, SKILL_ID,
                "写文档", "描述", "流程", List.of(), SkillProposal.ADOPTED, "h", CREATED, DECIDED, 2, 2, null);

        assertFalse(upToDate.hasNewVersion());
        assertTrue(hasNew.hasNewVersion());
    }

    @Test
    @DisplayName("心路历程：随 store 落库（新建不透丢）")
    void reasoning随store落库() {
        FakeRepo repo = new FakeRepo(10);
        SkillProposal.Reasoning r = new SkillProposal.Reasoning(List.of("规律"), List.of("偏好"),
                List.of("关键"), "依据", List.of("s1"), List.of("原话"),
                "这批日志在改稿", List.of("5 个会话", "828 条对话"));

        SkillProposal out = repo.store(new SkillProposal(PID, AGENT, TYPE, "文档撰写", 100, SKILL_ID,
                "写文档", "描述", "流程", List.of("readFile"), SkillProposal.PENDING, "h1", null, null,
                1, null, r));

        assertNotNull(out.reasoning());
        assertEquals(List.of("规律"), out.reasoning().patterns());
        assertEquals(List.of("s1"), out.reasoning().sessions());
        assertEquals("这批日志在改稿", out.reasoning().logSummary(), "日志总结（模型口径）随提议落库");
        assertEquals(List.of("5 个会话", "828 条对话"), out.reasoning().logFacts(), "日志总结（系统硬统计）随提议落库");
    }

    @Test
    @DisplayName("心路历程：已采纳出新版时随新内容一并更新")
    void 出新版更新reasoning() {
        FakeRepo repo = new FakeRepo(10);
        repo.rows.put(PID, proposal(SkillProposal.ADOPTED, "h1", 100, 1,
                "写文档", "旧流程", List.of("readFile")));
        SkillProposal.Reasoning r2 = new SkillProposal.Reasoning(List.of("新规律"), List.of(),
                List.of(), "新依据", List.of(), List.of(), "新版日志总结", List.of("6 个会话"));

        SkillProposal out = repo.store(new SkillProposal(PID, AGENT, TYPE, "文档撰写", 120, SKILL_ID,
                "写文档", "描述", "新流程", List.of("readFile"), SkillProposal.PENDING, "h2", null, null,
                1, null, r2));

        assertEquals(2, out.version());
        assertNotNull(out.reasoning(), "出新版时心路历程一并刷新，不残留旧版");
        assertEquals(List.of("新规律"), out.reasoning().patterns());
    }
}