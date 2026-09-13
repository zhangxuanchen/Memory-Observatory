package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.memobservatory.agentloop.workbench.core.AgentProperties;
import io.memobservatory.agentloop.workbench.core.GlobalKeyStore;
import io.memobservatory.server.api.dto.CreateEventDTO;
import io.memobservatory.server.model.MemoryEvent;
import io.memobservatory.server.model.MemoryOp;
import io.memobservatory.server.storage.EventRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写接口（CQRS 写侧）：所有产生数据变更的请求。
 *
 * POST /api/v1/events  单条记忆事件上报（其他应用直接 HTTP 调用）
 * POST /api/v1/import  批量导入（Excel .xlsx / JSON 上传）
 * 读接口统一放在 {@link ApiController}。
 */
@RestController
public class WriteController {

    @Autowired
    private EventRepository repo;

    @Autowired
    private AgentProperties agentProps;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 导入模板列顺序（与模板读接口 / 解析行保持一致）。 */
    private static final List<String> HEADERS = List.of(
            "event_id", "agent_id", "session_id", "operation", "layer",
            "memory_key", "memory_summary", "token_count", "latency_ms",
            "timestamp", "trace_id", "parent_span_id", "metadata");

    // ==================== 单条上报 ====================

    /** 新增记忆事件（POST）。供其他应用直接 HTTP JSON 上报一条事件入库。 */
    @PostMapping("/api/v1/events")
    public ResponseEntity<Map<String, Object>> createEvent(@RequestBody CreateEventDTO dto) {
        if (dto.getAgentId() == null || dto.getAgentId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "agentId is required"));
        }
        // session_id 列 NOT NULL：未传时兜底"default-session"。
        String sessionId = (dto.getSessionId() == null || dto.getSessionId().isBlank())
                ? "default-session"
                : dto.getSessionId();
        MemoryEvent event = MemoryEvent.builder()
                .eventId((dto.getEventId() == null || dto.getEventId().isBlank())
                        ? uuid16()
                        : dto.getEventId())
                .agentId(dto.getAgentId())
                .sessionId(sessionId)
                .operation(dto.getOperation())
                .layer(dto.getLayer() == null || dto.getLayer().isBlank() ? "provider" : dto.getLayer())
                .memoryKey(dto.getMemoryKey())
                .memorySummary(dto.getMemorySummary())
                .tokenCount(dto.getTokenCount())
                .latencyMs(dto.getLatencyMs())
                .timestamp((dto.getTimestamp() == null || dto.getTimestamp().isBlank())
                        ? Instant.now()
                        : Instant.parse(dto.getTimestamp()))
                .metadata(dto.getMetadata() == null ? Map.of() : dto.getMetadata())
                .traceId((dto.getTraceId() == null || dto.getTraceId().isBlank())
                        ? uuid32()
                        : dto.getTraceId())
                .parentSpanId(dto.getParentSpanId())
                .build();
        repo.batchInsertEvents(List.of(event));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.eventId());
        result.put("agentId", event.agentId());
        result.put("traceId", event.traceId());
        return ResponseEntity.ok(result);
    }

    // ==================== 批量导入 ====================

    /** 单条导入：与批量导入相同的字段/校验/时间格式，一次写入一条事件。
     *  传统 HTTP 接口：POST /api/v1/import/single，body 为 JSON（字段同模板：agent_id/session_id/operation/layer/...）。 */
    @PostMapping("/api/v1/import/single")
    public ResponseEntity<Map<String, Object>> importSingle(
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = new LinkedHashMap<>();
        String err = validate(body);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        MemoryEvent event;
        try {
            event = toEvent(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        repo.batchInsertEvents(List.of(event));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventId", event.eventId());
        result.put("agentId", event.agentId());
        result.put("sessionId", event.sessionId());
        result.put("inserted", 1);
        return ResponseEntity.ok(result);
    }

    /** 批量导入：multipart 上传 file + format(xlsx|json 二选一，可省，按扩展名推断)。 */
    @PostMapping("/api/v1/import")
    public ResponseEntity<Map<String, Object>> importEvents(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "format", required = false) String format) {
        String fmt = (format == null || format.isBlank())
                ? guessFormat(file.getOriginalFilename())
                : format.toLowerCase();
        if (!fmt.equals("xlsx") && !fmt.equals("json")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported format: " + fmt + " (expect xlsx|json)"));
        }

        List<Map<String, Object>> rows;
        try {
            rows = "xlsx".equals(fmt)
                    ? parseXlsx(file.getInputStream())
                    : parseJson(file.getInputStream());
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", "parse failed: " + e.getMessage()));
        }

        List<MemoryEvent> valid = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int total = rows.size();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String err = validate(row);
            if (err != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("row", i + 2); // 第 1 行是表头，数据从第 2 行起
                e.put("message", err);
                errors.add(e);
                continue;
            }
            try {
                valid.add(toEvent(row));
            } catch (Exception ex) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("row", i + 2);
                e.put("message", "invalid cell value: " + ex.getMessage());
                errors.add(e);
            }
        }

        int inserted = 0;
        if (!valid.isEmpty()) {
            repo.batchInsertEvents(valid);
            inserted = valid.size(); // 自然主键去重，重复 event_id 会被 ON CONFLICT 跳过
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", fmt);
        result.put("total", total);
        result.put("inserted", inserted);
        result.put("skipped", total - inserted);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    // ==================== 文件夹 .log 导入（可选大模型格式化） ====================

    /** 文件夹 .log 批量导入：body {fileName, content, llm}，逐个 .log 调用。
     *  llm=true（默认）：把日志内容交给大模型抽取成 memory_events JSON 数组再入库；
     *  llm=false：要求 content 本身就是事件 JSON 数组（复用普通 JSON 解析）。
     *  去重：先计算内容 MD5，命中已导入记录（$MO_HOME/import/log-md5.json）则直接跳过抽取。 */
    @PostMapping("/api/v1/import/logs")
    public ResponseEntity<Map<String, Object>> importLog(
            @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) body = new LinkedHashMap<>();
        String fileName = str(body.get("fileName"));
        String content = str(body.get("content"));
        boolean llm = body.get("llm") == null || Boolean.TRUE.equals(body.get("llm"));
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content is required"));
        }
        return ResponseEntity.ok(processLogContent(fileName, content, llm));
    }

    /** 单个 .log 内容的完整导入管线：MD5 去重 → (LLM|JSON) 解析 → 校验 → 批量入库 → 指纹记录。 */
    private Map<String, Object> processLogContent(String fileName, String content, boolean llm) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fileName", fileName);
        result.put("llm", llm);
        // MD5 去重：内容一致（MD5 相同）的文件不重复抽取，避免重复消耗大模型 token
        String md5;
        try {
            md5 = md5Hex(content);
        } catch (Exception e) {
            result.put("error", "md5 compute failed: " + e.getMessage());
            return result;
        }
        result.put("md5", md5);
        Map<String, Object> prev = repo.findImportedLogByMd5(md5);
        if (prev != null) {
            Map<String, Object> p = new LinkedHashMap<>(prev);
            Object at = prev.get("imported_at");
            p.put("imported_at", at instanceof java.sql.Timestamp t
                    ? t.toInstant().toString() : String.valueOf(at));
            result.put("duplicate", true);
            result.put("previous", p);
            result.put("total", 0);
            result.put("inserted", 0);
            result.put("skipped", 0);
            result.put("errors", List.of());
            return result;
        }
        List<Map<String, Object>> rows;
        try {
            rows = llm ? llmExtractRows(content) : rowsFromJsonText(content);
        } catch (Exception e) {
            result.put("error", (llm ? "llm parse failed: " : "json parse failed: ") + e.getMessage());
            result.put("total", 0);
            result.put("inserted", 0);
            result.put("skipped", 0);
            result.put("errors", List.of());
            return result;
        }
        List<MemoryEvent> valid = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String err = validate(row);
            if (err != null) { errors.add(rowErr(i, err)); continue; }
            try { valid.add(toEvent(row)); }
            catch (Exception ex) { errors.add(rowErr(i, "invalid cell value: " + ex.getMessage())); }
        }
        int inserted = 0;
        if (!valid.isEmpty()) { repo.batchInsertEvents(valid); inserted = valid.size(); }
        result.put("total", rows.size());
        result.put("inserted", inserted);
        result.put("skipped", rows.size() - inserted);
        result.put("errors", errors);
        // 抽取完成，MD5 指纹 + 统计入库，供后续同内容文件去重
        try {
            repo.upsertImportedLog(md5, fileName, llm,
                    rows.size(), inserted, rows.size() - inserted, errors.size());
        } catch (Exception ignore) {
            // 指纹记录失败不影响本次导入结果
        }
        return result;
    }

    /** 文件内容 MD5（十六进制小写）。 */
    private static String md5Hex(String content) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("MD5")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Map<String, Object> rowErr(int idx, String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("row", idx + 1);
        e.put("message", msg);
        return e;
    }

    /** 大模型抽取：把日志文本喂给 dashscope 文本对话模型，要求只输出 memory_events JSON 数组。 */
    private List<Map<String, Object>> llmExtractRows(String content) throws Exception {
        // 取钥优先级：界面配置（GlobalKeyStore）→ 环境变量/兜底文件（props）
        String key = GlobalKeyStore.plain("dashscope");
        if (key == null || key.isBlank()) key = agentProps.getDashscopeApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("未配置大模型 API Key（DashScope），无法用大模型格式化；"
                    + "可在工作区侧边栏「模型密钥」中配置，或在 .env 设置 DASHSCOPE_API_KEY");
        }
        String model = agentProps.getDashscopeModel();
        String sys = "你是 Memory Observatory 的数据导入解析器。请阅读下方 Agent 运行日志，把其中每一个“记忆事件”抽取成一条 JSON 对象，"
                + "最后仅输出一个 JSON 数组（不要任何说明文字、注释或 markdown 代码块标记）。"
                + "每条对象字段（键全小写）："
                + "agent_id(必填,字符串,日志未给出则用“import-default”)、"
                + "session_id(必填,字符串,无则用“default-session”)、"
                + "operation(必填,枚举 read/write/update/expire)、"
                + "layer(必填,枚举 prompt/session/skill/provider,不确定用 provider)、"
                + "memory_key(可空)、memory_summary(可空,压缩到200字内)、"
                + "token_count(整数,默认0)、latency_ms(数字,默认0)、"
                + "timestamp(可空,仅用格式 yyyy-MM-dd HH:mm:ss)、metadata(可空JSON对象)。"
                + "若日志里没有可识别的记忆事件，输出空数组 []。";
        String prompt = sys + "\n---LOG START---\n" + content + "\n---LOG END---";
        String text = chatText(model, key, prompt);
        int s = text.indexOf('[');
        int e = text.lastIndexOf(']');
        if (s >= 0 && e > s) text = text.substring(s, e + 1);
        return rowsFromJsonText(text);
    }

    /** 把一段 JSON 文本解析成事件行（复用普通 JSON 解析：顶层数组 或 {events:[...]}）。 */
    private List<Map<String, Object>> rowsFromJsonText(String text) throws Exception {
        return parseJson(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 原生 OpenAI 兼容文本对话请求（dashscope compatible-mode 端点）。 */
    private String chatText(String model, String key, String userPrompt) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", userPrompt);
        msgs.add(m);
        root.put("messages", msgs);
        root.put("temperature", 0.2);
        String body = mapper.writeValueAsString(root);
        final String endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("大模型接口错误 HTTP " + resp.statusCode() + ": " + truncateOf(resp.body(), 500));
        }
        JsonNode msg = mapper.readTree(resp.body()).path("choices").path(0).path("message");
        String text = msg.path("content").asText("");
        if (!text.isBlank()) return text.strip();
        String rc = msg.path("reasoning_content").asText("");
        return rc.isBlank() ? "[]" : rc.strip();
    }

    private static String truncateOf(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 校验必填字段。 */
    private String validate(Map<String, Object> row) {
        if (blank(row.get("agent_id"))) return "agent_id is required";
        if (blank(row.get("session_id"))) return "session_id is required";
        if (blank(row.get("operation"))) return "operation is required (read/write/update/expire)";
        if (blank(row.get("layer"))) return "layer is required (prompt/session/skill/provider)";
        return null;
    }

    /** 行 → MemoryEvent（时间长、token、latency、metadata 做容错解析）。 */
    private MemoryEvent toEvent(Map<String, Object> row) throws Exception {
        String eventId = defaultBlank(str(row.get("event_id")), uuid16());
        String traceId = defaultBlank(str(row.get("trace_id")), uuid32());
        String parentSpanId = null;
        if (!blank(row.get("parent_span_id"))) parentSpanId = str(row.get("parent_span_id"));

        Map<String, String> meta = parseMetadata(row.get("metadata"));
        meta.put("source", "import");

        return MemoryEvent.builder()
                .eventId(eventId)
                .agentId(str(row.get("agent_id")))
                .sessionId(str(row.get("session_id")))
                .operation(str(row.get("operation")))
                .layer(str(row.get("layer")))
                .memoryKey(str(row.get("memory_key")))
                .memorySummary(str(row.get("memory_summary")))
                .tokenCount(intOf(row.get("token_count"), 0))
                .latencyMs(doubleOf(row.get("latency_ms"), 0.0))
                .timestamp(parseTs(str(row.get("timestamp"))))
                .metadata(meta)
                .traceId(traceId)
                .parentSpanId(parentSpanId)
                .build();
    }

    private Map<String, String> parseMetadata(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        // JSON 导入时 metadata 已反序列化为 Map 对象，直接遍历；
        // xlsx 等字符串形式（JSON 文本单元格）再走 Jackson 解析。
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
            return out;
        }
        String s = str(raw);
        if (s == null || s.isBlank()) return out;
        try {
            Map<String, Object> parsed = mapper.readValue(s, Map.class);
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                out.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
            }
        } catch (Exception ignored) {
            // metadata 解析失败则忽略，仅保留 source 标记
        }
        return out;
    }

    // ==================== 解析 ====================

    private String guessFormat(String filename) {
        if (filename == null) return "xlsx";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".json")) return "json";
        return "xlsx";
    }

    /** 解析 xlsx：首行表头，后续为数据行。 */
    private List<Map<String, Object>> parseXlsx(java.io.InputStream in) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet.getLastRowNum() < 0) return rows;
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;
            List<String> headers = new ArrayList<>();
            for (Cell c : headerRow) headers.add(c.getStringCellValue().toLowerCase());

            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, Object> map = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) continue;
                    String val = cellVal(cell);
                    if (val != null && !val.isBlank()) any = true;
                    map.put(headers.get(c), val);
                }
                // 跳过全空行
                if (!any) continue;
                rows.add(map);
            }
        }
        return rows;
    }

    private String cellVal(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        try {
            String s = cell.getStringCellValue();
            return (s == null || s.isBlank()) ? null : s;
        } catch (Exception e) {
            cell.setCellType(CellType.STRING);
            return cell.getStringCellValue();
        }
    }

    /** 解析 JSON：支持顶层数组 或 {events:[...]} / {rows:[...]}。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(InputStream in) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object root = mapper.readValue(in, Object.class);
        if (root instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) rows.add(normalizeKeys((Map<String, Object>) m));
        } else if (root instanceof Map<?, ?> m) {
            Object data = m.containsKey("events") ? m.get("events") : m.get("rows");
            if (data instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> mm) rows.add(normalizeKeys((Map<String, Object>) mm));
            }
        }
        return rows;
    }

    /** 小写化字段名，便于与 xlsx 结果统一。 */
    private Map<String, Object> normalizeKeys(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey().toLowerCase(), e.getValue());
        return out;
    }

    // ==================== 工具 ====================

    private static boolean blank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String defaultBlank(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static int intOf(Object v, int fallback) {
        if (v == null) return fallback;
        try { return (int) Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static double doubleOf(Object v, double fallback) {
        if (v == null) return fallback;
        try { return Double.parseDouble(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId TS_ZONE = ZoneId.of("Asia/Shanghai");

    /** 导入时间解析：只接受 "yyyy-MM-dd HH:mm:ss"（如 2026-08-22 11:25:50）；空值用当前时间；其他格式抛错走 errors 列表。 */
    private static Instant parseTs(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        try {
            return LocalDateTime.parse(s.trim(), TS_FMT).atZone(TS_ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("timestamp must be 'yyyy-MM-dd HH:mm:ss'");
        }
    }

    private static String uuid16() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String uuid32() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}