package io.memobservatory.exoskeleton.tasks;

/**
 * 一次 LLM 调用的用量读数（取自 OpenAI 兼容响应体的 {@code usage} 字段）。
 *
 * <p><b>为什么单独建一个模型</b>：token 数是「成本」这一维的唯一可数事实，得从响应体里带出来，
 * 而不是靠「调了几次」去猜——不同类的摘要长度差几十倍，按次数计价会失真。
 *
 * <p>端点没上报 {@code usage} 时如实为 null（见 {@link LlmUsageSink}），<b>不造 0</b>：
 * 0 是「这次没用 token」，null 是「不知道用了多少」，两者混同会让计量变成假账。
 */
public record LlmUsage(int promptTokens, int completionTokens, int totalTokens) {
}