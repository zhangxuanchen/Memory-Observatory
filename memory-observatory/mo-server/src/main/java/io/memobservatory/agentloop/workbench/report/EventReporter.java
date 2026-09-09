/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · report（旁路记忆观测）
 * 【文件】EventReporter.java（io.memobservatory.agentloop.workbench.report）
 * 【核心功能】把 Agent 运行时采样到的事件 POST 到 POST /api/v1/events（与单条导入同表）。
 *            遵循旁路观测容错：上报全程 try-catch，异常仅 WARN，不影响业务线程。
 * 【核心改动】2026-08-23 从 mo-agentloop 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆事件上报器：把 AgentScope 运行时采集到的事件 POST 到服务端
 * {@code POST /api/v1/events}（与单条导入同表）。
 *
 * 遵循"旁路观测容错范式"：上报全程 try-catch，任何异常只记 WARN 不影响业务线程。
 *
 * @param endpoint 服务端地址，如 {@code http://localhost:8080/api/v1/events}
 */
public class EventReporter {

    private static final Logger log = LoggerFactory.getLogger(EventReporter.class);

    private final String endpoint;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 默认超时 5s；服务端批量 flush 与单条走 IMMEDIATE 写库。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public EventReporter(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        this.endpoint = endpoint;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    /**
     * 上报一条事件。字段与 {@code POST /api/v1/events} 的 CreateEventDTO 完全对齐。
     *
     * @param fields 事件字段的字符串值（agentId/sessionId/operation/layer/memoryKey/...）
     * @return 是否成功（异常视为 false）
     */
    public boolean report(Map<String, Object> fields) {
        try {
            String body = mapper.writeValueAsString(fields != null ? fields : Map.of());
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                if (log.isDebugEnabled()) log.debug("event reported: {} -> {}", fields.get("eventId"), resp.statusCode());
                return true;
            }
            log.warn("event report rejected by server, status={}, body={}", resp.statusCode(),
                    truncate(resp.body(), 300));
            return false;
        } catch (HttpTimeoutException e) {
            log.warn("event report timed out to {}, dropped (bounded by design)", endpoint);
            return false;
        } catch (Exception e) {
            log.warn("event report failed to {}: {}", endpoint, e.toString());
            return false;
        }
    }

    /** 构建一个与 CreateEventDTO 对齐的字段表（保证 key 顺序不依赖 map hash）。 */
    public static Map<String, Object> fieldsBuilder() {
        return new LinkedHashMap<>();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}