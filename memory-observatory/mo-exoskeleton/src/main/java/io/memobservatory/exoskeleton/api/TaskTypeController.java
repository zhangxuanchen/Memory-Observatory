package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.model.TaskClassifyReport;
import io.memobservatory.exoskeleton.skills.SkillDiscoverer;
import io.memobservatory.exoskeleton.tasks.TaskClassifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务类型分类入口（二级筛选维度的写入侧）。
 *
 * <p>只做一件事：把「还没分类完」的 session 交给 LLM 判一个 task_type 并回写 {@code memory_turns}。
 * 与其他入口一样刻意<b>不做调度</b>、不注入规则、不参与晋升闸门——分类只产出「一个可筛选的维度」，
 * 能不能别的东西一概不管。
 *
 * <p>幂等：已分类完的 session 不再进候选，可反复调用。
 *
 * <p>{@code force=true} 时无视已有分类全量重判并覆盖——用于判据 / 摘要策略变更后的重标定，
 * 不传则维持增量口径。
 *
 * <p><b>分类收尾接着提炼技能</b>：分类有新结果（{@code classified > 0}）才顺手跑一轮
 * {@link SkillDiscoverer}——满足「自己学、少打扰」里的「自己学」，人只在页面上看到结果。
 * 分类没产出新结果时不空跑（避免白付 LLM 调用）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/tasks")
public class TaskTypeController {

    private final TaskClassifier classifier;
    private final SkillDiscoverer discoverer;

    public TaskTypeController(TaskClassifier classifier, SkillDiscoverer discoverer) {
        this.classifier = classifier;
        this.discoverer = discoverer;
    }

    @PostMapping("/classify")
    public TaskClassifyReport classify(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        TaskClassifyReport report = classifier.runOnce(force);
        if (report.enabled() && report.classified() > 0) {
            // 提炼是「Agent × 任务类型」维度：只对「本轮真正有新分类」的 Agent 各提炼一轮，
            // 不空跑全库——证据没变的类都会在 runOnce 里被整类跳过，代价很小。
            for (String agentId : report.agents()) {
                discoverer.runOnce(agentId);
            }
        }
        return report;
    }
}