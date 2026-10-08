package io.memobservatory.server.api;

import io.memobservatory.server.export.EventExportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 原始事件流导出（导入的对称面）。
 *
 * <p>导出用 {@link StreamingResponseBody} 而不是 {@code ResponseEntity<byte[]>}：
 * 后者的写法（见 {@code ApiController.importSkillPackage}）要求内容先在内存里成完整字节数组，
 * 几十万行事件会直接把堆打满。流式写出对浏览器没有任何差别，都是普通附件下载。
 *
 * <p>导出的字段与格式对齐 {@code POST /api/v1/import} 的契约：{@code format=json} 产出的数组文件
 * 可直接回灌，{@code format=jsonl} 逐行（导入侧不认 jsonl，回灌前需包成 JSON 数组）。
 */
@Slf4j
@RestController
public class ExportController {

    private static final DateTimeFormatter FNAME_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EventExportService exportService;

    public ExportController(EventExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出原始事件流。
     *
     * <p>参数：{@code format=jsonl|json}（默认 jsonl）、{@code days}（默认 7，0 表示不限时间）、
     * {@code agent} / {@code session} / {@code trace} 三个可选过滤、{@code limit}（默认与上限见
     * {@code mo.export.max-rows}）。
     */
    @GetMapping("/api/v1/export/events")
    public ResponseEntity<StreamingResponseBody> exportEvents(
            @RequestParam(name = "format", required = false, defaultValue = "jsonl") String format,
            @RequestParam(name = "days", required = false, defaultValue = "7") int days,
            @RequestParam(name = "agent", required = false) String agent,
            @RequestParam(name = "session", required = false) String session,
            @RequestParam(name = "trace", required = false) String trace,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {

        EventExportService.Format fmt = EventExportService.parseFormat(format);
        Instant to = Instant.now();
        Instant from = days > 0 ? to.minusSeconds(days * 86400L) : null;
        EventExportService.Request req =
                new EventExportService.Request(from, to, agent, session, trace, limit);

        String fname = "mo-events" + scopeSuffix(agent, session, trace)
                + "-" + LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(FNAME_TS)
                + "." + EventExportService.extension(fmt);
        long[] written = {0};

        StreamingResponseBody body = (OutputStream out) -> {
            try {
                written[0] = exportService.export(req, fmt, out);
                log.info("导出完成 format={} rows={} agent={} session={} trace={} days={}",
                        fmt, written[0], agent, session, trace, days);
            } catch (Exception e) {
                // 响应头已经发出，这里改不了状态码，只能记日志；
                // 下载方会看到文件被截断，配合日志可定位
                log.warn("导出中断 format={} rowsWritten={} cause={}",
                        fmt, written[0], e.toString(), e);
                throw e;
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType(EventExportService.contentType(fmt)))
                .body(body);
    }

    /** 文件名里带上过滤范围，多个导出文件放在一起时能区分。 */
    private static String scopeSuffix(String agent, String session, String trace) {
        StringBuilder sb = new StringBuilder();
        if (agent != null && !agent.isBlank()) {
            sb.append("-").append(safe(agent));
        }
        if (session != null && !session.isBlank()) {
            sb.append("-").append(safe(session));
        }
        if (trace != null && !trace.isBlank()) {
            sb.append("-").append(safe(trace));
        }
        return sb.toString();
    }

    /** 去掉会把文件名搞坏或造成路径穿越的字符。 */
    private static String safe(String s) {
        String cleaned = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 40 ? cleaned : cleaned.substring(0, 40);
    }
}