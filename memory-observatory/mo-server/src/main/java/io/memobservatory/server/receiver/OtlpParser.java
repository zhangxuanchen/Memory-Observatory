package io.memobservatory.server.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemoryOp;
import io.memobservatory.server.model.MemorySnapshot;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * OTLP/HTTP JSON 解析器：从 OTLP traces 请求体提取 memory.* 属性，
 * 重建 MemoryEvent / MemorySnapshot。
 *
 * 属性来源（见 otel_exporter.py）：
 *   - memory.snapshot=true 的 span → 快照
 *   - 其余 span → 事件
 * 操作归一化：优先读 memory.op_normalized（Python 已归一），
 *           缺失时回退解析 memory.operation（add/update/compact 等）。
 */
@Component
public class OtlpParser {

    private final ObjectMapper mapper;

    public OtlpParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParseResult parse(String json) {
        List<MemoryEvent> events = new ArrayList<>();
        List<MemorySnapshot> snapshots = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            for (JsonNode rs : root.path("resourceSpans")) {
                JsonNode resAttrs = rs.path("resource").path("attributes");
                String agentId = attrStr(resAttrs, "agent.id");
                if (agentId == null) agentId = attrStr(resAttrs, "service.name");
                for (JsonNode ss : rs.path("scopeSpans")) {
                    for (JsonNode span : ss.path("spans")) {
                        if (isSnapshot(span)) {
                            snapshots.add(toSnapshot(span, agentId));
                        } else {
                            events.add(toEvent(span, agentId));
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("OTLP 解析失败: " + e.getMessage(), e);
        }
        return new ParseResult(events, snapshots);
    }

    private boolean isSnapshot(JsonNode span) {
        return "true".equals(attrStr(span.path("attributes"), "memory.snapshot"));
    }

    private MemoryEvent toEvent(JsonNode span, String agentId) {
        JsonNode attrs = span.path("attributes");
        String opNorm = attrStr(attrs, "memory.op_normalized");
        String opRaw = attrStr(attrs, "memory.operation");
        MemoryOp op = opNorm != null ? MemoryOp.of(opNorm) : MemoryOp.of(opRaw);
        // token_count 优先，回退 size_delta
        int tokens = attrInt(attrs, "memory.token_count",
                attrInt(attrs, "memory.size_delta", 0));

        // ===== Trace ID / Span ID / Parent Span ID（Trace 契约 §6）=====
        // 1. spanId = event_id（不变式 1：event_id = span_id）
        String spanId = text(span, "spanId", null);
        if (spanId == null || spanId.isBlank()) spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // 2. traceId：先取 OTLP span.traceId（32 hex）；没有则用 sessionId 派生一个稳定 trace
        //    （一个 session = 一个 trace），再没有就随机生成 + 打 trace_orphan=true
        String traceId = text(span, "traceId", null);
        // 3. parentSpanId：OTLP span.parentSpanId
        String parentSpanId = text(span, "parentSpanId", null);

        // 允许 memory.* 属性显式覆盖（MCP 直上报 / 非 OTLP 上报场景）
        String memTrace = attrStr(attrs, "memory.trace_id");
        if (memTrace != null && !memTrace.isBlank()) traceId = memTrace;
        String memParent = attrStr(attrs, "memory.parent_span_id");
        if (memParent != null && !memParent.isBlank()) parentSpanId = memParent;

        // 缺失容错（§6.1.2）：无 trace_id 时生成新的，并在 metadata 标孤立项
        boolean orphan = false;
        String sessionId = nz(attrStr(attrs, "memory.session_id"), "");
        if ((traceId == null || traceId.isBlank()) && !sessionId.isBlank()) {
            // session 派生 trace_id（确定性，避免同 session 产生不同 trace）
            traceId = deterministicTraceId(sessionId);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            orphan = true;
        }

        // 4. metadata 收集 + 额外写入 trace_orphan / turn_index 透传
        Map<String, String> meta = collectMeta(attrs);
        if (orphan) meta.put("trace_orphan", "true");

        return MemoryEvent.builder()
                .eventId(spanId)
                .agentId(nz(attrStr(attrs, "memory.agent_id"), agentId))
                .sessionId(sessionId)
                .operation(op)
                .layer(nz(attrStr(attrs, "memory.layer"), "prompt"))
                .memoryKey(attrStr(attrs, "memory.key"))
                .memorySummary(attrStr(attrs, "memory.summary"))
                .tokenCount(tokens)
                .latencyMs(attrDouble(attrs, "memory.latency_ms", 0.0))
                .timestamp(tsOf(span))
                .metadata(meta)
                .traceId(traceId)
                .parentSpanId((parentSpanId == null || parentSpanId.isBlank()) ? null : parentSpanId)
                .build();
    }

    private MemorySnapshot toSnapshot(JsonNode span, String agentId) {
        JsonNode attrs = span.path("attributes");
        return new MemorySnapshot(
                nz(text(span, "spanId", null), UUID.randomUUID().toString()),
                nz(attrStr(attrs, "memory.agent_id"), agentId),
                nz(attrStr(attrs, "memory.session_id"), ""),
                attrInt(attrs, "memory.total_tokens", 0),
                attrInt(attrs, "memory.system_tokens", 0),
                attrInt(attrs, "memory.task_tokens", 0),
                attrInt(attrs, "memory.memory_tokens", 0),
                attrInt(attrs, "memory.tool_history_tokens", 0),
                attrInt(attrs, "memory.free_tokens", 0),
                attrInt(attrs, "memory.compression_count", 0),
                attrDouble(attrs, "memory.last_compression_ratio", 0.0),
                tsOf(span)
        );
    }

    private Instant tsOf(JsonNode span) {
        String nano = text(span, "startTimeUnixNano", null);
        if (nano != null) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(nano) / 1_000_000L);
            } catch (NumberFormatException ignored) {
                // 回退到当前时间
            }
        }
        return Instant.now();
    }

