package io.memobservatory.support.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApiKeyAuthFilter} 行为测试（共享实现，mo-server / mo-exoskeleton 两处注册共用本套断言）。
 *
 * <p>四个面：开放模式放行、带对 key 放行、不带/带错 key 拒绝、前缀与预检的边界。
 * 前缀是参数化的——用 server 的姿势（/api/ + /v1/）与 exo 的姿势（仅 /api/）各测一遍。
 */
class ApiKeyAuthFilterTest {

    private static final String KEY = "test-key-123";

    private static MockHttpServletResponse run(ApiKeyAuthFilter filter, String uri,
                                               String method, String authHeader, String apiKeyHeader,
                                               MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (authHeader != null) req.addHeader("Authorization", authHeader);
        if (apiKeyHeader != null) req.addHeader("X-API-Key", apiKeyHeader);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, chain);
        return resp;
    }

    /** 开放模式（未配置 key）：一切放行——本机演示零配置体验不变。 */
    @Test
    void 开放模式全放行() throws Exception {
        ApiKeyAuthFilter open = new ApiKeyAuthFilter("", "/api/", "/v1/");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse resp = run(open, "/api/v1/exoskeleton/rules", "GET", null, null, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();  // 过滤器链确实走下去了
    }

    /** 配置了 key：Bearer 与 X-API-Key 两种姿势都认。 */
    @Test
    void 带对key放行两种头() throws Exception {
        ApiKeyAuthFilter f = new ApiKeyAuthFilter(KEY, "/api/", "/v1/");
        assertThat(run(f, "/api/v1/agents/a/events", "GET", "Bearer " + KEY, null, new MockFilterChain()).getStatus())
                .isEqualTo(200);
        assertThat(run(f, "/api/v1/agents/a/events", "GET", null, KEY, new MockFilterChain()).getStatus())
                .isEqualTo(200);
    }

    /** 不带 key、带错 key、Bearer 空值——一律 401 且不透链。 */
    @Test
    void 缺key或错key拒绝() throws Exception {
        ApiKeyAuthFilter f = new ApiKeyAuthFilter(KEY, "/api/", "/v1/");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse noKey = run(f, "/api/v1/exoskeleton/rules", "GET", null, null, chain);
        assertThat(noKey.getStatus()).isEqualTo(401);
        assertThat(noKey.getContentAsString()).contains("unauthorized");

        assertThat(run(f, "/api/v1/exoskeleton/rules", "GET", "Bearer wrong", null, chain).getStatus())
                .isEqualTo(401);
        assertThat(run(f, "/api/v1/exoskeleton/rules", "GET", "Bearer ", null, chain).getStatus())
                .isEqualTo(401);
        assertThat(chain.getRequest()).isNull();  // 被拒的请求没有到达下游
    }

    /** 前缀参数化是两份旧副本归并的关键：server 拦 /v1/（OTLP），exo 只拦 /api/。 */
    @Test
    void 受保护前缀由注册方声明() throws Exception {
        // server 姿势：/api/ 与 /v1/ 都保护
        ApiKeyAuthFilter server = new ApiKeyAuthFilter(KEY, "/api/", "/v1/");
        assertThat(run(server, "/v1/traces", "POST", null, null, new MockFilterChain()).getStatus())
                .isEqualTo(401);
        // exo 姿势：只保护 /api/，/v1/ 与静态路径不拦（它没有 OTLP 接收口）
        ApiKeyAuthFilter exo = new ApiKeyAuthFilter(KEY, "/api/");
        assertThat(run(exo, "/v1/traces", "POST", null, null, new MockFilterChain()).getStatus())
                .isEqualTo(200);
        assertThat(run(exo, "/api/v1/exoskeleton/rules", "GET", null, null, new MockFilterChain()).getStatus())
                .isEqualTo(401);
    }

    /** CORS 预检（OPTIONS）不拦；未声明的路径不拦。 */
    @Test
    void 预检与非受护路径不拦() throws Exception {
        ApiKeyAuthFilter f = new ApiKeyAuthFilter(KEY, "/api/");
        assertThat(run(f, "/api/v1/exoskeleton/rules", "OPTIONS", null, null, new MockFilterChain()).getStatus())
                .isEqualTo(200);
        assertThat(run(f, "/actuator/health", "GET", null, null, new MockFilterChain()).getStatus())
                .isEqualTo(200);
    }
}
