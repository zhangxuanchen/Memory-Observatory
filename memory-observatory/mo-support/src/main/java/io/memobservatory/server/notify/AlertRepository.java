package io.memobservatory.server.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警推送状态存储（{@code alert_notifications}）。
 *
 * <p>去重状态落库而不是放内存：进程重启后不能把已经推过的问题全部重推一遍。
 *
 * <p>表由 {@code schema.sql} 幂等创建（{@code spring.sql.init.mode: always}）。
 */
@Slf4j
@Repository
public class AlertRepository {

    private static final String SQL_UPSERT = """
            INSERT INTO alert_notifications (dedup_key, source, agent_id, severity, title, payload, status)
            VALUES (?, ?, ?, ?, ?, ?, 'active')
            ON CONFLICT (dedup_key) DO UPDATE SET
                last_seen_at = now(),
                severity     = EXCLUDED.severity,
                title        = EXCLUDED.title,
                payload      = EXCLUDED.payload,
                status       = 'active',
                recovered_at = NULL
            """;

    private static final String SQL_SELECT_STATES = """
            SELECT dedup_key, status, last_pushed_at, push_count
            FROM alert_notifications WHERE dedup_key = ANY(?)
            """;

    private static final String SQL_MARK_PUSHED = """
            UPDATE alert_notifications
            SET last_pushed_at = now(), push_count = push_count + 1
            WHERE dedup_key = ?
            """;

    private static final String SQL_FIND_RECOVERABLE = """
            SELECT dedup_key FROM alert_notifications
            WHERE status = 'active' AND dedup_key <> ALL(?)
            """;

    private static final String SQL_MARK_RECOVERED = """
            UPDATE alert_notifications
            SET status = 'recovered', recovered_at = now()
            WHERE status = 'active' AND dedup_key = ANY(?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AlertRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 一条告警的既有状态。 */
    public record State(String dedupKey, String status, Instant lastPushedAt, int pushCount) {
        /** 是否处于冷却窗口内（应只刷新 last_seen_at，不再发送）。 */
        public boolean coolingDown(long cooldownMinutes) {
            if (lastPushedAt == null) {
                return false;
            }
            return lastPushedAt.plusSeconds(cooldownMinutes * 60L).isAfter(Instant.now());
        }
    }

    /** 取这批键的既有状态；不存在的键不会出现在返回的 Map 里。 */
    public Map<String, State> loadStates(Collection<String> keys) {
        Map<String, State> out = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        String[] arr = keys.toArray(new String[0]);
        jdbc.query(SQL_SELECT_STATES,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", arr)),
                rs -> {
                    String key = rs.getString("dedup_key");
                    Timestamp pushed = rs.getTimestamp("last_pushed_at");
                    out.put(key, new State(key, rs.getString("status"),
                            pushed == null ? null : pushed.toInstant(), rs.getInt("push_count")));
                });
        return out;
    }

    /**
     * 候选本轮出现：写库并刷新 {@code last_seen_at}。
     *
     * <p>同时把 {@code recovered} 拉回 {@code active}——问题又出现了，说明没修好。
     * {@code first_seen_at} / {@code last_pushed_at} / {@code push_count} 都保留不动。
     */
    public void upsertSeen(AlertCandidate c) {
        jdbc.update(SQL_UPSERT, c.dedupKey(), c.source(), c.agentId(), c.severity(), c.title(),
                jsonb(c.payload()));
    }

    /** 推送成功：更新 {@code last_pushed_at} 与 {@code push_count}。失败时不要调这个。 */
    public void markPushed(String dedupKey) {
        jdbc.update(SQL_MARK_PUSHED, dedupKey);
    }

    /**
     * 本轮未出现、仍处于 {@code active} 的键。
     *
     * <p>调用方需保证本轮采集是完整的：采集出现异常时不应调用，
     * 否则会把「这次没查到」误判成「问题已经好了」。
     */
    public List<String> findRecoverableKeys(List<String> seenKeys) {
        String[] arr = seenKeys.toArray(new String[0]);
        return jdbc.query(SQL_FIND_RECOVERABLE,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", arr)),
                (rs, n) -> rs.getString("dedup_key"));
    }

    /** 把给定键置为 {@code recovered}，返回被置的条数。 */
    public int markRecovered(List<String> keys) {
        if (keys.isEmpty()) {
            return 0;
        }
        String[] arr = keys.toArray(new String[0]);
        return jdbc.update(SQL_MARK_RECOVERED,
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", arr)));
    }

    private Object jsonb(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            PGobject pgo = new PGobject();
            pgo.setType("jsonb");
            pgo.setValue(mapper.writeValueAsString(payload));
            return pgo;
        } catch (Exception e) {
            log.warn("notify 载荷序列化失败，本条 payload 记为空 cause={}", e.toString(), e);
            return null;
        }
    }
}