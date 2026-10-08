package io.memobservatory.server.exoskeleton;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 记忆外骨骼（mo-exoskeleton）的转发层。
 *
 * <p><b>为什么需要这一层</b>：mo-exoskeleton 是<b>另一个进程</b>（默认 :8081，不在 compose 内、
 * 无鉴权过滤器），而前端页面由 mo-server 提供。若让浏览器直连 8081，页面就得硬编码端口、
 * 跨端口取数，还绕开了本站既有的鉴权通道。改为镜像一层后，页面只需同源调
 * {@code /api/v1/exoskeleton/**}——index.html 的全局 fetch 包装器会自动给同源 {@code /api/}
 * 请求注入 {@code Authorization: Bearer}，前端<b>零改动</b>即可复用既有鉴权。
 *
 * <p><b>路径原样镜像</b>：转发目标 = {@code baseUrl + 原始 URI + query}，不做任何路径翻译。
 * 前端用的就是外骨骼自己的真实路径（{@code /api/v1/exoskeleton/cards} 等），
 * 这样看页面源码与看后端接口文档是同一条路径，不会多一层需要对照的记忆负担。
 *
 * <p><b>刻意不做的事</b>：不改写响应体、不缓存、不重试、不做降级为空数据。
 * 外骨骼不可达时<b>如实回 503</b>并说明原因——与 §4.10 的「不编证据」同一条原则：
 * 拿不到卡就说拿不到，不能让页面显示成「零张卡」那种看起来正常的假象。
 */
@Slf4j
@RestController
public class ExoskeletonProxyController {

    /** 外骨骼基址。默认本机；容器内由 compose 经 {@code MO_EXOSKELETON_BASE_URL} 覆盖为 host.docker.internal。 */
    private final String baseUrl;

    private final HttpClient http;

    /** 单次转发的最长等待。取值对齐 mo-dashboard/nginx.conf 的 {@code proxy_read_timeout 3600s}。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3600);

    public ExoskeletonProxyController(
            @Value("${mo.exoskeleton.base-url:http://localhost:8081}") String baseUrl) {
        // 去掉尾部斜杠，避免与镜像过来的 URI 拼出 "//"
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 连接超时保持 2 秒：外骨骼压根没起来时仍要快速失败、如实回 503。
        // 请求超时则必须放宽——/tasks/classify 与 /skills/discover 是 LLM 驱动、逐类跑的同步接口，
        // 一次要几分钟；卡在 30 秒会让「重新提炼」按钮每次都误报成「不可达」，
        // 明明外骨骼在跑、只是还没跑完。放宽只影响「连上了但对端很慢」这一种情形，不损害 503 的诚实性。
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * 镜像转发。方法不限——出卡/作答是 POST、清单是 GET，都走这一条。
     *
     * <p>用 {@code getRequestURI()}（原始、仍是百分号编码形式）而不是解码后的路径：
     * 卡片 id 含 {@code :} 与 {@code |}（如 {@code T1:cluster:readonly|steps:1-3}），
     * 客户端已对 {@code |} 做 {@code %7C} 编码，原样透传最稳。
     */
    @RequestMapping("/api/v1/exoskeleton/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body) {
        String target = baseUrl + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target)).timeout(REQUEST_TIMEOUT);
        String contentType = request.getContentType();
        if (contentType != null) {
            rb.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        byte[] payload = body == null ? new byte[0] : body;
        String method = request.getMethod();
        switch (method) {
            case "GET" -> rb.GET();
            case "DELETE" -> rb.DELETE();
            case "PUT" -> rb.PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
            default -> rb.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        }

        try {
            HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(resp.statusCode())
                    .contentType(contentTypeOf(resp))
                    .body(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(target, e);
        } catch (Exception e) {
            return unavailable(target, e);
        }
    }

    private static MediaType contentTypeOf(HttpResponse<byte[]> resp) {
        return resp.headers().firstValue(HttpHeaders.CONTENT_TYPE)
                .map(MediaType::parseMediaType)
                .orElse(MediaType.APPLICATION_JSON);
    }

    /** 不可达时如实回 503：写明是「外骨骼没起来」，而不是伪造一个空列表。 */
    private static ResponseEntity<byte[]> unavailable(String target, Exception e) {
        // 连接被拒时 e.getMessage() 常为 null（如 ConnectException），此时退回异常类名——
        // 报错信息宁可粗一点，也不能是「未能取数：null」这种看不出原因的字样。
        String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String msg = "记忆外骨骼（mo-exoskeleton）不可达，未能取数：%s。请确认它已启动（默认 %s，"
                .formatted(cause, target)
                + "本地跑法：cd mo-exoskeleton && mvn spring-boot:run）。";
        String json = "{\"error\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        return ResponseEntity.status(503)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }
}