    // —— OTLP attribute 取值辅助（属性值是联合类型：stringValue/intValue/...）——

    private String attrStr(JsonNode attrs, String key) {
        for (JsonNode a : attrs) {
            if (key.equals(a.path("key").asText())) {
                JsonNode v = a.path("value");
                if (v.has("stringValue")) return v.get("stringValue").asText();
                if (v.has("boolValue")) return String.valueOf(v.get("boolValue").asBoolean());
            }
        }
        return null;
    }

    private int attrInt(JsonNode attrs, String key, int def) {
        for (JsonNode a : attrs) {
            if (key.equals(a.path("key").asText())) {
                JsonNode v = a.path("value");
                if (v.has("intValue")) return v.get("intValue").asInt();
                if (v.has("doubleValue")) return (int) v.get("doubleValue").asDouble();
            }
        }
        return def;
    }

    private double attrDouble(JsonNode attrs, String key, double def) {
        for (JsonNode a : attrs) {
            if (key.equals(a.path("key").asText())) {
                JsonNode v = a.path("value");
                if (v.has("doubleValue")) return v.get("doubleValue").asDouble();
                if (v.has("intValue")) return v.get("intValue").asDouble();
            }
        }
        return def;
    }

    private String text(JsonNode node, String field, String def) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? def : n.asText();
    }

    private String nz(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    private Map<String, String> collectMeta(JsonNode attrs) {
        Map<String, String> meta = new HashMap<>();
        String prefix = "memory.meta.";
        for (JsonNode a : attrs) {
            String k = a.path("key").asText();
            if (k.startsWith(prefix)) {
                JsonNode v = a.path("value");
                String val = null;
                if (v.has("stringValue")) val = v.get("stringValue").asText();
                else if (v.has("boolValue")) val = String.valueOf(v.get("boolValue").asBoolean());
                else if (v.has("intValue")) val = String.valueOf(v.get("intValue").asInt());
                else if (v.has("doubleValue")) val = String.valueOf(v.get("doubleValue").asDouble());
                if (val != null) meta.put(k.substring(prefix.length()), val);
            }
        }
        return meta;
    }

    /** 由 sessionId 生成确定性 trace_id（MD5 前 32 hex，一个 session = 一个 trace）。 */
    private static String deterministicTraceId(String sessionId) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 回退到 UUID
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public record ParseResult(List<MemoryEvent> events, List<MemorySnapshot> snapshots) {
    }
}
