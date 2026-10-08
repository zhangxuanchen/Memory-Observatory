package io.memobservatory.exoskeleton.model;

import java.time.Instant;
import java.util.List;

/**
 * 决策卡片的对外视图（§4.10）。
 *
 * <p>三块：卡片清单（含状态与到期）+ 卡数统计（§4.10.6 把「卡片数」当反向指标）
 * + 生成/作答的结果报告。卡片本身是 {@link DecisionCard}（契约），此处只负责包上状态与时间。
 */
public record CardReport(
        List<CardView> cards,
        Stats stats,
        String note
) {
    /**
     * 一张卡的当前状态。
     *
     * <p>{@code status} 只有 {@code OPEN} / {@code ANSWERED} 两态——「到期」不改状态，
     * 由 {@code expiresAt} 与当前时间比较得出（到期只是不再展示，不是替人作决定）。
     */
    public record CardView(
            DecisionCard card,
            String status,        // OPEN | ANSWERED
            Instant createdAt,
            Instant expiresAt,
            Instant answeredAt,
            String answer,        // 已答时的选项 key；未答为 null
            boolean expired       // expiresAt 已过（仅提示，卡片本身不因此消失）
    ) {
    }

    /** 卡数统计（§4.10.6）：开着的 / 已答的 / 已到期的。 */
    public record Stats(int open, int answered, int expired, int cap, String note) {
    }

    /**
     * 一次生成的报告（§4.10.5 流水线）：候选 → 合并 → 截断，各步掉了多少要能对上账。
     *
     * <p>{@code candidates} = 规划器产出的候选总数（截断前）；{@code merged} = 被合并同类消掉的候选数；
     * {@code created} / {@code refreshed} / {@code reissued} = 真正落库的（新卡 / 刷新未答卡 / 重新提出「下周期再报」的卡）；
     * {@code skipped} = 已被人表过态、不再打扰的卡；{@code suppressed} = 因曾被驳回而退出候选的簇数；
     * {@code queued} = 排序截断后排队未出的。
     */
    public record GenerateResult(
            int candidates,
            int merged,
            int created,
            int refreshed,
            int reissued,
            int skipped,
            int suppressed,
            int queued,
            int cap,
            List<String> templates,   // 六个模板的触发情况（含未触发及原因），「六个都建，能触发的才出」
            String note
    ) {
    }

    /**
     * 一次作答的结果。
     *
     * <p>{@code action} 是实际执行的动作，{@code affected} 是它对规则生命周期的影响行数；
     * {@code outcome} 说明结果（含「已记录但本版不改系统状态」这类如实报告）。
     */
    public record AnswerResult(
            String cardId,
            String answer,
            String action,
            String outcome,
            int affected,
            String note
    ) {
    }
}