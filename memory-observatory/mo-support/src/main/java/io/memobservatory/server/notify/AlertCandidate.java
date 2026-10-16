package io.memobservatory.server.notify;

import java.util.Map;

/**
 * 一条待判定 / 待推送的告警候选。
 *
 * <p>{@code dedupKey} 是整套机制的主键：它稳定，同一个问题每轮都映射到同一行；
 * 它漂移，同一个问题每轮都会被当成新问题重推。键的构造规则见 {@link FindingCollector}。
 *
 * @param dedupKey 去重键，落库为 {@code alert_notifications.dedup_key}
 * @param source   来源：{@code threshold} | {@code flow} | {@code risk}
 * @param agentId  归属 agent
 * @param severity 严重度：{@code critical} | {@code high} | {@code medium}
 * @param title    展示标题（取自各源自身的中文标题）
 * @param payload  webhook 请求体内容；风险类只含已脱敏片段，不含原文
 */
public record AlertCandidate(String dedupKey, String source, String agentId,
                             String severity, String title, Map<String, Object> payload) {
}