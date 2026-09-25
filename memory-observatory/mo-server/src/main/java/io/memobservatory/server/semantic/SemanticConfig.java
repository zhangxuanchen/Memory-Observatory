package io.memobservatory.server.semantic;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 语义过滤层装配。
 *
 * 绑定 mo.semantic.*；ServerApplication 的 scanBasePackages 已包含 io.memobservatory.server，
 * 因此本包下的 @Component 会自动注册。
 */
@Configuration
@EnableConfigurationProperties(SemanticProperties.class)
public class SemanticConfig {
}
