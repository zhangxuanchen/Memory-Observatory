package io.memobservatory.server.model;

import java.time.Instant;

/**
 * 上下文快照（对应数据库 memory_snapshots 表）。
 *
 * 来源：Python intercept_chat_turn 每轮产生的 ContextSnapshot，
 * 经 OTelSpanExporter 作为独立 span（name=memory.snapshot）上报。
 * 五区 Token 预算：system / task / memory / tool_history / free 合计 100%。
 */
public record MemorySnapshot(
        String snapshotId,
        String agentId,
        String sessionId,
        int totalTokens,
        int systemTokens,
        int taskTokens,
        int memoryTokens,
        int toolHistoryTokens,
        int freeTokens,
        int compressionCount,
        double lastCompressionRatio,
        Instant timestamp
) {
}
