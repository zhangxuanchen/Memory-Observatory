package io.memobservatory.server.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.contract.EventQueryPort;
import io.memobservatory.contract.EventStreamPort;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemoryOp;
import io.memobservatory.server.model.MemorySnapshot;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EventRepository} 按域拆出的协作类（Write 域）。
 * 由门面实例化并委托，非 Spring Bean；通用件继承自 {@link SqlRepositoryBase}。
 */
class EventWriteStore extends SqlRepositoryBase {

    EventWriteStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    private static final String SQL_INSERT_EVENT = """
            INSERT INTO memory_events (event_id, agent_id, session_id, operation, layer,
                memory_key, memory_summary, token_count, latency_ms, ts, metadata,
                trace_id, parent_span_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String SQL_INSERT_SNAPSHOT = """
            INSERT INTO memory_snapshots (snapshot_id, agent_id, session_id, total_tokens,
                system_tokens, task_tokens, memory_tokens, tool_history_tokens, free_tokens,
                compression_count, last_compression_ratio, ts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (snapshot_id) DO NOTHING
            """;

    private static final String SQL_IMPORT_LOG_FIND = """
            SELECT md5, file_name, llm, total, inserted, skipped, error_count, imported_at
            FROM import_log_files WHERE md5 = ?
            """;

    private static final String SQL_IMPORT_LOG_UPSERT = """
            INSERT INTO import_log_files (md5, file_name, llm, total, inserted, skipped, error_count)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (md5) DO UPDATE SET
                file_name = EXCLUDED.file_name, llm = EXCLUDED.llm,
                total = EXCLUDED.total, inserted = EXCLUDED.inserted,
                skipped = EXCLUDED.skipped, error_count = EXCLUDED.error_count,
                imported_at = now()
            """;

    /** 按 MD5 查已导入的 .log 记录；不存在返回 null。 */
    public Map<String, Object> findImportedLogByMd5(String md5) {
        try {
            return jdbc.queryForMap(SQL_IMPORT_LOG_FIND, md5);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /** 记录（或刷新）一个已导入 .log 的 MD5 指纹与导入统计。 */
    public void upsertImportedLog(String md5, String fileName, boolean llm,
                                  int total, int inserted, int skipped, int errorCount) {
        jdbc.update(SQL_IMPORT_LOG_UPSERT, md5, fileName, llm, total, inserted, skipped, errorCount);
    }

    public void batchInsertEvents(List<MemoryEvent> events) {
        if (events.isEmpty()) return;
        jdbc.batchUpdate(SQL_INSERT_EVENT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MemoryEvent e = events.get(i);
                ps.setString(1, e.eventId());
                ps.setString(2, e.agentId());
                ps.setString(3, e.sessionId());
                ps.setString(4, e.operation().name());
                ps.setString(5, e.layer());
                ps.setString(6, e.memoryKey());
                ps.setString(7, e.memorySummary());
                ps.setInt(8, e.tokenCount());
                ps.setDouble(9, e.latencyMs());
                ps.setTimestamp(10, Timestamp.from(e.timestamp()));
                ps.setObject(11, jsonb(e.metadata()));
                ps.setString(12, e.traceId());
                ps.setString(13, e.parentSpanId());
            }

            @Override
            public int getBatchSize() {
                return events.size();
            }
        });
    }

    public void batchInsertSnapshots(List<MemorySnapshot> snaps) {
        if (snaps.isEmpty()) return;
        jdbc.batchUpdate(SQL_INSERT_SNAPSHOT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                MemorySnapshot s = snaps.get(i);
                ps.setString(1, s.snapshotId());
                ps.setString(2, s.agentId());
                ps.setString(3, s.sessionId());
                ps.setInt(4, s.totalTokens());
                ps.setInt(5, s.systemTokens());
                ps.setInt(6, s.taskTokens());
                ps.setInt(7, s.memoryTokens());
                ps.setInt(8, s.toolHistoryTokens());
                ps.setInt(9, s.freeTokens());
                ps.setInt(10, s.compressionCount());
                ps.setDouble(11, s.lastCompressionRatio());
                ps.setTimestamp(12, Timestamp.from(s.timestamp()));
            }

            @Override
            public int getBatchSize() {
                return snaps.size();
            }
        });
    }

}
