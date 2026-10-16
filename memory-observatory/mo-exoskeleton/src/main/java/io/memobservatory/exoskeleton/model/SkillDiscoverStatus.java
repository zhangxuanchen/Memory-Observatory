package io.memobservatory.exoskeleton.model;

/**
 * 一次提炼的进度快照（{@code GET /skills/discover/status} 的响应体）。
 *
 * <p><b>为什么进度与上一次报告放在同一个响应里</b>：页面轮询时一次就能知道「还在跑吗、跑到哪了」，
 * 跑完那次也能顺带拿到「上一轮结果如何」——不必再发一次请求。故 {@code lastReport} 就是
 * {@link SkillDiscoverReport}，不另建平行模型。
 *
 * <p><b>缺数据一律 null，绝不造值</b>：从未提炼过的 Agent 没有 {@code startedAt} / {@code totalClasses}，
 * 就如实给 null；只有 {@code running} 与 {@code cancelRequested} 是布尔、恒有值。
 * 进度条目在跑完后<b>保留</b>（见 {@code SkillDiscoverer} 的生命周期注释），故 status 能读到最终进度
 * 与 {@code lastReport}，直到下一次同 Agent 的提炼开始时才重置。
 *
 * @param agentId          查询的 Agent
 * @param running          是否正在跑
 * @param startedAt        本轮开始时刻（ISO8601 字符串；未跑过为 null）
 * @param elapsedMs        本轮已用毫秒（跑完则为本轮总耗时；未跑过为 null）
 * @param tokensUsed       本轮已累计 token（各次成功调用的 total_tokens 之和；未跑过为 null）——
 *                         供人对照 {@code budget-tokens} 看「离预算还有多远」，也是成本面读数
 * @param totalClasses     本轮要处理的类数（过 min-turns 门槛后的真实数量；未跑过为 null）
 * @param doneClasses      本轮已处理的类数（未跑过为 null）
 * @param currentClass     当前正在处理的类（task_type；空闲 / 跑完为 null）
 * @param cancelRequested  是否已请求取消
 * @param lastReport       上一次跑完的报告（从未跑完为 null）
 * @param lastRun          最近一轮的落库运行态（{@code memory_skill_runs}；从未跑过为 null）——
 *                         进程重启后内存态全丢，靠它如实说出「上轮被中断」
 */
public record SkillDiscoverStatus(
        String agentId,
        boolean running,
        String startedAt,
        Long elapsedMs,
        Long tokensUsed,
        Integer totalClasses,
        Integer doneClasses,
        String currentClass,
        boolean cancelRequested,
        SkillDiscoverReport lastReport,
        RunStat lastRun) {

    /**
     * 最近一轮的运行态读数（落库侧）。
     *
     * <p><b>为什么单列到 status 里，而不让前端另调一个端点</b>：页面一次轮询就该同时拿到
     * 「在跑吗 / 跑到哪 / 上一轮是不是被打断了」——分开取会有两处状态、可能各说各话。
     *
     * @param runId        本次运行 id
     * @param status       RUNNING | DONE | CANCELLED | HALTED | INTERRUPTED
     * @param totalClasses 本轮要处理的类数
     * @param doneClasses  已完成类数
     * @param currentClass 当前类（空闲为 null）
     * @param startedAt    开始时刻（ISO8601）
     * @param finishedAt   结束时刻（跑着为 null）
     */
    public record RunStat(String runId, String status, int totalClasses, int doneClasses,
                          String currentClass, String startedAt, String finishedAt) {
    }
}