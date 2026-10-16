package io.memobservatory.server.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Qoder（含 CN 版）实时 Hook 的「一键接入」服务端一侧：扫描本机接入状态 + 幂等安装。
 *
 * <p>依赖一个事实：docker-compose 默认把宿主 {@code $HOME} 同路径挂进 mo-server 容器
 * （见 docker-compose.yml 的 volumes 与 {@code HOME}/{@code -Duser.home}），
 * 故容器内可直接读写 {@code ~/.qoder(-cn)/settings.json} 与 {@code ~/.qoder/hooks/}。
 * Qoder IDE 在宿主上读取的正是这些文件，写它 = 改宿主配置。
 *
 * <p>安装动作与 {@code examples/QODER-HOOK-SETUP.md} 的脚本逐字对齐、且幂等：
 * 拷 {@code mo_ingest} 整包 → 生成 {@code mo-report.sh} 包装器 → 落 {@code .mo_env}（含 key，0600）→
 * 合并 hooks 块进已存在的 settings.json（保留 enabledPlugins 等其它键，绝不新建未安装的变体）。
 */
@Service
public class QoderHookService {

    private static final Logger log = LoggerFactory.getLogger(QoderHookService.class);

    /** Hook 包装脚本命令（写进 settings.json，宿主 Qoder 用 ~ 展开执行）。 */
    private static final String CMD = "~/.qoder/hooks/mo-report.sh";
    /** 上报来源标记，自检按它在库里数近期事件。 */
    private static final String SOURCE = "qoder_hook";
    /** 目标 hook 事件 → 是否需要 matcher="*"。 */
    private static final Map<String, Boolean> TARGET_EVENTS = new LinkedHashMap<>();
    static {
        TARGET_EVENTS.put("UserPromptSubmit", false);
        TARGET_EVENTS.put("PostToolUse", true);
        TARGET_EVENTS.put("PostToolUseFailure", true);
        TARGET_EVENTS.put("Stop", false);
    }

    /** 实时流可过滤的层级维度 → metadata 里的实际键（白名单，防列名注入）。 */
    private static final Map<String, String> SCOPE_COLUMNS = Map.of(
            "project", "project",
            "workspace", "workspace_name",
            "task", "task_id");

