package io.memobservatory.exoskeleton.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.exoskeleton.model.SkillEvent;
import io.memobservatory.exoskeleton.model.SkillProposal;
import io.memobservatory.exoskeleton.model.SkillVersion;
import io.memobservatory.exoskeleton.skills.SkillProposalRepository;
import io.memobservatory.exoskeleton.tasks.LlmUsageMeter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SkillProposalController} 本项新增的三个端点：批量不用、版本历史、事件流水。
 *
 * <p><b>为什么不经 HTTP 起 Spring</b>：控制器是普通类，直接注入替身调方法即可——
 * 要验的是「分支与夹取口径」，不是序列化。真实 HTTP 形状由实测覆盖（见方案 p-7）。
 * 用零依赖手写替身，不引 Mockito。
 */
class SkillProposalControllerTest {

    private static final String AGENT = "agent-x";
    private static final String PID = "agent:agent-x:task:doc_write";

    /** 取数 / 落库替身：把 mark 与 limit 记下来，供断言口径。 */
    private static class StubRepo extends SkillProposalRepository {
        final Map<String, SkillProposal> rows = new LinkedHashMap<>();
        final List<Integer> limits = new ArrayList<>();
        final List<String> marked = new ArrayList<>();

        StubRepo() {
            super((JdbcTemplate) null);
        }

        @Override
        public SkillProposal find(String proposalId) {
            return rows.get(proposalId);
        }

        @Override
        public SkillProposal mark(String proposalId, String status) {
            marked.add(proposalId + "=" + status);
            return rows.get(proposalId);
        }

        @Override
        public List<SkillVersion> listVersions(String proposalId) {
            return List.of(new SkillVersion(proposalId, 1, AGENT, "doc_write", "agent-x--doc-refine",
                    "文档精修", "desc", "prompt", List.of("readFile"), "h", SkillVersion.ADOPTED,
                    Instant.parse("2026-01-01T00:00:00Z")));
        }

        @Override
        public List<SkillEvent> recentEvents(String agentId, int limit, String kind) {
            limits.add(limit);
            return List.of();
        }
    }

    private static SkillProposal pending(String id) {
        return new SkillProposal(id, AGENT, "doc_write", "文档撰写", 100, "agent-x--doc-refine",
                "文档精修", "desc", "prompt", List.of(), SkillProposal.PENDING, "h", null, null, 1, null, null);
    }

    private static SkillProposalController controller(StubRepo repo) {
        return new SkillProposalController(null, null, repo, null, new LlmUsageMeter());
    }

    @Test
    @DisplayName("批量不用：ids 为空 → 400（空勾选是调用方用错，与「某条不存在」不同）")
    void 批量不用空勾选回400() {
        SkillProposalController c = controller(new StubRepo());

        assertEquals(400, c.dismissBatch(new SkillProposalController.BatchRequest(List.of()))
                .getStatusCode().value());
        assertEquals(400, c.dismissBatch(new SkillProposalController.BatchRequest(null))
                .getStatusCode().value());
    }

    @Test
    @DisplayName("批量不用：混合（有存在、有不存在）→ 200 且逐条如实")
    void 批量不用逐条独立() throws Exception {
        StubRepo repo = new StubRepo();
        repo.rows.put(PID, pending(PID));

        ResponseEntity<Object> resp = controller(repo)
                .dismissBatch(new SkillProposalController.BatchRequest(List.of(PID, "no-such")));

        assertEquals(200, resp.getStatusCode().value(), "逐条独立：即便有条失败，也是 200");
        JsonNode node = new ObjectMapper().valueToTree(resp.getBody());
        assertEquals(2, node.get("total").asInt());
        assertEquals(1, node.get("succeeded").asInt());
        assertEquals(1, node.get("failed").asInt());
        assertEquals(2, node.get("results").size(), "两条都如实带出");
        assertTrue(repo.marked.contains(PID + "=" + SkillProposal.DISMISSED));
    }

    @Test
    @DisplayName("版本历史：提议不存在 → 404（无提议就无从谈起版本）")
    void 版本历史未知提议回404() {
        assertEquals(404, controller(new StubRepo()).versions("no-such").getStatusCode().value());
    }

    @Test
    @DisplayName("版本历史：提议存在 → 200 + 版本快照")
    void 版本历史已知提议() {
        StubRepo repo = new StubRepo();
        repo.rows.put(PID, pending(PID));

        ResponseEntity<List<SkillVersion>> resp = controller(repo).versions(PID);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(1, resp.getBody().size());
        assertEquals(1, resp.getBody().get(0).version());
    }

    @Test
    @DisplayName("事件流水：limit 越界只夹取（1..500），不报 400")
    void 事件流水limit夹取() {
        StubRepo repo = new StubRepo();
        SkillProposalController c = controller(repo);

        c.events(AGENT, 9999, null);
        c.events(AGENT, -5, null);
        c.events(AGENT, 100, null);

        assertEquals(List.of(500, 1, 100), repo.limits, "上限 500、下限 1、正常值原样透传");
    }
}