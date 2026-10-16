package io.memobservatory.server.notify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 告警推送装配。
 *
 * 绑定 mo.notify.*；ServerApplication 的 scanBasePackages 已包含 io.memobservatory.server，
 * 因此本包下的 @Component 会自动注册。
 */
@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {
}