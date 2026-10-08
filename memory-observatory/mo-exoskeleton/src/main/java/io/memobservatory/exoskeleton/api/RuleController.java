package io.memobservatory.exoskeleton.api;

import io.memobservatory.exoskeleton.criteria.CriteriaService;
import io.memobservatory.exoskeleton.drafter.RuleDraftService;
import io.memobservatory.exoskeleton.model.RulesReport;
import io.memobservatory.exoskeleton.storage.RuleRepository;
import io.memobservatory.exoskeleton.tasks.TaskType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「当前情况」的只读 REST 接口（对应设计文档 §D2 接口二），外加两个手动入口（§4.5 / 二级筛选）。
 *
 * 本期报八块：规则清单（含版本数与 churn）+ 各簇支撑度 + J1 隐式纠正率 + 对照组交叉指标（§2.5）
 * + 分桶口径 + 生效参数 + 任务类型分布，以及 <b>簇级判据</b>（把判据下放到每个桶，并逐簇标注可得性）。
 * 接口一「上传 log」复用 mo-server 现有的 POST /api/v1/import/logs，不在本模块重造（§D1）。
 */
@RestController
@RequestMapping("/api/v1/exoskeleton/rules")
public class RuleController {

    private final RuleRepository rules;
    private final RuleDraftService drafting;
    private final CriteriaService criteria;

    public RuleController(RuleRepository rules, RuleDraftService drafting, CriteriaService criteria) {
        this.rules = rules;
        this.drafting = drafting;
        this.criteria = criteria;
    }

    /**
     * 当前情况（规则现状 + 支撑度分布 + J1 + 对照组 + 任务类型分布 + 簇级判据）。
     *
     * @param taskType 二级筛选（可选）：按任务类型收窄 support / correctionRate / holdout 三块；
     *                 不传时三块的口径与加筛选前逐字一致。taskTypes 与 <b>clusters</b> 两块<b>始终全量</b>——
     *                 前者是筛选项的全貌，后者是分簇键上的判据（A13：task_type 不作分簇键）。
     *                 桶键与支撑度闸门口径不因筛选改变。
     */
    @GetMapping
    public RulesReport rules(@RequestParam(required = false) String taskType) {
        CriteriaService.CriteriaView view = criteria.view(taskType);
        return new RulesReport(
                rules.rules(),
                rules.support(taskType),
                view.correctionRate(),
                view.holdout(),
                rules.bucketing(),
                rules.config(),
                taskTypes(taskType),
                criteria.clusters());
    }

    /** 任务类型分布 + 口径说明。过滤只影响「本次视图」的注记，不影响本块取数。 */
    private RulesReport.TaskTypes taskTypes(String taskType) {
        String f = taskType == null ? "" : taskType.strip();
        String note = f.isEmpty()
                ? "全量视图。task_type 为 session 粒度、由 LLM 读会话内用户提问判定，只作筛选维度、"
                        + "不作分簇键；加 ?taskType=<取值> 可收窄 support / correctionRate / holdout 三块。"
                : "本次已按 taskType=" + f + " 过滤：support / correctionRate / holdout 三块只统计该类；"
                        + "本块仍为全量，以便继续切换。";
        return new RulesReport.TaskTypes(rules.taskTypeDistribution(), TaskType.vocabularyNote(), note);
    }

    /**
     * 起草：给「支撑度达标但还没有规则」的簇各写一条 SHADOW 草稿（§4.5）。
     * 手动触发、可重复调用（幂等）；写入的规则不参与注入，上线仍待闸门。
     */
    @PostMapping("/draft")
    public RulesReport.DraftResult draft() {
        return drafting.draftMissing();
    }
}
