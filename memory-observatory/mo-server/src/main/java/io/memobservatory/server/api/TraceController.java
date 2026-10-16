package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.api.dto.EventQueryDTO;
import io.memobservatory.server.api.dto.SessionsQueryDTO;
import io.memobservatory.server.api.dto.SkillAnalysisQueryDTO;
import io.memobservatory.server.api.dto.TokenAnalyticsQueryDTO;
import io.memobservatory.server.api.dto.TokenHeatmapQueryDTO;
import io.memobservatory.server.api.dto.TokenStatsQueryDTO;
import io.memobservatory.server.api.dto.TraceListQueryDTO;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.storage.EventRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/v1")
public class TraceController {

@Autowired
    private EventRepository repo;

    /** 拉取单个 Trace 的完整详情：flat spans + 预构建树 + statistics。 */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        List<Map<String, Object>> spans = repo.queryTraceSpans(traceId);
        if (spans.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "trace not found",
                    "traceId", traceId));
        }
        // 树 + 统计
        List<Map<String, Object>> tree = buildTraceTree(spans);
        Map<String, Object> stats = buildTraceStatistics(spans, tree);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("traceId", traceId);
        // 取第一个 span 的 agentId / sessionId 作为 trace 归属
        result.put("agentId", spans.get(0).get("agentId"));
        result.put("sessionId", spans.get(0).get("sessionId"));
        result.put("spans", spans);
        result.put("tree", tree);
        result.put("statistics", stats);
        return ResponseEntity.ok(result);
    }

    /** 从事件反查 Trace：等价先查 event.trace_id 再返回完整 Trace（方便事件流详情跳转）。 */
    @GetMapping("/events/{eventId}/trace")
    public ResponseEntity<Map<String, Object>> getTraceByEvent(@PathVariable String eventId) {
        String traceId = repo.findTraceIdByEventId(eventId);
        if (traceId == null || traceId.isBlank()) {
            return ResponseEntity.status(404).body(Map.of("error", "event has no trace_id",
                    "eventId", eventId));
        }
        return getTrace(traceId);
    }

    /** Agent 维度 Trace 列表（按 last_ts DESC 分页）。支持 ?sessionId=xxx 过滤。 */
    @GetMapping("/agents/{agentId}/traces")
    public Map<String, Object> listAgentTraces(
            TraceListQueryDTO query) {
        int safeLimit = Math.min(Math.max(query.getLimit(), 1), 200);
        int safeOffset = Math.max(query.getOffset(), 0);
        List<Map<String, Object>> traces = repo.queryAgentTraces(query.getAgentId(), query.getSessionId(),
                safeLimit, safeOffset);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("agentId", query.getAgentId());
        if (query.getSessionId() != null) result.put("sessionId", query.getSessionId());
        result.put("limit", safeLimit);
        result.put("offset", safeOffset);
        result.put("traces", traces);
        return result;
    }

    // —— Trace 辅助：flat spans → children 树 ——

    /**
     * 根据 parentSpanId 在一次遍历中构建树。
     * 孤儿 span（parent 不在集合内或 parentSpanId == null 且 parent 不存在）提升为根。
     */
    private static List<Map<String, Object>> buildTraceTree(List<Map<String, Object>> spans) {
        // spanId → node（children 递归）
        java.util.Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> s : spans) {
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("span", s);
            node.put("children", new java.util.ArrayList<Map<String, Object>>());
            byId.put((String) s.get("spanId"), node);
        }
        // 挂子
        java.util.List<Map<String, Object>> roots = new java.util.ArrayList<>();
        for (Map<String, Object> s : spans) {
            String pid = (String) s.get("parentSpanId");
            Map<String, Object> self = byId.get(s.get("spanId"));
            if (pid != null && byId.containsKey(pid)) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> siblings =
                        (java.util.List<Map<String, Object>>) byId.get(pid).get("children");
                siblings.add(self);
            } else {
                // 根节点：Turn Root Span 或 孤儿
                roots.add(self);
            }
        }
        return roots;
    }

    /** Trace statistics：spans 总数、turns、critical path、失败、慢 span、记忆有效性估算。 */
    private static Map<String, Object> buildTraceStatistics(
            List<Map<String, Object>> spans, List<Map<String, Object>> tree) {
        long total = spans.size();
        long turns = spans.stream()
                .map(s -> (Map<String, String>) s.getOrDefault("metadata", java.util.Collections.emptyMap()))
                .map(m -> m.get("turn_message_id"))
                .filter(java.util.Objects::nonNull)
                .distinct().count();
        if (turns == 0) {
            // 没 turn_message_id 时按 session_id 兜底为 1 个 turn
            turns = 1;
        }
        // 关键路径：树中从根到叶子的最长 duration 路径
        double criticalMs = criticalPath(tree);

        long failed = 0;
        long orphan = 0;
        long reads = 0, writes = 0;
        long itemsReturned = 0;
        java.util.List<Map<String, Object>> slowList = new java.util.ArrayList<>();

        for (Map<String, Object> s : spans) {
            Map<String, String> meta = (Map<String, String>) s.getOrDefault("metadata",
                    java.util.Collections.emptyMap());
            String status = meta.get("status");
            String error  = meta.get("error");
            String orphanFlag = meta.get("trace_orphan");
            if ("failed".equalsIgnoreCase(status) || error != null) failed++;
            if ("true".equals(orphanFlag) || "1".equals(orphanFlag)) orphan++;

            String evt = (String) s.get("eventType");
            if (evt != null) {
                if ("READ".equalsIgnoreCase(evt)) reads++;
                if ("WRITE".equalsIgnoreCase(evt) || "UPDATE".equalsIgnoreCase(evt)) writes++;
            }
            // items_returned（memory.read 常见 metadata 字段）
            String ir = meta.get("items_returned");
            if (ir != null) {
                try { itemsReturned += Long.parseLong(ir); }
                catch (NumberFormatException ignored) {}
            }
            Double dur = (Double) s.get("durationMs");
            if (dur != null && dur > 0) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", s.get("name"));
                row.put("durationMs", dur);
                row.put("status", ("failed".equalsIgnoreCase(status) || error != null) ? "failed" : "ok");
                if (error != null) row.put("error", error);
                slowList.add(row);
            }
        }
        // Top 5 慢 spans（按 duration desc）
        slowList.sort((a, b) -> Double.compare(
                ((Number) b.get("durationMs")).doubleValue(),
                ((Number) a.get("durationMs")).doubleValue()));
        List<Map<String, Object>> topSlow = slowList.subList(0, Math.min(5, slowList.size()));

        // 记忆有效性粗略估算：（读返回条数 - 失败）/ max(读返回条数,1)
        double effectiveness = 0.0;
        String hint = "";
        if (reads + itemsReturned > 0) {
            long inferredUsed = Math.max(0, itemsReturned - failed * 2);
            long base = Math.max(1, itemsReturned);
            effectiveness = Math.min(1.0, (double) inferredUsed / base);
            hint = String.format("检索 %d 条，推断使用 %d 条（%.0f%% 利用率）",
                    itemsReturned, inferredUsed, effectiveness * 100);
        } else {
            hint = "此 Trace 内无 memory.read span";
        }
        Map<String, Object> eff = new java.util.LinkedHashMap<>();
        eff.put("reads", reads);
        eff.put("writes", writes);
        eff.put("itemsReturned", itemsReturned);
        eff.put("retrievalEffectiveness", effectiveness);
        eff.put("hint", hint);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("totalSpans", total);
        result.put("turns", turns);
        result.put("criticalPathMs", criticalMs);
        result.put("failedSpans", failed);
        result.put("orphanSpans", orphan);
        result.put("topSlowSpans", topSlow);
        result.put("memoryEffectiveness", eff);
        return result;
    }

    /** 从根出发递归求关键路径（最长 duration 累加和）。 */
    @SuppressWarnings("unchecked")
    private static double criticalPath(List<Map<String, Object>> nodes) {
        double max = 0;
        for (Map<String, Object> node : nodes) {
            Map<String, Object> span = (Map<String, Object>) node.get("span");
            double d = span.get("durationMs") == null ? 0 : ((Number) span.get("durationMs")).doubleValue();
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            double sub = children == null ? 0 : criticalPath(children);
            if (d + sub > max) max = d + sub;
        }
        return max;
    }
}
