package io.memobservatory.server.model;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆操作事件（对应数据库 memory_events 表，对应 OTLP span）。
 *
 * 字段来源：OTLP span 的 memory.* 属性，由 OtlpParser 解析填充。
 * 归一化操作取自 memory.op_normalized（Python 侧已归一），缺失时回退到
 * 解析 memory.operation（add/update/compact/retrieve/delete）。
 */
public record MemoryEvent(
        String eventId,         // spanId 作为主键（16 字符 hex，Trace 契约 §6.1.1 对齐）
        String agentId,
        String sessionId,
        MemoryOp operation,
        String layer,
        String memoryKey,
        String memorySummary,   // 前 200 字预览，不含原始数据（PII 脱敏前移）
        int tokenCount,
        double latencyMs,
        Instant timestamp,
        Map<String, String> metadata,
        // Trace 字段（input-contracts §6）
        String traceId,         // 32 hex chars；一个 session = 一个 trace；NULL = 孤立项
        String parentSpanId     // 16 hex chars；NULL = Turn Root Span 或 无父的孤立 span
) {
    /** 旧构造兼容：无 Trace 字段时两者置 NULL。 */
    public MemoryEvent(String eventId, String agentId, String sessionId, MemoryOp operation,
                       String layer, String memoryKey, String memorySummary,
                       int tokenCount, double latencyMs, Instant timestamp,
                       Map<String, String> metadata) {
        this(eventId, agentId, sessionId, operation, layer, memoryKey, memorySummary,
             tokenCount, latencyMs, timestamp, metadata, null, null);
    }

    /** 链式构建入口：字段超过三个，统一用 DTO/Builder 封装，避免长参数构造。 */
    public static Builder builder() {
        return new Builder();
    }

    /** MemoryEvent 构建器（含 Trace 字段）。 */
    public static final class Builder {
        private String eventId;
        private String agentId;
        private String sessionId;
        private MemoryOp operation;
        private String layer;
        private String memoryKey;
        private String memorySummary;
        private int tokenCount;
        private double latencyMs;
        private Instant timestamp;
        private Map<String, String> metadata = Map.of();
        private String traceId;
        private String parentSpanId;

        private Builder() {
        }

        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder agentId(String v) { this.agentId = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder operation(MemoryOp v) { this.operation = v; return this; }
        public Builder operation(String v) { this.operation = v == null ? null : MemoryOp.of(v); return this; }
        public Builder layer(String v) { this.layer = v; return this; }
        public Builder memoryKey(String v) { this.memoryKey = v; return this; }
        public Builder memorySummary(String v) { this.memorySummary = v; return this; }
        public Builder tokenCount(int v) { this.tokenCount = v; return this; }
        public Builder latencyMs(double v) { this.latencyMs = v; return this; }
        public Builder timestamp(Instant v) { this.timestamp = v; return this; }
        public Builder metadata(Map<String, String> v) { this.metadata = v; return this; }
        public Builder traceId(String v) { this.traceId = v; return this; }
        public Builder parentSpanId(String v) { this.parentSpanId = v; return this; }

        public MemoryEvent build() {
            return new MemoryEvent(eventId, agentId, sessionId, operation, layer, memoryKey,
                    memorySummary, tokenCount, latencyMs, timestamp, metadata, traceId, parentSpanId);
        }
    }
}
