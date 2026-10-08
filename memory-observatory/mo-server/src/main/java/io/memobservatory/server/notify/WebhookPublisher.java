package io.memobservatory.server.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 通用 webhook 发送器：把一条告警的载荷 POST 到配置的 URL。
 *
 * <p>失败只记 WARN 并返回 false，不抛异常、不阻塞后续条目——旁路容错，
 * 与 {@code ingest/IngestQueue} 的写库失败处理同构。
 */
@Slf4j
@Component
public class WebhookPublisher {

    private final NotifyProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public WebhookPublisher(NotifyProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    /**
     * POST 一条载荷。
     *
     * @return true 表示对端返回 2xx；其余情况（含超时、连接失败、非 2xx）均为 false，
     *         调用方据此不更新 {@code last_pushed_at}，让下一轮自然重试
     */
    public boolean publish(Map<String, Object> payload) {
        String url = props.getWebhookUrl();
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(props.getTimeoutMs()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            log.warn("notify webhook 返回非 2xx status={} url={} body={}",
                    code, url, shorten(resp.body()));
            return false;
        } catch (Exception e) {
            log.warn("notify webhook 发送失败 url={} cause={}", url, e.toString());
            return false;
        }
    }

    /** 对端返回体只留一小段，避免把整页 HTML 灌进日志。 */
    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}