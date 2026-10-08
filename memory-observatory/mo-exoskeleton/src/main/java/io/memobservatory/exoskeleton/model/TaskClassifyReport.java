package io.memobservatory.exoskeleton.model;

import java.util.List;
import java.util.Map;

/**
 * 一次任务类型分类的结果（{@code POST /api/v1/exoskeleton/tasks/classify}）。
 *
 * <p>与 {@link CriteriaReport} 同样只报「这一次做了什么」，不带任何验收判断——
 * 分类质量没有先验保证，本期它<b>不进任何验收判断</b>。
 *
 * <p>{@code samples} 是刻意留的：{@code leak_context} 三例全错的教训说明分类器不能自证，
 * 必须有一条人眼抽检的通道。也正因如此，本报告<b>不返回、库里也不存</b> LLM 自报的置信度
 * （A2 已定案：模型自述不能当标签）。
 */
public record TaskClassifyReport(
        boolean enabled,               // LLM 未配置时为 false，其余字段全 0
        int candidates,                // 本轮「还没分类完」的 session 数
        int classified,                // 成功回写的 session 数
        int failed,                    // LLM 不可达 / 解析失败（保持 NULL，下轮自动重试）
        List<String> agents,           // 本轮成功分类涉及的 Agent（去重；收尾按 Agent 逐个提炼技能）
        Map<String, Long> byTaskType,  // 本次新写入的取值分布
        List<Sample> samples,          // 抽检样本（≤20 条）
        String note                    // 口径说明
) {
    /** 抽检样本：一条 session 的判定结果与人可读的备注。 */
    public record Sample(String sessionId, String taskType, String note) {
    }
}