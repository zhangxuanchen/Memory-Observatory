package io.memobservatory.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memobservatory.agentloop.workbench.core.AgentProperties;
import io.memobservatory.agentloop.workbench.core.GlobalKeyStore;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请求用量监测：Excel（积分消耗明细）导入 + 异常消耗检测。
 *
 * 数据来源：平台导出的「Usage Details」Excel，列：RequestID / 积分消耗 / User Prompt / 模型 / 客户端 / 时间。
 *
 * 异常规则（阈值可在 application.yml mo.usage 调整）：
 *   large-charge  单条大额：积分 >= large-credits（默认 10）
 *   blank-burn    短提问高扣费：提问字符数 <= short-prompt-len（默认 30）且积分 >= short-burn-credits（默认 2）
 *   duplicate     重复扣费：同 RequestID 出现多条（重试/平台 bug 证据，库中刻意保留重复行）
 *   burst         请求风暴：同一客户端同一分钟内请求数 >= burst-min-count（默认 5）
 *   spike         小时尖峰：某小时消耗 > 小时均值 × spike-ratio（默认 3）且 >= 50
 *
 * 去重策略：request_id 不加唯一约束（重复行是异常证据）；按文件 MD5 指纹防同一文件重复导入。
 */
@RestController
public class UsageController {

    private static final Logger log = LoggerFactory.getLogger(UsageController.class);
    private static final DateTimeFormatter[] TS_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProps;

    /** 六个目标字段的键名，顺序与 int[] 映射下标一致。 */
    private static final String[] FIELD_KEYS = {"requestId", "credits", "prompt", "model", "client", "time"};

    @Value("${mo.usage.large-credits:10}")
    private double largeCredits;
    @Value("${mo.usage.short-prompt-len:30}")
    private int shortPromptLen;
    @Value("${mo.usage.short-burn-credits:2}")
    private double shortBurnCredits;
    @Value("${mo.usage.burst-min-count:5}")
    private int burstMinCount;
    @Value("${mo.usage.spike-ratio:3.0}")
    private double spikeRatio;
    @Value("${mo.usage.spike-min-credits:50}")
    private double spikeMinCredits;

