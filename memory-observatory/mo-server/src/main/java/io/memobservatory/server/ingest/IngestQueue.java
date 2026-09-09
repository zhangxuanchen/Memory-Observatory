package io.memobservatory.server.ingest;

import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemorySnapshot;
import io.memobservatory.server.storage.EventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内存队列削峰 + 批量写库（旁路观测容错范式，见 MVP 设计文档 §9 修订 2）。
 *
 * 容错策略：
 * - 入队非阻塞：offer 失败（队列满）则丢最旧保最新，记 WARN 不抛
 * - 后台单线程定时 drain（默认 5s），批量写库
 * - 写库异常只记 WARN，丢弃该批次（不重试不阻塞接收）
 * - 队列混合存事件与快照，drain 时按类型分拣
 */
@Component
public class IngestQueue {

    private static final Logger log = LoggerFactory.getLogger(IngestQueue.class);

    private final EventRepository repo;
    private final int capacity;
    private final long flushMs;
    private final BlockingQueue<Object> queue;
    private ScheduledExecutorService scheduler;

    public IngestQueue(EventRepository repo,
                       @Value("${mo.ingest.queue-capacity:10000}") int capacity,
                       @Value("${mo.ingest.flush-interval-ms:5000}") long flushMs) {
        this.repo = repo;
        this.capacity = capacity;
        this.flushMs = flushMs;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mo-ingest-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, flushMs, flushMs, TimeUnit.MILLISECONDS);
        log.info("ingest 队列启动 capacity={} flushMs={}", capacity, flushMs);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        flush(); // 关闭前再刷一次，避免丢数据
    }

    /** 入队：旁路容错，满则丢最旧保最新。 */
    public void push(Object item) {
        if (!queue.offer(item)) {
            queue.poll();
            queue.offer(item);
            log.warn("ingest 队列满（capacity={}），丢弃最旧一条", capacity);
        }
    }

    private void flush() {
        List<Object> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) return;

        List<MemoryEvent> events = new ArrayList<>();
        List<MemorySnapshot> snaps = new ArrayList<>();
        for (Object o : batch) {
            if (o instanceof MemoryEvent e) events.add(e);
            else if (o instanceof MemorySnapshot s) snaps.add(s);
        }
        try {
            if (!events.isEmpty()) repo.batchInsertEvents(events);
            if (!snaps.isEmpty()) repo.batchInsertSnapshots(snaps);
            log.debug("ingest flush ok: events={} snaps={}", events.size(), snaps.size());
        } catch (Exception ex) {
            // 旁路容错：写库失败只记 WARN，不抛、不阻塞接收（带完整 cause 便于定位）
            log.warn("ingest flush 写库失败，丢弃批次 size={} cause={}", batch.size(), ex.toString(), ex);
        }
    }
}
