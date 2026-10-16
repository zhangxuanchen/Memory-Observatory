package io.memobservatory.server.turn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 接入来源家族归类（统一接入层 mo_ingest 的服务端一侧）。
 *
 * <p>把「某 source 归到哪个家族」从 {@link TurnMaterializer} 的硬编码 {@code CASE WHEN}
 * 挪到 DB 表 {@code event_source_family}：新增平台只需运行时 {@code INSERT} 一行，
 * 不必改 Java、不必重建镜像。
 *
 * <p>归类规则（优先级与旧 SQL 一致）：
 * <ol>
 *   <li>{@code source} 命中清单 → 取清单里的 family（如 {@code qoder_hook → hook}）；</li>
 *   <li>否则若有 {@code turn_user} 或 {@code turn_actions} → {@code workbench}；</li>
 *   <li>否则 → {@code otlp}。</li>
 * </ol>
 *
 * <p>刷新口径：装载带 60s TTL 的惰性刷新——运行时 {@code INSERT} 后最迟一个周期生效，
 * 不需要额外调度线程。
 */
public class SourceFamilyClassifier {

    private static final Logger log = LoggerFactory.getLogger(SourceFamilyClassifier.class);

    public static final String FAMILY_IMPORT = "import";
    public static final String FAMILY_HOOK = "hook";
    public static final String FAMILY_WORKBENCH = "workbench";
    public static final String FAMILY_OTLP = "otlp";

    private static final long TTL_MS = 60_000L;

    /** source → family。{@code null} 的 jdbc 表示测试注入模式，不刷新。 */
    private final JdbcTemplate jdbc;
    private final Map<String, String> families = new HashMap<>();
    private volatile long lastRefreshMs = 0L;

    /** 生产用：从 {@code event_source_family} 装载。 */
    public SourceFamilyClassifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        refresh();
    }

    /** 测试用：直接注入 source→family 映射（零依赖，不碰 DB）。 */
    SourceFamilyClassifier(Map<String, String> seed) {
        this.jdbc = null;
        if (seed != null) {
            families.putAll(seed);
        }
    }

    /**
     * 归类一个 turn。
     *
     * @param source        该 turn 事件的 {@code metadata.source}（可能为 null）
     * @param hasTurnUser   是否有 turn_user
     * @param hasTurnActions 是否有 turn_actions
     */
    public synchronized String classify(String source, boolean hasTurnUser, boolean hasTurnActions) {
        ensureFresh();
        String key = source == null ? "" : source.trim();
        String family = families.get(key);
        if (family != null && !family.isBlank()) {
            return family;
        }
        if (hasTurnUser || hasTurnActions) {
            return FAMILY_WORKBENCH;
        }
        return FAMILY_OTLP;
    }

    // ------------------------------------------------------------------
    // 装载
    // ------------------------------------------------------------------

    private void ensureFresh() {
        if (jdbc == null) {
            return;
        }
        if (System.currentTimeMillis() - lastRefreshMs < TTL_MS) {
            return;
        }
        refresh();
    }

    /** 从 {@code event_source_family} 重新装载清单。异常只 WARN 不抛——后台任务失败不影响主流程。 */
    public synchronized void refresh() {
        if (jdbc == null) {
            return;
        }
        try {
            Map<String, String> loaded = new HashMap<>();
            jdbc.query("SELECT source, family FROM event_source_family",
                    rs -> { loaded.put(rs.getString("source"), rs.getString("family")); });
            families.clear();
            families.putAll(loaded);
            lastRefreshMs = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("event_source_family 装载失败（沿用旧清单）：{}", e.toString());
        }
    }

    /** 当前清单快照（只读，供诊断 / 测试）。 */
    Map<String, String> snapshot() {
        return Map.copyOf(families);
    }
}
