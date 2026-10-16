package io.memobservatory.server.security;

import io.memobservatory.support.security.ApiKeyAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * API Key 鉴权的 mo-server 侧注册点。
 *
 * <p>实现只在 {@code mo-support} 有一份（2026-10-12 归并，原为 server/exo 各一份逐字重复）；
 * 本类只声明本站的保护面：{@code /api/**}（REST/工作台）与 {@code /v1/**}（OTLP 接收）。
 * key 与开关语义不变：{@code MO_API_KEY} 空 = 开放模式（过滤器构造时 WARN）。
 */
@Configuration
public class ApiKeyAuthConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 20)
    public ApiKeyAuthFilter apiKeyAuthFilter(@Value("${mo.auth.api-key:}") String apiKey) {
        return new ApiKeyAuthFilter(apiKey, "/api/", "/v1/");
    }
}
