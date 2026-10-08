package io.memobservatory.server.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.server.storage.EventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原始事件流导出：把 {@code memory_events} 按导入契约的字段与格式写出，导出的文件可以再导入回来。
 *
 * <p>「可回灌」是这份导出的唯一验收标准，因此有三处必须与导入侧严格对齐
 * （见 {@code WriteController} 的 {@code toEvent} / {@code parseTs}）：
 * <ol>
 *   <li>字段名用导入契约的 snake_case（{@code event_id} / {@code agent_id} / …），
 *       不是 DB 列名，也不是接口响应用的 camelCase</li>
 *   <li>{@code timestamp} 用 {@code yyyy-MM-dd HH:mm:ss} + {@code Asia/Shanghai}，
 *       与导入侧的 {@code TS_FMT} / {@code TS_ZONE} 逐字一致。用别的时区导出，
 *       回灌后整批事件的时间会平移</li>
 *   <li>{@code metadata} 写成 JSON 对象而不是字符串：导入侧 {@code parseMetadata}
 *       对 Map 与 JSON 文本都接受，但对象形式在阅读和 diff 时更友好</li>
 * </ol>
 *
 * <p><b>已知的有损点</b>：导入契约的时间格式只到秒，而 DB 的 {@code ts} 带微秒，
 * 所以回灌后亚秒精度会归零。这是导入侧的格式约束，不是导出可以单方面解决的问题。
 */
@Slf4j
@Service
public class EventExportService {

    /**
     * 导出格式。字段契约两者相同：json 数组可直接被 {@code POST /api/v1/import?format=json} 回灌，
     * jsonl 逐行（导入侧不认 jsonl，回灌前需包成 JSON 数组）。
     */
    public enum Format { JSONL, JSON }

    /** 必须与 WriteController.TS_FMT 逐字一致，否则回灌时间会平移。 */
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 必须与 WriteController.TS_ZONE 逐字一致。 */
    private static final ZoneId TS_ZONE = ZoneId.of("Asia/Shanghai");

    private final EventRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxRows;

    public EventExportService(EventRepository repo,
                              @Value("${mo.export.max-rows:100000}") int maxRows) {
        this.repo = repo;
        this.maxRows = maxRows;
    }

    /** 解析 {@code format} 参数；空或无法识别时按 JSONL 处理。 */
    public static Format parseFormat(String s) {
        if (s == null) {
            return Format.JSONL;
        }
        return "json".equalsIgnoreCase(s.trim()) ? Format.JSON : Format.JSONL;
    }

    /** 请求的导出条件。{@code limit} 会在服务内被 {@code mo.export.max-rows} 二次夹紧。 */
    public record Request(Instant from, Instant to, String agentId, String sessionId,
                          String traceId, int limit) {
    }

    /**
     * 流式写出事件。
     *
     * <p>逐行序列化、逐行写出，内存占用与导出行数无关：导出窗口动辄几十万行，
     * 先物化成 List 再 {@code writeValueAsBytes} 会把堆打满。
     *
     * @return 实际写出的行数
     */
    public long export(Request req, Format format, OutputStream out) throws IOException {
        int limit = req.limit() > 0 ? Math.min(req.limit(), maxRows) : maxRows;
        // JSON 数组需要在行之间插逗号，但写第一行时还不知道后面有没有行，用个可变标志位
        boolean[] first = {true};
        long[] written = {0};

        if (format == Format.JSON) {
            out.write('[');
        }
        repo.streamEventsForExport(req.from(), req.to(), req.agentId(), req.sessionId(),
                req.traceId(), limit, rs -> {
                    try {
                        Map<String, Object> row = toExportRow(rs);
                        byte[] json = mapper.writeValueAsBytes(row);
                        if (format == Format.JSON) {
                            if (!first[0]) {
                                out.write(',');
                            }
                            out.write('\n');
                        }
                        first[0] = false;
                        out.write(json);
                        out.write('\n');
                        written[0]++;
                    } catch (IOException | SQLException e) {
                        // 行回调签名不允许抛检查异常，包成非检查异常交给调用方
                        throw new ExportWriteException(e);
                    }
                });
        if (format == Format.JSON) {
            if (!first[0]) {
                out.write('\n');
            }
            out.write(']');
        }
        out.flush();
        return written[0];
    }

    /**
     * 一行 → 导入契约字段。
     *
     * <p>字段顺序按契约固定，便于产出文件的 diff 稳定。
     */
    private Map<String, Object> toExportRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", rs.getString("event_id"));
        row.put("agent_id", rs.getString("agent_id"));
        row.put("session_id", rs.getString("session_id"));
        row.put("operation", rs.getString("operation"));
        row.put("layer", rs.getString("layer"));
        row.put("memory_key", rs.getString("memory_key"));
        row.put("memory_summary", rs.getString("memory_summary"));
        row.put("token_count", rs.getInt("token_count"));
        row.put("latency_ms", rs.getDouble("latency_ms"));
        Timestamp ts = rs.getTimestamp("ts");
        row.put("timestamp", ts == null
                ? null
                : LocalDateTime.ofInstant(ts.toInstant(), TS_ZONE).format(TS_FMT));
        row.put("trace_id", rs.getString("trace_id"));
        row.put("parent_span_id", rs.getString("parent_span_id"));
        row.put("metadata", metadata(rs.getObject("metadata")));
        return row;
    }

    /**
     * metadata 单元 → JSON 对象。
     *
     * <p>导出侧是边界，库里万一存了非对象（数组/标量）或坏 JSON，原样带出比丢弃更安全：
     * 丢字段会让回灌后的数据与源数据不一致，且没人会发现。
     */
    private Object metadata(Object raw) {
        if (raw == null) {
            return new LinkedHashMap<String, Object>();
        }
        String s = raw.toString();
        if (s.isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return mapper.readValue(s, Object.class);
        } catch (Exception e) {
            log.warn("导出：metadata 不是合法 JSON，按原文本带出 snippet={}", shorten(s));
            return s;
        }
    }

    private static String shorten(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120);
    }

    /** 行回调签名不允许抛检查异常，用它把 IO/RS 异常送到调用方。 */
    public static class ExportWriteException extends RuntimeException {
        public ExportWriteException(Throwable cause) {
            super(cause);
        }
    }

    /** 供 Controller 拼 Content-Type 用。 */
    public static String contentType(Format format) {
        return format == Format.JSON ? "application/json" : "application/x-ndjson";
    }

    /** 供 Controller 拼文件名后缀用。 */
    public static String extension(Format format) {
        return format == Format.JSON ? "json" : "jsonl";
    }
}