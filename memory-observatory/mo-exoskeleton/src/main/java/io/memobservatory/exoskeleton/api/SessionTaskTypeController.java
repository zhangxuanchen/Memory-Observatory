package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.tasks.TaskType;
import io.memobservatory.exoskeleton.tasks.TaskTypeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单会话人工纠正 {@code task_type}（二级筛选维度的纠正入口）。
 *
 * <p><b>为什么需要它</b>：分类是 LLM 判的，会有判错的会话；没有纠正入口，人只能干看着错标签
 * 一直挂在筛选里。这里让人把<b>某一个会话</b>改成对的类型，落回该会话的<b>全部 turn</b>
 * （分类本就是 session 粒度，见 {@link TaskTypeRepository#writeBack}）。
 *
 * <p><b>只改标签，不触发下游重跑</b>：改完只是纠正了筛选维度这一个读数；据此重提炼技能、重出看板，
 * 由人在页面上另行触发（与「自己学、少打扰」一致——不替人做他没点的动作）。故本端点<b>不调用</b>
 * 任何分类器 / 提炼器。
 *
 * <p><b>两种拒绝，各有各的意思</b>：
 * <ul>
 *   <li>{@code taskType} 不在词表内 → <b>400</b>。刻意<b>不回落 other</b>——那是「读模型输出」的宽容口径，
 *       而这里是<b>写</b>，把打错的取值静默落成 other 等于替人做了他没做的决定（见 {@link TaskType#isValidValue}）。</li>
 *   <li>该 session 一行都没改到（库里没有这个会话的 turn）→ <b>404</b>。如实说「没这个会话」，
 *       而不是回一个 {@code updated=0} 让人误以为改成功了。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/sessions")
public class SessionTaskTypeController {

    private final TaskTypeRepository repo;

    public SessionTaskTypeController(TaskTypeRepository repo) {
        this.repo = repo;
    }

    /**
     * 纠正某会话的任务类型。
     *
     * @param sessionId 会话 id（必填）
     * @param request   新类型与备注；备注可省，省了按「人工纠正」记，便于日后查出这是人手改的
     * @return 200 + 纠正回执；参数不合法 400；会话不存在 404
     */
    @PostMapping("/{sessionId}/task-type")
    public ResponseEntity<Object> correct(@PathVariable String sessionId,
                                          @RequestBody(required = false) TaskTypeCorrection request) {
        if (sessionId == null || sessionId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new Fail("未指定 session：纠正以会话为单位，sessionId 不能为空。"));
        }
        String wanted = request == null ? null : request.taskType();
        if (!TaskType.isValidValue(wanted)) {
            return ResponseEntity.badRequest().body(new Fail("taskType 不在词表内：" + wanted
                    + "。合法取值见词表，本次刻意不回落 other。"));
        }
        TaskType type = TaskType.fromOrOther(wanted);
        String note = (request == null || request.note() == null || request.note().isBlank())
                ? "人工纠正" : request.note();
        int updated = repo.writeBack(sessionId, type.value(), note);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new CorrectionResult(sessionId, type.value(), type.label(), updated, note));
    }

    /** 纠正请求体：新类型（词表取值）与可选备注。 */
    public record TaskTypeCorrection(String taskType, String note) {
    }

    /** 纠正回执：{@code updated} 是本会话被改写的 turn 数（分类是 session 粒度，通常为整段会话）。 */
    public record CorrectionResult(String sessionId, String taskType, String label, int updated, String note) {
    }

    private record Fail(String error) {
    }
}