    private static final String WRAPPER_SH = """
            #!/bin/bash
            # Memory Observatory · Qoder hook 包装器（由「一键接入」自动生成，勿手改；改动走 QODER-HOOK-SETUP.md）
            HOOK_DIR="$HOME/.qoder/hooks"
            # API key 从 .mo_env 读（若存在）。set -a 让其中赋值自动 export 给子进程。
            if [ -f "$HOOK_DIR/.mo_env" ]; then
              set -a
              . "$HOOK_DIR/.mo_env"
              set +a
            fi
            export MO_OTLP_ENDPOINT="${MO_OTLP_ENDPOINT:-http://localhost:4318/v1/traces}"
            export MO_AGENT_ID="${MO_AGENT_ID:-qoder}"
            export PYTHONPATH="$HOOK_DIR"
            exec /usr/bin/python3 -m mo_ingest --platform qoder --mode hook
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${MO_API_KEY:}")
    private String apiKey;

    @Value("${MO_OTLP_ENDPOINT:http://localhost:4318/v1/traces}")
    private String otlpEndpoint;

    /** 镜像内 mo_ingest 源目录（Dockerfile COPY 进来）；可用 env 覆盖以便本机直跑测试。 */
    @Value("${MO_INGEST_SRC:/app/mo_ingest}")
    private String moIngestSrc;

    public QoderHookService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 本机 Qoder 接入状态：settings.json 注册情况 + 脚本/key + 近期入库事件。 */
    public Map<String, Object> status(int windowMinutes) {
        Path home = home();
        List<Map<String, Object>> files = new ArrayList<>();
        boolean anyRegistered = false;
        for (String variant : List.of(".qoder", ".qoder-cn")) {
            Path settings = home.resolve(variant).resolve("settings.json");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("variant", variant);
            f.put("path", settings.toString());
            boolean exists = Files.isRegularFile(settings);
            f.put("exists", exists);
            List<String> registered = exists ? registeredEvents(settings) : List.of();
            f.put("registeredEvents", registered);
            f.put("hookRegistered", !registered.isEmpty());
            if (!registered.isEmpty()) {
                anyRegistered = true;
            }
            files.add(f);
        }
        Path script = home.resolve(".qoder/hooks/mo-report.sh");
        Path moEnv = home.resolve(".qoder/hooks/.mo_env");
        boolean scriptPresent = Files.isRegularFile(script);
        boolean moEnvPresent = Files.isRegularFile(moEnv);

        Map<String, Object> recent = recentEvents(Math.max(1, windowMinutes));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("home", home.toString());
        out.put("source", SOURCE);
        out.put("files", files);
        out.put("scriptPresent", scriptPresent);
        out.put("moEnvPresent", moEnvPresent);
        out.put("keyConfigured", moEnvPresent && containsApiKey(moEnv));
        out.put("installed", scriptPresent && anyRegistered);
        out.put("recent", recent);
        return out;
    }

    /** 该 settings.json 里已注册 mo-report.sh 的事件名。解析失败按未注册处理。 */
    @SuppressWarnings("unchecked")
    private List<String> registeredEvents(Path settings) {
        List<String> hit = new ArrayList<>();
        try {
            Map<String, Object> cfg = mapper.readValue(Files.readString(settings), Map.class);
            Object hooksObj = cfg.get("hooks");
            if (!(hooksObj instanceof Map)) {
                return hit;
            }
            Map<String, Object> hooks = (Map<String, Object>) hooksObj;
            for (Map.Entry<String, Object> e : hooks.entrySet()) {
                if (groupListHasCommand(e.getValue())) {
                    hit.add(e.getKey());
                }
            }
        } catch (Exception ex) {
            log.warn("解析 settings.json 失败 {}: {}", settings, ex.toString());
        }
        return hit;
    }

    @SuppressWarnings("unchecked")
    private boolean groupListHasCommand(Object value) {
        if (!(value instanceof List)) {
            return false;
        }
        for (Object g : (List<Object>) value) {
            if (!(g instanceof Map)) {
                continue;
            }
            Object inner = ((Map<String, Object>) g).get("hooks");
            if (!(inner instanceof List)) {
                continue;
            }
            for (Object h : (List<Object>) inner) {
                if (h instanceof Map && CMD.equals(((Map<String, Object>) h).get("command"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Object> recentEvents(int windowMinutes) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT count(*)::int AS n, max(ts) AS last_ts FROM memory_events "
                            + "WHERE metadata->>'source' = ? AND ts >= now() - make_interval(mins => ?)",
                    SOURCE, windowMinutes);
            Object n = row.get("n");
            Object last = row.get("last_ts");
            m.put("windowMinutes", windowMinutes);
            m.put("count", n == null ? 0 : n);
            m.put("lastTs", last == null ? null : last.toString());
        } catch (Exception e) {
            log.warn("近期事件计数失败: {}", e.toString());
            m.put("windowMinutes", windowMinutes);
            m.put("count", 0);
            m.put("lastTs", null);
            m.put("error", e.getMessage());
        }
        return m;
    }

    /** 实时事件流：最近 limit 条 qoder_hook 事件，按时间升序（旧→新）返回，供前端滚动展示。
     *  ts 以 epoch 毫秒回传，前端按浏览器本地时区格式化，避免服务端/容器时区歧义。
     *  scope 为层级过滤维度（project/workspace/task），配合 value 只取该归属下的事件；不传即全局。 */
    public List<Map<String, Object>> feed(int limit, String scope, String value) {
        int n = Math.max(1, Math.min(limit, 50));
        String col = SCOPE_COLUMNS.get(scope == null ? "" : scope);
        boolean filter = col != null && value != null && !value.isBlank();
        boolean escape = "escape".equals(scope);
        List<Object> args = new ArrayList<>();
        args.add(SOURCE);
        String where = "WHERE metadata->>'source' = ?";
        if (filter) {
            where += " AND metadata->>'" + col + "' = ?";   // col 来自白名单常量，非用户输入
            args.add(value);
        }
        if (escape) {   // 越界：cwd(project_path) 既不等于工作区根、也不在其子路径下
            where += " AND metadata->>'workspace' IS NOT NULL AND metadata->>'project_path' IS NOT NULL"
                    + " AND metadata->>'project_path' <> metadata->>'workspace'"
                    + " AND metadata->>'project_path' NOT LIKE metadata->>'workspace' || '/%'";
        }
        args.add(n);
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT extract(epoch FROM ts) * 1000 AS ts_ms, agent_id, operation, layer, "
                            + "memory_summary, metadata->>'io_kind' AS io_kind, "
                            + "metadata->>'project' AS project, metadata->>'workspace_name' AS workspace, "
                            + "metadata->>'project_path' AS project_path, metadata->>'workspace' AS workspace_path, "
                            + "metadata->>'task_id' AS task FROM memory_events "
                            + where + " ORDER BY ts DESC LIMIT ?",
                    args.toArray());
        } catch (Exception e) {
            log.warn("feed 查询失败: {}", e.toString());
            return out;
        }
        for (int k = rows.size() - 1; k >= 0; k--) {   // 查询取最新 N 条，反转为时间升序
            Map<String, Object> r = rows.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            Object ms = r.get("ts_ms");
            m.put("ts", ms == null ? null : ((Number) ms).longValue());
            m.put("agent", r.get("agent_id"));
            m.put("op", r.get("operation"));
            m.put("layer", r.get("layer"));
            m.put("summary", r.get("memory_summary"));
            m.put("ioKind", r.get("io_kind"));
            m.put("project", r.get("project"));
            m.put("workspace", r.get("workspace"));
            m.put("task", r.get("task"));
            String pp = r.get("project_path") == null ? null : r.get("project_path").toString();
            String wp = r.get("workspace_path") == null ? null : r.get("workspace_path").toString();
            m.put("projectPath", pp);
            m.put("workspacePath", wp);
            m.put("escaped", isEscaped(pp, wp));
            out.add(m);
        }
        return out;
    }

    /** 越界判定：cwd 既不等于工作区根、也不在其子路径下（两者都非空才判，缺任一则不算）。 */
    private static boolean isEscaped(String projectPath, String workspacePath) {
        if (projectPath == null || projectPath.isBlank() || workspacePath == null || workspacePath.isBlank()) {
            return false;
        }
        String base = workspacePath.endsWith("/") ? workspacePath : workspacePath + "/";
        return !(projectPath.equals(workspacePath) || projectPath.startsWith(base));
    }

    /** 层级清单：近 window 分钟内出现过的 project / workspace（按事件数倒序），供实时流过滤下拉。 */
    public Map<String, Object> scopes(int windowMinutes) {
        int w = Math.max(1, Math.min(windowMinutes, 1440));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projects", scopeList("project", w));
        out.put("workspaces", scopeList("workspace_name", w));
        out.put("tasks", taskList(w));
        return out;
    }

    /** 任务清单：近 window 分钟内的 session，额外带上所属项目与首条提问，
     *  供前端把裸 UUID 变成可辨认的“提问片段 · 项目”。 */
    private List<Map<String, Object>> taskList(int windowMinutes) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT metadata->>'task_id' AS name, count(*)::int AS n, "
                            + "(array_agg(metadata->>'project' ORDER BY ts) FILTER (WHERE metadata->>'project' IS NOT NULL))[1] AS project, "
                            + "(array_agg(metadata->>'turn_user' ORDER BY ts) FILTER (WHERE metadata->>'turn_user' IS NOT NULL))[1] AS prompt "
                            + "FROM memory_events "
                            + "WHERE metadata->>'source' = ? AND metadata->>'task_id' IS NOT NULL "
                            + "AND ts >= now() - make_interval(mins => ?) "
                            + "GROUP BY metadata->>'task_id' ORDER BY count(*) DESC LIMIT 50",
                    SOURCE, windowMinutes);
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.get("name"));
                m.put("count", r.get("n"));
                m.put("project", r.get("project"));
                m.put("prompt", r.get("prompt"));
                list.add(m);
            }
        } catch (Exception e) {
            log.warn("tasks 查询失败: {}", e.toString());
        }
        return list;
    }

    private List<Map<String, Object>> scopeList(String key, int windowMinutes) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT metadata->>? AS name, count(*)::int AS n FROM memory_events "
                            + "WHERE metadata->>'source' = ? AND metadata->>? IS NOT NULL "
                            + "AND ts >= now() - make_interval(mins => ?) GROUP BY 1 ORDER BY 2 DESC LIMIT 50",
                    key, SOURCE, key, windowMinutes);
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", r.get("name"));
                m.put("count", r.get("n"));
                list.add(m);
            }
        } catch (Exception e) {
            log.warn("scope({}) 查询失败: {}", key, e.toString());
        }
        return list;
    }

    /** 当前 Agent（source=qoder_hook）的全部历史记录条数，供实时流头部展示总量。 */
    public long total() {
        try {
            Long c = jdbc.queryForObject(
                    "SELECT count(*) FROM memory_events WHERE metadata->>'source' = ?",
                    Long.class, SOURCE);
            return c == null ? 0L : c;
        } catch (Exception e) {
            log.warn("total 查询失败: {}", e.toString());
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 安装（幂等）
    // ------------------------------------------------------------------

    /** 一键安装：拷包 + 生成脚本 + 落 key + 合并 hooks。返回本次改了什么。 */
    public Map<String, Object> install() throws IOException {
        Path home = home();
        Path hooksDir = home.resolve(".qoder/hooks");
        List<String> changed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        Files.createDirectories(hooksDir);

        // 1) mo_ingest 整包
        copyMoIngest(hooksDir, changed, skipped);

        // 2) 包装脚本
        Path script = hooksDir.resolve("mo-report.sh");
        String wantScript = WRAPPER_SH;
        if (Files.isRegularFile(script) && wantScript.equals(Files.readString(script))) {
            skipped.add("mo-report.sh（已存在且一致）");
        } else {
            Files.writeString(script, wantScript);
            chmod(script, "rwxr-xr-x");
            changed.add("写入 mo-report.sh");
        }

        // 3) .mo_env（含 key，0600）
        writeMoEnv(hooksDir, changed, skipped);

        // 4) 合并 hooks 进「已存在」的 settings.json（不给未安装的变体凭空建文件）
        for (String variant : List.of(".qoder", ".qoder-cn")) {
            Path settings = home.resolve(variant).resolve("settings.json");
            if (!Files.isRegularFile(settings)) {
                skipped.add(variant + "/settings.json（未安装该变体，跳过）");
                continue;
            }
            if (mergeHooks(settings, variant, changed)) {
                // changed 已在 mergeHooks 内追加
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        out.put("skipped", skipped);
        out.put("status", status(30));
        return out;
    }

    private void copyMoIngest(Path hooksDir, List<String> changed, List<String> skipped) throws IOException {
        Path src = Paths.get(moIngestSrc);
        if (!Files.isDirectory(src)) {
            throw new IOException("镜像内未找到 mo_ingest 源（" + src + "）；请确认 mo-server 镜像已 COPY mo_ingest。");
        }
        Path dst = hooksDir.resolve("mo_ingest");
        deleteRecursively(dst);
        copyRecursively(src, dst);
        // 清掉 __pycache__，避免把构建期字节码带过去
        try (Stream<Path> walk = Files.walk(dst)) {
            walk.filter(p -> p.getFileName().toString().equals("__pycache__"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { deleteRecursively(p); } catch (IOException ignore) {} });
        }
        changed.add("拷贝 mo_ingest 整包 → " + dst);
    }

    private void writeMoEnv(Path hooksDir, List<String> changed, List<String> skipped) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            skipped.add(".mo_env（服务端 MO_API_KEY 为空，未写 key——开放模式可留空）");
            return;
        }
        Path moEnv = hooksDir.resolve(".mo_env");
        String want = "MO_API_KEY=" + apiKey + "\n"
                + "MO_OTLP_ENDPOINT=" + otlpEndpoint + "\n";
        if (Files.isRegularFile(moEnv) && want.equals(Files.readString(moEnv))) {
            skipped.add(".mo_env（已存在且一致）");
            return;
        }
        Files.writeString(moEnv, want);
        chmod(moEnv, "rw-------");
        changed.add("写入 .mo_env（含 API key，0600）");
    }

    /** 幂等合并 hooks 块；确有新增返回 true。保留其它顶层键与既有分组。 */
    @SuppressWarnings("unchecked")
    private boolean mergeHooks(Path settings, String variant, List<String> changed) throws IOException {
        Map<String, Object> cfg;
        try {
            cfg = mapper.readValue(Files.readString(settings), LinkedHashMap.class);
        } catch (Exception e) {
            throw new IOException(variant + "/settings.json 不是合法 JSON，拒绝改写：" + e.getMessage());
        }
        Object hooksObj = cfg.get("hooks");
        Map<String, Object> hooks = (hooksObj instanceof Map)
                ? (Map<String, Object>) hooksObj : new LinkedHashMap<>();
        cfg.put("hooks", hooks);

        List<String> added = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : TARGET_EVENTS.entrySet()) {
            String ev = e.getKey();
            Object curObj = hooks.get(ev);
            List<Object> groups = (curObj instanceof List) ? (List<Object>) curObj : new ArrayList<>();
            if (!groupListHasCommand(groups)) {
                groups.add(newGroup(e.getValue()));
                added.add(ev);
            }
            hooks.put(ev, groups);
        }
        if (added.isEmpty()) {
            return false;
        }
        Files.writeString(settings,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg) + "\n");
        changed.add(variant + "/settings.json 合并 hooks：" + String.join("/", added));
        return true;
    }

    private Map<String, Object> newGroup(boolean withMatcher) {
        Map<String, Object> handler = new LinkedHashMap<>();
        handler.put("type", "command");
        handler.put("command", CMD);
        handler.put("timeout", 10);
        Map<String, Object> group = new LinkedHashMap<>();
        if (withMatcher) {
            group.put("matcher", "*");
        }
        group.put("hooks", new ArrayList<>(List.of(handler)));
        return group;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Path home() {
        String h = System.getenv("HOME");
        if (h == null || h.isBlank()) {
            h = System.getProperty("user.home");
        }
        return Paths.get(h == null || h.isBlank() ? "/root" : h);
    }

    private boolean containsApiKey(Path moEnv) {
        try {
            return Files.readString(moEnv).contains("MO_API_KEY=");
        } catch (IOException e) {
            return false;
        }
    }

    private void chmod(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (Exception e) {
            log.warn("chmod {} 失败: {}", p, e.toString());
        }
    }

    private void copyRecursively(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path s : (Iterable<Path>) walk::iterator) {
                Path d = dst.resolve(src.relativize(s).toString());
                if (Files.isDirectory(s)) {
                    Files.createDirectories(d);
                } else {
                    Files.createDirectories(d.getParent());
                    Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 供状态展示的时间戳格式化占位（保留给控制器按需使用）。 */
    static String isoNow() {
        return Instant.now().toString();
    }
}
