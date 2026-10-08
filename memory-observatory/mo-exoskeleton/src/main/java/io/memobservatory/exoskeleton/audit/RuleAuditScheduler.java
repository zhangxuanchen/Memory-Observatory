package io.memobservatory.exoskeleton.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审计的定时入口（§4.7 调度与并发）。
 *
 * <p><b>不用 {@code @Scheduled}</b>——沿用项目现有范式：单线程 {@code ScheduledExecutorService}、
 * 守护线程、固定延迟。理由见 §4.7：审计是重活，不该占用公共的 Spring 调度线程池。
 *
 * <p><b>默认关闭</b>：{@code mo.exoskeleton.audit.enabled=false}。没配就不跑——
 * 「不能默认给生产加负载」（第 10 讲）。手动触发走 {@code POST /rules/audit}，与起草 / 晋升同一形态。
 *
 * <p>异常必须吞掉并记日志：这个循环是旁路的，任何一次失败都不该让调度线程死掉，
 * 否则「自动降级」会在无人察觉时静默停止——那比不跑更危险。
 */
@Component
public class RuleAuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(RuleAuditScheduler.class);

    private final RuleLifecycleService lifecycle;

    @Value("${mo.exoskeleton.audit.enabled:false}")
    private boolean enabled;
    @Value("${mo.exoskeleton.audit.initial-delay-minutes:1}")
    private long initialDelayMinutes;
    @Value("${mo.exoskeleton.audit.interval-minutes:1440}")
    private long intervalMinutes;

    private ScheduledExecutorService scheduler;

    public RuleAuditScheduler(RuleLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("规则审计调度未启用（mo.exoskeleton.audit.enabled=false）；手动触发走 POST /rules/audit");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mo-rule-audit");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::auditQuietly,
                initialDelayMinutes, intervalMinutes, TimeUnit.MINUTES);
        log.info("规则审计调度已启用：首次延迟 {} 分钟，之后每 {} 分钟一次", initialDelayMinutes, intervalMinutes);
    }

    private void auditQuietly() {
        try {
            var report = lifecycle.audit();
            log.info("规则审计完成：{} 条，降级 {}、撤回 {}、硬化 {}",
                    report.audited(), report.demoted(), report.retired(), report.hardened());
        } catch (Exception e) {
            log.warn("规则审计失败（不影响后续调度）：{}", e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
