package io.memobservatory.exoskeleton.skills;

import io.memobservatory.exoskeleton.model.SkillEffectReport;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository.ContrastTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效果验证的取数 / 分桶（{@link SkillEffectService}）。
 *
 * <p>验三条数据口径：① 一个会话按<b>首 turn</b>归到界前 / 界后（不劈成两半）；
 * ② 只采信 workbench 路径；③ 无采纳时刻 / 无时刻的会话如实剔除，不当 0 算。
 *
 * <p>不用 Mockito（本机 JDK 上 Byte Buddy 不可用），改用手写替身。
 */
class SkillEffectServiceTest {

    private static final Instant DECIDED = Instant.parse("2026-01-10T00:00:00Z");

    /** 取数替身：返回预置 turn；find 返回预置提议。 */
    private static class StubRepo extends SkillProposalRepository {
        final SkillProposal proposal;
        final List<ContrastTurn> turns;

        StubRepo(SkillProposal proposal, List<ContrastTurn> turns) {
            super((JdbcTemplate) null);
            this.proposal = proposal;
            this.turns = turns;
        }

        @Override
        public SkillProposal find(String proposalId) {
            return proposal;
        }

        @Override
        public List<ContrastTurn> classContrastTurns(String agentId, String taskType) {
            return turns;
        }
    }

    private static SkillProposal proposal(Instant decidedAt) {
        return new SkillProposal("agent:a:task:doc_write", "a", "doc_write", "文档撰写",
                0, "a--doc", "写文档", "desc", "prompt", List.of("readFile"),
                decidedAt == null ? SkillProposal.PENDING : SkillProposal.ADOPTED,
                "hash", Instant.parse("2026-01-01T00:00:00Z"), decidedAt, 1, null, null);
    }

    private static ContrastTurn turn(String session, String startedAt, boolean corrected,
                                     String outcome, String source) {
        return new ContrastTurn(session + "-" + startedAt, session,
                startedAt == null ? null : Instant.parse(startedAt), corrected, outcome, source);
    }

    /** 门槛设为 1，使两组都「达标」，好验分桶数值本身。 */
    private static SkillEffectService service(SkillProposal p, List<ContrastTurn> turns) {
        return new SkillEffectService(new StubRepo(p, turns), new SkillEffectGate(1, 0.05, 0.10));
    }

    @Test
    @DisplayName("未采纳（无采纳时刻）：如实报不可测，不去算前后两组")
    void 未采纳则不可测() {
        SkillEffectReport r = service(proposal(null), List.of()).report("agent:a:task:doc_write");

        assertEquals(SkillEffectReport.UNKNOWN, r.verdict());
        assertNull(r.decidedAt());
        assertTrue(r.note().contains("尚未采纳"), r.note());
        assertEquals(0, r.before().turns());
        assertEquals(0, r.after().turns());
    }

    @Test
    @DisplayName("提议不存在：返回 null（端点据此回 404）")
    void 提议不存在返回null() {
        SkillEffectService s = new SkillEffectService(new StubRepo(null, List.of()),
                new SkillEffectGate(1, 0.05, 0.10));
        assertNull(s.report("nope"));
    }

    @Test
    @DisplayName("按会话首 turn 分界：只采信 workbench，无时刻的会话整段剔除")
    void 分桶与采信面() {
        List<ContrastTurn> turns = List.of(
                // 会话 A：界前（首 turn 2026-01-01），2 turn，一条纠正、一条失败
                turn("A", "2026-01-01T00:00:00Z", true, "失败", "workbench"),
                turn("A", "2026-01-02T00:00:00Z", false, "完成", "workbench"),
                // 会话 B：界后（首 turn 2026-01-20），2 turn，无纠正、无失败
                turn("B", "2026-01-20T00:00:00Z", false, "完成", "workbench"),
                turn("B", "2026-01-21T00:00:00Z", false, "完成", "workbench"),
                // 会话 C：导入路径，整段剔除（不是可采信来源）
                turn("C", "2026-01-20T00:00:00Z", true, "失败", "import"),
                // 会话 D：无时刻，整段剔除（归不了组）
                turn("D", null, true, "失败", "workbench"));

        SkillEffectReport r = service(proposal(DECIDED), turns).report("agent:a:task:doc_write");

        assertEquals(1, r.before().sessions());
        assertEquals(2, r.before().turns());
        assertEquals(0.5, r.before().correctedRate(), 1e-9);
        assertEquals(0.5, r.before().failureRate(), 1e-9);
        assertEquals(2.0, r.before().rounds(), 1e-9);

        assertEquals(1, r.after().sessions());
        assertEquals(2, r.after().turns());
        assertEquals(0.0, r.after().correctedRate(), 1e-9);
        assertEquals(0.0, r.after().failureRate(), 1e-9);

        assertEquals(SkillEffectReport.PASS, r.verdict(), "采纳后三项都不劣化，应通过");
    }

    @Test
    @DisplayName("会话跨界不劈半：首 turn 在界前的会话，全部 turn 计入「前」组")
    void 跨界会话整段归前() {
        List<ContrastTurn> turns = List.of(
                turn("A", "2026-01-01T00:00:00Z", false, "完成", "workbench"),
                turn("A", "2026-01-20T00:00:00Z", false, "完成", "workbench"));   // 界后的一轮，仍归「前」

        SkillEffectReport r = service(proposal(DECIDED), turns).report("agent:a:task:doc_write");

        assertEquals(2, r.before().turns(), "首 turn 在界前，整段计入前组");
        assertEquals(0, r.after().turns());
    }
}