    public UsageController(JdbcTemplate jdbc, ObjectMapper objectMapper, AgentProperties agentProps) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.agentProps = agentProps;
    }

    // ==================== 导入 ====================

    /**
     * 预览用量 Excel：返回表头、前几行样例与自动猜测的列映射，供前端映射弹窗使用。
     * mapping 键为 FIELD_KEYS 六字段，值为列下标（-1 表示未识别）。
     */
    @PostMapping("/api/v1/usage/preview")
    public ResponseEntity<?> previewUsage(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty file"));
        }
        try {
            List<String[]> grid = readGrid(file.getInputStream());
            if (grid.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Excel 为空：未读取到表头行"));
            }
            String[] header = grid.get(0);
            List<List<String>> sample = new ArrayList<>();
            for (int i = 1; i < grid.size() && sample.size() < 3; i++) {
                sample.add(Arrays.asList(grid.get(i)));
            }
            int[] guess = guessMapping(header);
            // 表头自动猜测未覆盖必填列（requestId/credits/time）时，若已配置大模型 Key，则让 AI 辅助识别
            boolean aiUsed = false;
            if (guess[0] < 0 || guess[1] < 0 || guess[5] < 0) {
                int[] ai = aiGuessMapping(header, sample);
                if (ai != null) {
                    for (int i = 0; i < FIELD_KEYS.length; i++) {
                        if (guess[i] >= 0 || ai[i] < 0) continue;
                        boolean claimed = false; // AI 给的列已被其他字段占用时放弃，避免两字段指向同一列
                        for (int j = 0; j < FIELD_KEYS.length; j++) {
                            if (j != i && guess[j] == ai[i]) { claimed = true; break; }
                        }
                        if (!claimed) { guess[i] = ai[i]; aiUsed = true; }
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("headers", Arrays.asList(header));
            out.put("sample", sample);
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < FIELD_KEYS.length; i++) m.put(FIELD_KEYS[i], guess[i]);
            out.put("mapping", m);
            out.put("rows", grid.size() - 1);
            out.put("aiUsed", aiUsed);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("usage preview failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(Map.of("error", "解析失败: " + e.getMessage()));
        }
    }

    /**
     * 导入用量 Excel：multipart 上传 .xlsx。同内容文件（MD5 相同）自动跳过。
     * mapping：可选 JSON，如 {"requestId":0,"credits":1,...}，值为列下标（-1 = 不导入该列）；
     * 不传时按表头自动识别。
     */
    @PostMapping("/api/v1/usage/import")
    public ResponseEntity<?> importUsage(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "mapping", required = false) String mappingJson) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "empty file"));
        }
        try {
            byte[] bytes;
            try (InputStream in = file.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                in.transferTo(out);
                bytes = out.toByteArray();
            }
            String md5 = md5Hex(bytes);

            Integer seen = jdbc.queryForObject(
                    "SELECT count(*) FROM import_usage_files WHERE md5 = ?", Integer.class, md5);
            if (seen != null && seen > 0) {
                return ResponseEntity.ok(Map.of(
                        "skippedFile", true,
                        "message", "该文件已导入过（MD5 相同），已跳过"));
            }

            List<String[]> grid = readGrid(new ByteArrayInputStream(bytes));
            if (grid.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Excel 为空：未读取到表头行"));
            }
            int[] col = (mappingJson == null || mappingJson.isBlank())
                    ? guessMapping(grid.get(0))
                    : parseMapping(mappingJson);
            if (col[0] < 0) return ResponseEntity.badRequest().body(Map.of("error", "未指定 RequestID 对应的列"));
            if (col[1] < 0) return ResponseEntity.badRequest().body(Map.of("error", "未指定积分消耗对应的列"));
            if (col[5] < 0) return ResponseEntity.badRequest().body(Map.of("error", "未指定时间对应的列"));

            List<String[]> errors = new ArrayList<>();
            List<Object[]> batch = new ArrayList<>();
            String fileName = file.getOriginalFilename();
            for (int i = 1; i < grid.size(); i++) { // grid[0] 是表头
                String[] g = grid.get(i);
                String[] r = { // [requestId, credits, prompt, model, client, time]
                        pick(g, col[0]), pick(g, col[1]), pick(g, col[2]),
                        pick(g, col[3]), pick(g, col[4]), normalizeTime(pick(g, col[5]))};
                if ((r[0] == null || r[0].isBlank()) && (r[1] == null || r[1].isBlank())
                        && (r[2] == null || r[2].isBlank())) continue; // 全空行
                String err = validateRow(r);
                if (err != null) {
                    errors.add(new String[]{String.valueOf(i + 1), err}); // Excel 行号：表头占第 1 行
                    continue;
                }
                batch.add(new Object[]{
                        r[0], Double.parseDouble(r[1]), r[2], r[3], r[4], parseTime(r[5]), fileName});
            }

            int inserted = 0;
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(
                        "INSERT INTO request_usage(request_id, credits, prompt, model, client, request_time, file_name) "
                                + "VALUES (?,?,?,?,?,?,?)",
                        batch);
                inserted = batch.size();
            }
            int total = grid.size() - 1;
            int skipped = total - inserted - errors.size();
            jdbc.update("INSERT INTO import_usage_files(md5, file_name, total, inserted, skipped, error_count) "
                            + "VALUES (?,?,?,?,?,?)",
                    md5, fileName, total, inserted, skipped, errors.size());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skippedFile", false);
            result.put("total", total);
            result.put("inserted", inserted);
            result.put("skipped", skipped);
            result.put("errors", errorList(errors));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("usage import failed: {}", e.getMessage());
            return ResponseEntity.status(400).body(Map.of("error", "解析失败: " + e.getMessage()));
        }
    }

    private static List<Map<String, Object>> errorList(List<String[]> errors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] e : errors) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", e[0]);
            m.put("message", e[1]);
            out.add(m);
        }
        return out;
    }

    // ==================== 查询 ====================

    /** KPI + 分布：总消耗 / 请求数 / 均值 / 最大单条 / 时间范围 / 按小时 / 按模型 / 按客户端。 */
    @GetMapping("/api/v1/usage/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> kpi = jdbc.queryForList(
                "SELECT count(*) AS cnt, coalesce(sum(credits),0) AS total_credits, "
                        + "coalesce(avg(credits),0) AS avg_credits, coalesce(max(credits),0) AS max_credits, "
                        + "to_char(min(request_time) AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI') AS first_time, "
                        + "to_char(max(request_time) AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI') AS last_time "
                        + "FROM request_usage");
        out.put("kpi", kpi.isEmpty() ? Map.of() : kpi.get(0));

        out.put("byHour", jdbc.queryForList(
                "SELECT to_char(date_trunc('hour', request_time) AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:00') AS hour, "
                        + "count(*) AS cnt, coalesce(sum(credits),0) AS total_credits "
                        + "FROM request_usage GROUP BY date_trunc('hour', request_time) ORDER BY date_trunc('hour', request_time)"));

        out.put("byModel", jdbc.queryForList(
                "SELECT coalesce(model,'(未知)') AS model, count(*) AS cnt, coalesce(sum(credits),0) AS total_credits "
                        + "FROM request_usage GROUP BY model ORDER BY sum(credits) DESC NULLS LAST"));

        out.put("byClient", jdbc.queryForList(
                "SELECT coalesce(client,'(未知)') AS client, count(*) AS cnt, coalesce(sum(credits),0) AS total_credits "
                        + "FROM request_usage GROUP BY client ORDER BY sum(credits) DESC NULLS LAST"));

        return out;
    }

    /** 异常消耗检测：五类规则命中明细。 */
    @GetMapping("/api/v1/usage/anomalies")
    public Map<String, Object> anomalies() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1) 单条大额
        List<Map<String, Object>> large = queryRows(
                "SELECT request_id AS \"requestId\", credits, model, client, left(coalesce(prompt,''),120) AS \"promptSummary\", "
                        + "to_char(request_time AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI:SS') AS time "
                        + "FROM request_usage WHERE credits >= ? ORDER BY credits DESC LIMIT 50", largeCredits);
        out.put("large", large);

        // 2) 短提问高扣费
        List<Map<String, Object>> blankBurn = queryRows(
                "SELECT request_id AS \"requestId\", credits, model, client, left(coalesce(prompt,''),120) AS \"promptSummary\", "
                        + "char_length(btrim(coalesce(prompt,''))) AS \"promptLen\", "
                        + "to_char(request_time AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI:SS') AS time "
                        + "FROM request_usage WHERE char_length(btrim(coalesce(prompt,''))) <= ? AND credits >= ? "
                        + "ORDER BY credits DESC LIMIT 50", shortPromptLen, shortBurnCredits);
        out.put("blankBurn", blankBurn);

        // 3) 重复扣费（同 RequestID 多条）
        List<Map<String, Object>> duplicates = jdbc.queryForList(
                "SELECT request_id AS \"requestId\", count(*) AS cnt, coalesce(sum(credits),0) AS \"totalCredits\", "
                        + "to_char(min(request_time) AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI:SS') AS \"firstTime\", "
                        + "to_char(max(request_time) AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI:SS') AS \"lastTime\" "
                        + "FROM request_usage GROUP BY request_id HAVING count(*) > 1 "
                        + "ORDER BY sum(credits) DESC LIMIT 50");
        out.put("duplicates", duplicates);

        // 4) 请求风暴（同客户端同分钟）
        List<Map<String, Object>> bursts = jdbc.queryForList(
                "SELECT coalesce(client,'(未知)') AS client, "
                        + "to_char(date_trunc('minute', request_time) AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI') AS minute, "
                        + "count(*) AS cnt, coalesce(sum(credits),0) AS \"totalCredits\" "
                        + "FROM request_usage GROUP BY 1, date_trunc('minute', request_time) HAVING count(*) >= ? "
                        + "ORDER BY count(*) DESC LIMIT 50", burstMinCount);
        out.put("bursts", bursts);

        // 5) 小时尖峰：小时聚合后与均值比较（Java 内过滤）
        List<Map<String, Object>> hourRows = jdbc.queryForList(
                "SELECT to_char(date_trunc('hour', request_time) AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:00') AS hour, "
                        + "count(*) AS cnt, coalesce(sum(credits),0) AS \"totalCredits\" "
                        + "FROM request_usage GROUP BY date_trunc('hour', request_time) ORDER BY date_trunc('hour', request_time)");
        double avgHour = 0;
        if (!hourRows.isEmpty()) {
            double sum = 0;
            for (Map<String, Object> r : hourRows) sum = sum + ((Number) r.get("totalCredits")).doubleValue();
            avgHour = sum / hourRows.size();
        }
        List<Map<String, Object>> spikes = new ArrayList<>();
        for (Map<String, Object> r : hourRows) {
            double total = ((Number) r.get("totalCredits")).doubleValue();
            if (avgHour > 0 && total > avgHour * spikeRatio && total >= spikeMinCredits) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                m.put("avgHourCredits", Math.round(avgHour * 100.0) / 100.0);
                m.put("ratio", Math.round(total / avgHour * 10.0) / 10.0);
                spikes.add(m);
            }
        }
        out.put("spikes", spikes);

        int total = large.size() + blankBurn.size() + duplicates.size() + bursts.size() + spikes.size();
        out.put("total", total);
        return out;
    }

    /** 最近用量明细（表格展示）。 */
    @GetMapping("/api/v1/usage/records")
    public Map<String, Object> records(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        List<Map<String, Object>> rows = queryRows(
                "SELECT request_id AS \"requestId\", credits, model, client, left(coalesce(prompt,''),200) AS \"promptSummary\", "
                        + "to_char(request_time AT TIME ZONE 'Asia/Shanghai','MM-DD HH24:MI:SS') AS time "
                        + "FROM request_usage ORDER BY request_time DESC LIMIT " + n);
        Integer cnt = jdbc.queryForObject("SELECT count(*) FROM request_usage", Integer.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", cnt == null ? 0 : cnt);
        out.put("rows", rows);
        return out;
    }

    // ==================== 解析 ====================

    /** 读整张 sheet 为网格（含表头行），每个单元格转为字符串；列数取所有行的最大值。 */
    private List<String[]> readGrid(InputStream in) throws Exception {
        List<String[]> grid = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            if (sheet.getRow(0) == null && lastRow == 0) return grid;
            int maxCols = 0;
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row != null) maxCols = Math.max(maxCols, row.getLastCellNum());
            }
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                String[] cells = new String[maxCols];
                if (row != null) {
                    for (int c = 0; c < maxCols; c++) cells[c] = cellText(row.getCell(c));
                }
                grid.add(cells);
            }
        }
        return grid;
    }

    /** 读单元格字符串；数值列保持原样（2.58 → "2.58"），整数值去尾零（12345 → "12345"），日期型转标准时间串。 */
    private static String cellText(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.STRING) {
            String s = cell.getStringCellValue();
            return (s == null || s.isBlank()) ? null : s.trim();
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant()
                        .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (type == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        if (type == CellType.FORMULA) {
            try {
                String s = cell.getStringCellValue();
                return (s == null || s.isBlank()) ? null : s.trim();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 表头自动猜测列映射（宽容匹配中英文，忽略大小写与空白）。返回与 FIELD_KEYS 顺序一致的下标，未识别记 -1。 */
    private static int[] guessMapping(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            String h = header[i];
            if (h != null && !h.isBlank()) idx.putIfAbsent(h.toLowerCase().replaceAll("\\s+", ""), i);
        }
        return new int[]{
                idx.getOrDefault("requestid", idx.getOrDefault("request_id", -1)),
                idx.getOrDefault("credits", idx.getOrDefault("积分消耗", idx.getOrDefault("积分", -1))),
                idx.getOrDefault("prompt", idx.getOrDefault("userprompt", -1)),
                idx.getOrDefault("model", idx.getOrDefault("模型", -1)),
                idx.getOrDefault("client", idx.getOrDefault("客户端", -1)),
                idx.getOrDefault("time", idx.getOrDefault("请求时间", idx.getOrDefault("时间", -1)))
        };
    }

    /**
     * AI 辅助识别列映射：表头自动猜测未覆盖必填列时，把表头与样例行交给大模型，让 AI 推断每列含义。
     * 未配置 DashScope Key、调用失败或输出不可解析时返回 null（调用方降级为表头猜测结果）。
     * 返回值与 FIELD_KEYS 顺序一致，未识别字段记 -1。
     */
    private int[] aiGuessMapping(String[] header, List<List<String>> sample) {
        try {
            // 取钥优先级：界面配置（GlobalKeyStore）→ 环境变量/兜底文件（agentProps），与用量大模型格式化一致
            String key = GlobalKeyStore.plain("dashscope");
            if (key == null || key.isBlank()) key = agentProps.getDashscopeApiKey();
            if (key == null || key.isBlank()) return null;
            String model = agentProps.getDashscopeModel();

            String sys = "你是 Excel 列映射识别助手。用户会给出一份用量明细表的表头和几行样例数据，"
                    + "表格对应以下六个字段：requestId(请求唯一编号/流水号)、credits(积分消耗/费用数值)、"
                    + "prompt(用户提问/输入内容等长文本)、model(模型名称)、client(客户端/来源/应用)、time(请求发生时间)。"
                    + "请判断表头中每一列代表哪个字段，严格只输出一个 JSON 对象（不要任何说明文字、注释或 markdown 代码块标记），"
                    + "键为 requestId/credits/prompt/model/client/time，值为该字段对应的列下标（从 0 开始的整数）；"
                    + "某字段在表中不存在时值为 -1。请结合样例内容特征判断（如时间格式、纯数字编号、长文本提问等）。";
            StringBuilder user = new StringBuilder("表头：").append(objectMapper.writeValueAsString(header)).append('\n');
            user.append("样例行：\n");
            for (List<String> r : sample) user.append(objectMapper.writeValueAsString(r)).append('\n');

            String text = llmChat(model, key, sys, user.toString());
            int s = text.indexOf('{');
            int e = text.lastIndexOf('}');
            if (s < 0 || e <= s) return null;
            JsonNode n = objectMapper.readTree(text.substring(s, e + 1));
            int[] ai = new int[FIELD_KEYS.length];
            boolean anyHit = false;
            for (int i = 0; i < FIELD_KEYS.length; i++) {
                int v = n.path(FIELD_KEYS[i]).asInt(-1);
                ai[i] = (v >= -1 && v < header.length) ? v : -1;
                if (ai[i] >= 0) anyHit = true;
            }
            // 至少命中一个必填列才算有效，否则当作识别失败
            if (!anyHit || (ai[0] < 0 && ai[1] < 0 && ai[5] < 0)) return null;
            return ai;
        } catch (Exception ex) {
            log.info("usage AI column guess skipped: {}", ex.getMessage());
            return null;
        }
    }

    /** 大模型文本对话请求（dashscope compatible-mode 端点，OpenAI 兼容协议）。 */
    private String llmChat(String model, String key, String sys, String userPrompt) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("role", "system");
        sm.put("content", sys);
        msgs.add(sm);
        Map<String, Object> um = new LinkedHashMap<>();
        um.put("role", "user");
        um.put("content", userPrompt);
        msgs.add(um);
        root.put("messages", msgs);
        root.put("temperature", 0);
        String body = objectMapper.writeValueAsString(root);
        final String endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("大模型接口错误 HTTP " + resp.statusCode());
        }
        JsonNode msg = objectMapper.readTree(resp.body()).path("choices").path(0).path("message");
        String text = msg.path("content").asText("");
        if (text.isBlank()) text = msg.path("reasoning_content").asText("");
        return text.strip();
    }

    /** 解析前端传来的列映射 JSON（如 {"requestId":0,"credits":1,...}）为与 FIELD_KEYS 顺序一致的 int[]。 */
    private int[] parseMapping(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            int[] col = new int[FIELD_KEYS.length];
            for (int i = 0; i < FIELD_KEYS.length; i++) {
                JsonNode v = n.get(FIELD_KEYS[i]);
                col[i] = (v != null && v.isNumber()) ? v.asInt(-1) : -1;
            }
            return col;
        } catch (Exception e) {
            throw new IllegalArgumentException("mapping 参数格式错误: " + e.getMessage());
        }
    }

    /** 从网格行中取指定列；列越界/未指定/空值返回 null。 */
    private static String pick(String[] row, int colIdx) {
        if (colIdx < 0 || colIdx >= row.length) return null;
        String s = row[colIdx];
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 时间字符串按常见格式归一化为 Timestamp 字符串；解析失败返回 null（由校验报错）。 */
    private static String normalizeTime(String s) {
        if (s == null) return null;
        String t = s.trim();
        for (DateTimeFormatter f : TS_FORMATS) {
            try {
                return Timestamp.valueOf(LocalDateTime.parse(t, f)).toString();
            } catch (Exception ignored) {
                // 试下一个格式
            }
        }
        return null;
    }

    private static String validateRow(String[] r) {
        if (r[0] == null || r[0].isBlank()) return "RequestID 为空";
        if (r[1] == null || r[1].isBlank()) return "积分消耗为空";
        try {
            Double.parseDouble(r[1]);
        } catch (NumberFormatException e) {
            return "积分消耗不是数字: " + r[1];
        }
        if (r[5] == null) return "时间无法解析";
        return null;
    }

    private static Timestamp parseTime(String s) {
        return Timestamp.valueOf(s); // normalizeTime 已归一化为 Timestamp.toString()
    }

    private List<Map<String, Object>> queryRows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private static String md5Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }
}
