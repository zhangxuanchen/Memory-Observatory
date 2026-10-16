package io.memobservatory.server.notify;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 告警轮询：定时评估问题源 → 去重判定 → webhook 推送。
 *
 * <p>周期任务沿用 {@code ingest/IngestQueue} 的范式：单线程命名守护线程 +
 * {@code scheduleWithFixedDelay}，不用 {@code @Scheduled}（项目里没有 {@code @EnableScheduling}）。
 *
 * <p>不用事件驱动是首版的有意取舍：判定本身要扫窗口聚合，做成事件驱动就得维持增量状态，
 * 而重复评估同一批问题的代价只是几次查询。
 */
@Slf4j
@Component
public class NotifyScheduler {

    /** 严重度排序：critical 先推。 */
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "critical", 0, "high", 1, "medium", 2);

    private final NotifyProperties props;
    private final FindingCollector collector;
    private final AlertRepository alerts;
    private final WebhookPublisher publisher;

    private ScheduledExecutorService scheduler;

    public NotifyScheduler(NotifyProperties props,
                           FindingCollector collector,
                           AlertRepository alerts,
                           WebhookPublisher publisher) {
        this.props = props;
        this.collector = collector;
        this.alerts = alerts;
        this.publisher = publisher;
    }

    @PostConstruct
    void start() {
        if (!props.isEnabled() || props.getWebhookUrl() == null || props.getWebhookUrl().isBlank()) {
            log.info("notify 未启用（enabled={} webhookUrl={}），跳过轮询",
                    props.isEnabled(), props.getWebhookUrl() == null ? "" : props.getWebhookUrl());
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mo-notify-poller");
            t.setDaemon(true);
            return t;
        });
        long interval = props.getPollIntervalMs();
        scheduler.scheduleWithFixedDelay(this::runOnce, interval, interval, TimeUnit.MILLISECONDS);
        log.info("notify 轮询启动 intervalMs={} windowDays={} semantic={} maxPerRound={}",
                interval, props.getWindowDays(), props.isSemantic(), props.getMaxPerRound());
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 单轮：采集 → 刷状态 → 判定 → 推送 → 恢复判定。 */
    private void runOnce() {
        try {
            tick();
        } catch (Exception e) {
            // 单轮失败不该让周期任务停摆
            log.warn("notify 单轮执行异常 cause={}", e.toString(), e);
        }
    }

    private void tick() {
        boolean quiet = inQuietHours();
        FindingCollector.Collected collected = collector.collect(
                props.getWindowDays(), props.isSemantic(), props.getAgentsLimit(),
                props.getTopHits(), props.getMinSeverity());
        List<AlertCandidate> candidates = collected.candidates();
        if (collected.degraded()) {
            log.warn("notify 本轮采集不完整 failures={} candidates={}，跳过恢复判定",
                    collected.failures(), candidates.size());
        }

        // 逐个 upsert：本轮出现的候选都要刷新 last_seen_at，不管最后推不推
        Set<String> seenKeys = new LinkedHashSet<>();
        for (AlertCandidate c : candidates) {
            seenKeys.add(c.dedupKey());
            try {
                alerts.upsertSeen(c);
            } catch (Exception e) {
                log.warn("notify 写状态失败 key={} cause={}", c.dedupKey(), e.toString(), e);
            }
        }

        if (quiet) {
            log.info("notify 处于静默时段（{}），只更新状态不推送 candidates={}",
                    props.getQuietHours(), candidates.size());
            return;
        }

        Map<String, AlertRepository.State> states;
        try {
            states = alerts.loadStates(seenKeys);
        } catch (Exception e) {
            log.warn("notify 读状态失败，本轮不推送 cause={}", e.toString(), e);
            return;
        }

        List<AlertCandidate> toPush = new ArrayList<>();
        for (AlertCandidate c : candidates) {
            AlertRepository.State state = states.get(c.dedupKey());
            if (state == null) {
                toPush.add(c);                                  // 新问题
            } else if (state.coolingDown(props.getCooldownMinutes())) {
                // 冷却窗口内：已在库里刷新过 last_seen_at，这里什么都不做
            } else {
                toPush.add(c);                                  // 冷却已过，作为提醒重推一次
            }
        }

        toPush.sort(Comparator.comparingInt(c -> SEVERITY_RANK.getOrDefault(c.severity(), 3)));
        int limit = props.getMaxPerRound();
        if (toPush.size() > limit) {
            log.info("notify 本轮待推 {} 条，按严重度只取前 {} 条，其余留待下轮", toPush.size(), limit);
            toPush = toPush.subList(0, limit);
        }

        int sent = 0;
        for (AlertCandidate c : toPush) {
            if (!publisher.publish(c.payload())) {
                // 失败不更新 last_pushed_at，下轮自然重试
                continue;
            }
            sent++;
            try {
                alerts.markPushed(c.dedupKey());
            } catch (Exception e) {
                log.warn("notify 标记已推失败 key={} cause={}", c.dedupKey(), e.toString(), e);
            }
        }
        if (sent > 0) {
            log.info("notify 本轮推送成功 {}/{} 条", sent, toPush.size());
        }

        if (!collected.degraded()) {
            handleRecovery(new ArrayList<>(seenKeys));
        }
    }

    /**
     * 恢复判定：本轮没再出现的 active 键置为 recovered。
     *
     * <p>只在采集完整时执行——采集有异常时「没查到」不等于「问题好了」。
     * 默认只记录不推送（{@code push-recovery=false}），避免首版就制造通知噪音。
     */
    private void handleRecovery(List<String> seenKeys) {
        List<String> recovered;
        try {
            recovered = alerts.findRecoverableKeys(seenKeys);
        } catch (Exception e) {
            log.warn("notify 恢复判定查询失败 cause={}", e.toString(), e);
            return;
        }
        if (recovered.isEmpty()) {
            return;
        }
        log.info("notify 本轮判定 {} 条告警已恢复", recovered.size());
        if (!props.isPushRecovery()) {
            alerts.markRecovered(recovered);
            return;
        }
        for (String key : recovered) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "recovery");
            payload.put("dedupKey", key);
            payload.put("status", "recovered");
            payload.put("detectedAt", Instant.now().toString());
            publisher.publish(payload);
        }
        alerts.markRecovered(recovered);
    }

    /**
     * 是否处于静默时段。
     *
     * <p>只支持整点区间（如 {@code "22-8"}），跨零点由 {@code start > end} 表达。
     * 不支持工作日 / 节假日日历——那需要一份日历数据，首版不做。
     */
    private boolean inQuietHours() {
        String spec = props.getQuietHours();
        if (spec == null || spec.isBlank()) {
            return false;
        }
        int dash = spec.indexOf('-');
        if (dash <= 0 || dash == spec.length() - 1) {
            log.warn("notify quiet-hours 格式无法解析，按不静默处理: {}", spec);
            return false;
        }
        try {
            int start = Integer.parseInt(spec.substring(0, dash).trim());
            int end = Integer.parseInt(spec.substring(dash + 1).trim());
            int hour = LocalTime.now(ZoneId.systemDefault()).getHour();
            if (start == end) {
                return false;
            }
            return start < end ? (hour >= start && hour < end) : (hour >= start || hour < end);
        } catch (NumberFormatException e) {
            log.warn("notify quiet-hours 格式无法解析，按不静默处理: {}", spec);
            return false;
        }
    }
}