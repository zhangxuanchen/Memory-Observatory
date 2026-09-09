package io.memobservatory.server.receiver;

import io.memobservatory.server.ingest.IngestQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * OTLP/HTTP traces 接收端点。
 *
 * 标准路径 POST /v1/traces（OTLP 默认）。
 * docker-compose 把宿主 4318 映射到容器 8080，兼容 OTel 默认端口约定。
 * 接收后解析并推入 IngestQueue（旁路容错），立即返回 200，不阻塞上报方。
 */
@RestController
public class OtlpReceiver {

    private static final Logger log = LoggerFactory.getLogger(OtlpReceiver.class);

    private final OtlpParser parser;
    private final IngestQueue queue;

    public OtlpReceiver(OtlpParser parser, IngestQueue queue) {
        this.parser = parser;
        this.queue = queue;
    }

    @PostMapping(value = "/v1/traces", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String body) {
        try {
            OtlpParser.ParseResult result = parser.parse(body);
            int n = 0;
            for (Object e : result.events()) { queue.push(e); n++; }
            for (Object s : result.snapshots()) { queue.push(s); n++; }
            log.debug("OTLP 接收 events={} snapshots={}", result.events().size(), result.snapshots().size());
            // OTLP 期望空对象响应
            return ResponseEntity.ok(Map.of());
        } catch (Exception e) {
            log.warn("OTLP 接收失败: {}", e.getMessage());
            // OTLP 规范错误仍返回对象，用 400
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
