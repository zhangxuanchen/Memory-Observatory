package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.tasks.TaskTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SessionTaskTypeController} 的三种出口（零依赖手写替身）。
 *
 * <p>替身只拦下 {@code writeBack} 并预置返回值，验的是<b>控制流</b>：参数不合法就不该写库、
 * 写不到行就该 404、写到了就该如实回执。这些是「纠正」这条写入路径的正确性要点——
 * 尤其「打错的取值必须 400 而非静默落 other」。
 */
class SessionTaskTypeControllerTest {

    /** 只记录调用的写库替身；{@code result} 预置本次回写的行数。 */
    private static class FakeRepo extends TaskTypeRepository {
        final List<Object[]> calls = new ArrayList<>();
        int result = 0;

        FakeRepo() {
            super((JdbcTemplate) null);
        }

        @Override
        public int writeBack(String sessionId, String taskType, String note) {
            calls.add(new Object[]{sessionId, taskType, note});
            return result;
        }
    }

    @Test
    @DisplayName("空 sessionId：400，且不写库")
    void 空sessionId拒绝() {
        FakeRepo repo = new FakeRepo();
        SessionTaskTypeController c = new SessionTaskTypeController(repo);

        ResponseEntity<Object> r = c.correct("  ", new SessionTaskTypeController.TaskTypeCorrection("refactor", null));

        assertEquals(400, r.getStatusCode().value());
        assertTrue(repo.calls.isEmpty(), "参数不合法就不该碰库");
    }

    @Test
    @DisplayName("未知 taskType：400（刻意不回落 other），且不写库")
    void 未知taskType拒绝不回落other() {
        FakeRepo repo = new FakeRepo();
        SessionTaskTypeController c = new SessionTaskTypeController(repo);

        ResponseEntity<Object> r = c.correct("s1", new SessionTaskTypeController.TaskTypeCorrection("not_a_type", null));

        assertEquals(400, r.getStatusCode().value());
        assertTrue(repo.calls.isEmpty(), "打错的取值不能被静默落成 other");
    }

    @Test
    @DisplayName("合法纠正：200 + 回执带 updated 与中文标签；缺省备注记「人工纠正」")
    void 合法纠正返回回执() {
        FakeRepo repo = new FakeRepo();
        repo.result = 3;
        SessionTaskTypeController c = new SessionTaskTypeController(repo);

        ResponseEntity<Object> r = c.correct("s1", new SessionTaskTypeController.TaskTypeCorrection("refactor", null));

        assertEquals(200, r.getStatusCode().value());
        SessionTaskTypeController.CorrectionResult body = (SessionTaskTypeController.CorrectionResult) r.getBody();
        assertEquals("s1", body.sessionId());
        assertEquals("refactor", body.taskType());
        assertEquals("重构整理", body.label(), "回执应带中文标签供页面直接显示");
        assertEquals(3, body.updated());
        assertEquals("人工纠正", body.note(), "缺省备注应记「人工纠正」，便于日后区分人手改的");
        assertEquals("refactor", repo.calls.get(0)[1]);
    }

    @Test
    @DisplayName("会话不存在（改写 0 行）：404，不谎报成功")
    void 会话不存在回404() {
        FakeRepo repo = new FakeRepo();
        repo.result = 0;
        SessionTaskTypeController c = new SessionTaskTypeController(repo);

        ResponseEntity<Object> r = c.correct("s-missing", new SessionTaskTypeController.TaskTypeCorrection("fix_bug", "x"));

        assertEquals(404, r.getStatusCode().value());
    }
}