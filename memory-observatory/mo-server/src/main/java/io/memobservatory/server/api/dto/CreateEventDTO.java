package io.memobservatory.server.api.dto;

import lombok.Data;

import java.util.Map;

/**
 * 记忆事件上报参数（POST /api/v1/events）。
 *
 * 供其他应用直接 HTTP POST 上报一条记忆事件，与 CLI / OTLP 走同一张
 * memory_events 表。字段对齐 MemoryEvent 模型。
 */
@Data
public class CreateEventDTO {

    /** 可选。缺省生成 16 位 hex 作为 event_id。 */
    private String eventId;
    /** 必填。Agent 标识。 */
    private String agentId;
    /** 会话标识，可选。 */
    private String sessionId;
    /** 可选。read/write/update/expire 等，缺省经 MemoryOp 归一化为 WRITE。 */
    private String operation;
    /** 记忆层，可选，缺省 provider。 */
    private String layer;
    /** 记忆 key，可选。 */
    private String memoryKey;
    /** 摘要预览，可选。 */
    private String memorySummary;
    /** 涉及 token 数，默认 0。 */
    private int tokenCount = 0;
    /** 操作耗时 ms，默认 0。 */
    private double latencyMs = 0.0;
    /** 可选。ISO-8601 时间，缺省当前时间。 */
    private String timestamp;
    /** 可选。Trace ID（32 hex），缺省生成。 */
    private String traceId;
    /** 可选。父 Span ID（16 hex），缺省 null。 */
    private String parentSpanId;
    /** 可选。自定义附加属性。 */
    private Map<String, String> metadata;
}