package io.memobservatory.support.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * API Key 鉴权过滤器——mo-server 与 mo-exoskeleton <b>共用这一份</b>（2026-10-12 由
 * 两份逐字重复的模块私有副本归并而来；归并动机见架构重构蓝图 §7.1 跟进记录）。
 *
 * <p>协议：{@code Authorization: Bearer <key>} 或 {@code X-API-Key: <key>}，
 * 与 mo_sdk 的 exporter api_key（Bearer）天然对齐；两个服务共用同一把
 * {@code MO_API_KEY}，不存在「一侧开了另一侧还开着」的错配窗口。
 *
 * <p>开关语义：key 为空 = 开放模式（构造时打 WARN 一次），仅限本地/内网演示；
 * 配置后仅 {@code protectedPrefixes} 命中的路径强制校验，不匹配 401。
 * 前缀由各消费模块的 {@code @Bean} 工厂方法传入——mo-server 是 {@code /api/} 与
 * {@code /v1/}（REST + OTLP），mo-exoskeleton 只有 {@code /api/}（无 OTLP 接收口）。
 *
 * <p>本类<b>不带</b> {@code @Component}：由每个应用显式 {@code @Bean} 注册并传入各自己的
 * 路径前缀，support 作为库不靠包扫描塞组件进任何容器。
 *
 * <p>设计：常量时间比较防时序侧信道；只读请求头不消费 body，无请求体缓存问题。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HDR_AUTH = "Authorization";
    private static final String HDR_API_KEY = "X-API-Key";

    private final String configuredKey;
    private final String[] protectedPrefixes;

    /**
     * @param configuredKey      鉴权 key（env {@code MO_API_KEY}）；空 = 开放模式
     * @param protectedPrefixes  受保护路径前缀（如 {@code "/api/", "/v1/"}）
     */
    public ApiKeyAuthFilter(String configuredKey, String... protectedPrefixes) {
        this.configuredKey = configuredKey;
        this.protectedPrefixes = protectedPrefixes;
        if (configuredKey == null || configuredKey.isBlank()) {
            log.warn("==========================================================");
            log.warn(" API Key 鉴权未启用（MO_API_KEY 未配置）：受保护接口完全开放。");
            log.warn(" 仅限本地/内网演示；生产部署请设置环境变量 MO_API_KEY（两个服务同一把）。");
            log.warn("==========================================================");
        } else {
            String faces = String.join(" / ", protectedPrefixes).replaceAll("/$", "/**");
            log.info("API Key 鉴权已启用：{} 需携带 Authorization: Bearer <key>", faces);
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
        // 仅保护声明过的前缀；CORS 预检放行；其余（静态资源等）不拦
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

    /** 受保护路径：命中构造时声明的任一前缀。 */
    private boolean isProtected(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : protectedPrefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
