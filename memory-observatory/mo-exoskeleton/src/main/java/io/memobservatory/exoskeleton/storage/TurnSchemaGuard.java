package io.memobservatory.exoskeleton.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
     * 启动自检：本模块从 memory_turns 读取的列必须真实存在（架构重构 M0 / 步骤 4、M1 / 步骤 7；M3 / 步骤 15 转读校验）。
     *
     * <p><b>为什么需要它</b>——memory_turns 由 owner（mo-server）建表与物化，本模块只<b>读</b>它做分析取数
     * （判据列已于步骤 15 迁出至本模块自建的 memory_turn_criteria，不再回写）。
     * 若 owner 尚未建表 / 尚未建出本模块所需列，本模块的 {@code SELECT ... FROM memory_turns} 会失败，
     * 而进程看起来一直是健康的——故障在取数路径上迟到地爆发，且没有明确指出原因。
     *
     * <p>这个失败模式由<b>启动顺序</b>决定：compose 里本服务只 {@code depends_on} postgres，
     * <b>不</b>依赖 mo-server；库里若还没有 memory_turns，先起来的本服务就会踩中。
     *
     * <p><b>怎么处置</b>——自检不通过就让启动失败，不再静默带病运行。compose 里本服务是
     * {@code restart: unless-stopped}，待 mo-server 建好表后会自动重启成功。即
     * 「早失败（点名缺哪列）+ 自愈」，好过「晚失败（取数时才报）+ 无声」。
     *
     * <p><b>为什么还查 memory_turn_criteria 表存在</b>——判据列（步骤 15）迁出后，本模块的取数与回写
     * 都改走这张自建表。它由本模块的 schema.sql 建，正常情况下随启动初始化到位；但若某环境关掉了
     * {@code spring.sql.init}（DB 由 DBA 托管），memory_turns 可能在而这张表不在——此时取数会迟到地
     * 失败。故与 memory_turns 同一处置：启动时点名，不静默带病运行。
     *
     * <p>自检清单须被 owner 的 DDL 覆盖，由 {@code TurnSchemaGuardTest} 机器比对，
     * 防止「清单要的列、owner 没建」这类漂移。
     */
@Component
public class TurnSchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TurnSchemaGuard.class);

    /**
     * 本模块从 memory_turns 读取的列，须被 owner（mo-server）的 DDL 覆盖。
     * 顺序即 owner 的 schema.sql 书写顺序，便于人读。
     * （判据列已于 M3 / 步骤 15 迁出 memory_turns，故不在此清单内。）
     */
    static final List<String> REQUIRED_COLUMNS = List.of(
            "turn_id",      // 主键，取自 metadata.turn_message_id
            "agent_id",     // Agent 身份
            "session_id",   // 会话粒度（分类、对照组分组按此走）
            "user_text",    // 用户提问原文（分类素材、隐式纠正判定）
            "action_seq",   // 工具序列（分桶 steps / action_seq）
            "io_kind_mix",  // 各 io_kind 事件数（ShapeBucketer 分桶取只读/只写）
            "token_total",  // 成本归因
            "latency_ms",   // 耗时归因
            "outcome",      // metadata.turn_outcome
            "source",       // 事件家族（workbench | otlp | import | hook）
            "started_at");  // 排序（同 session 相邻 turn 判纠正依赖此序）

    /** 只查当前 schema 下的 memory_turns；空结果 = 表不存在（或不可见）。 */
    static final String SQL_COLUMNS = """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'memory_turns'
            """;

    /** 本模块自建的判据表名（步骤 15 迁出的落点）。 */
    static final String CRITERIA_TABLE = "memory_turn_criteria";

    /** 只查当前 schema 下这张判据表是否存在；空结果 = 不存在（或不可见）。 */
    static final String SQL_CRITERIA_TABLE = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = current_schema()
              AND table_name = 'memory_turn_criteria'
            """;

    private final JdbcTemplate jdbc;

    public TurnSchemaGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForList(SQL_CRITERIA_TABLE, String.class).isEmpty()) {
            throw new IllegalStateException("[schema-guard] " + CRITERIA_TABLE + " 表不存在。"
                    + "判据列（M3 / 步骤 15）已迁出 memory_turns、落在这张本模块自建的表上，取数与回写都走它，"
                    + "缺表会让后续所有判据读写失败。请确认本模块 schema.sql 已随启动执行"
                    + "（spring.sql.init 未被关闭），或手工建表后重启本服务。");
        }
        Set<String> actual = new HashSet<>(jdbc.queryForList(SQL_COLUMNS, String.class));
        List<String> missing = missing(actual);
        if (!missing.isEmpty()) {
            String why = actual.isEmpty()
                    ? "memory_turns 表不存在（当前 schema 下查不到任何列）"
                    : "memory_turns 缺少本模块所需列";
            throw new IllegalStateException("[schema-guard] " + why + "：" + missing
                    + "。memory_turns 的 DDL 归 owner（mo-server），本模块只读不写、启动时只做只读校验，"
                    + "带病运行会让后续所有分析取数失败。请确认 mo-server 已建表后重启本服务"
                    + "（compose 的 restart: unless-stopped 会自动重试）。");
        }
        log.info("[schema-guard] memory_turns 读取列自检通过（{} 列）、{} 存在", REQUIRED_COLUMNS.size(), CRITERIA_TABLE);
    }

    /** 纯函数：给定实际存在的列，返回缺失清单（保持 {@link #REQUIRED_COLUMNS} 的顺序）。 */
    static List<String> missing(Set<String> actualColumns) {
        List<String> missing = new ArrayList<>();
        for (String column : REQUIRED_COLUMNS) {
            if (!actualColumns.contains(column)) {
                missing.add(column);
            }
        }
        return missing;
    }
}