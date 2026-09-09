package io.memobservatory.server.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key 鉴权过滤器。
 *
 * 协议：请求头 {@code Authorization: Bearer <key>} 或 {@code X-API-Key: <key>}，
 *      与 mo_sdk 的 exporter api_key（Bearer）天然对齐。
 *
 * 开关：配置项 {@code mo.auth.api-key}（环境变量 MO_API_KEY）。
 *   - 配置了 key：/api/** 与 /v1/**（OTLP 上报）全部强制校验，不匹配返回 401；
 *   - 未配置（空）：开放模式，仅打印 WARN——仅限本地/内网演示，生产必须配置。
 *
 * 设计：常量时间比较防时序侧信道；只读请求头不消费 body，无请求体缓存问题。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HDR_AUTH = "Authorization";
    private static final String HDR_API_KEY = "X-API-Key";

    @Value("${mo.auth.api-key:}")
    private String configuredKey;

    @PostConstruct
    void init() {
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("==========================================================");
            log.warn(" API Key 鉴权未启用（MO_API_KEY 未配置）：/api 与 /v1 接口完全开放。");
            log.warn(" 仅限本地/内网演示；生产部署请设置环境变量 MO_API_KEY。");
            log.warn("==========================================================");
        } else {
            log.info("API Key 鉴权已启用：/api/** 与 /v1/** 需携带 Authorization: Bearer <key>");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        // 开放模式（未配置 key）：直接放行
        if (configuredKey == null || configuredKey.isBlank()) {
            chain.doFilter(req, resp);
            return;
        }
        // 仅保护 API 与 OTLP 上报路径；静态资源等放行
        String path = req.getRequestURI();
        if (!isProtected(path) || "OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }
        String presented = extractKey(req);
        if (presented != null && constantTimeEquals(configuredKey, presented)) {
            chain.doFilter(req, resp);
            return;
        }
        reject(resp);
    }

    /** 受保护路径：REST/工作台 API（/api/）与 OTLP 接收（/v1/）。 */
    private static boolean isProtected(String path) {
        return path != null && (path.startsWith("/api/") || path.startsWith("/v1/"));
    }

    /** 从 Authorization: Bearer xxx 或 X-API-Key 头提取 key。 */
    private static String extractKey(HttpServletRequest req) {
        String auth = req.getHeader(HDR_AUTH);
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            String v = auth.substring(BEARER_PREFIX.length()).trim();
            if (!v.isEmpty()) return v;
        }
        String k = req.getHeader(HDR_API_KEY);
        return (k == null || k.isBlank()) ? null : k.trim();
    }

    /** 常量时间字符串比较，避免时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void reject(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write("{\"error\":\"unauthorized: missing or invalid API key\"}");
    }
}
