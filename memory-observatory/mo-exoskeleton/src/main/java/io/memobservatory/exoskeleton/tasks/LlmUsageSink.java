package io.memobservatory.exoskeleton.tasks;

/**
 * LLM 用量的回执口：每次 {@code chat} 调完（无论成败）回调一次，供上层按 Agent / 任务类型记账。
 *
 * <p><b>为什么用回调而不是让 {@link LlmChatClient} 直接依赖计量器</b>：客户端刻意零依赖
 * （见其类注释），它不该知道「这次调用属于哪个 Agent / 哪一类任务」——那是<b>调用方的语境</b>。
 * 回调把语境留在调用方，客户端只管如实报出「这次成没成、用了多少 token」。
 */
@FunctionalInterface
public interface LlmUsageSink {

    /**
     * @param ok    true = 拿到了 2xx 响应；false = 失败（超时 / 非 2xx / 解析异常），此时用量无意义
     * @param usage 成功时的用量；端点未上报 {@code usage} 时为 null（如实为空，不造 0）
     */
    void accept(boolean ok, LlmUsage usage);
}