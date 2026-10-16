package io.memobservatory.exoskeleton.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动自检的行为与「清单不漂移」两道断言（架构重构 M0 / 步骤 4）。
 *
 * <p>不起数据库：按本仓惯例子类化 {@link JdbcTemplate}，只把「查到的列」喂进去。
 * 真库场景（先起本服务、后起 mo-server）由 M0 的端到端顺序验证覆盖，不在这里做。
 */
class TurnSchemaGuardTest {

    /** 只回「库里有哪些列 / 有哪些表」，不连库。 */
    private static class FakeJdbc extends JdbcTemplate {
        private final List<String> columns;
        private final boolean criteriaTable;

        FakeJdbc(List<String> columns) {
            this(columns, true);
        }

        FakeJdbc(List<String> columns, boolean criteriaTable) {
            this.columns = columns;
            this.criteriaTable = criteriaTable;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType) {
            if (sql.contains("information_schema.columns")) {
                assertTrue(sql.contains("memory_turns"), "记忆列自检必须针对 memory_turns");
                return (List<T>) (List<?>) columns;
            }
            assertTrue(sql.contains("information_schema.tables"), "判据表自检必须查 information_schema.tables");
            return (List<T>) (criteriaTable ? List.of(TurnSchemaGuard.CRITERIA_TABLE) : List.of());
        }
    }

    private static List<String> fullColumns() {
        List<String> columns = new ArrayList<>(TurnSchemaGuard.REQUIRED_COLUMNS);
        columns.add("trace_id");   // owner 建的基础列，不在自检清单内，顺带混进来
        return columns;
    }

    @Test
    void 表与列齐全时自检通过() {
        // 不抛异常即通过
        new TurnSchemaGuard(new FakeJdbc(fullColumns())).run(null);
    }

    @Test
    void 判据表缺失时启动失败且消息点名该表() {
        // 关掉 spring.sql.init（DB 由 DBA 托管）时，memory_turns 在而判据表可能不在
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TurnSchemaGuard(new FakeJdbc(fullColumns(), false)).run(null));

        assertTrue(e.getMessage().contains(TurnSchemaGuard.CRITERIA_TABLE), e.getMessage());
        assertFalse(e.getMessage().contains("memory_turns 缺少"), "缺的是判据表，不该误报为 memory_turns 缺列");
    }

    @Test
    void 缺列时启动失败且消息点名缺的那列() {
        List<String> columns = fullColumns();
        columns.remove("user_text");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TurnSchemaGuard(new FakeJdbc(columns)).run(null));

        assertTrue(e.getMessage().contains("缺少本模块所需列"), e.getMessage());
        assertTrue(e.getMessage().contains("user_text"), e.getMessage());
        assertFalse(e.getMessage().contains("表不存在"), "表是存在的，不该误报为表不存在");
    }

    @Test
    void 表不存在时消息指明表不存在() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TurnSchemaGuard(new FakeJdbc(List.of())).run(null));

        assertTrue(e.getMessage().contains("表不存在"), e.getMessage());
        // 表都没有时，应把全部必需列都列出来，而不是只说「表不存在」就完事
        assertTrue(e.getMessage().contains("started_at"), e.getMessage());
    }

    @Test
    void 缺失清单保持声明顺序() {
        // 全齐 → 无缺失
        assertEquals(List.of(),
                TurnSchemaGuard.missing(new LinkedHashSet<>(TurnSchemaGuard.REQUIRED_COLUMNS)));

        // 只挖掉两列 → 缺失清单恰好是这两列，且**按声明顺序**排列
        // （先摘 started_at、后摘 user_text，输出里仍是 user_text 在前——顺序来自声明而非入参）
        Set<String> present = new LinkedHashSet<>(TurnSchemaGuard.REQUIRED_COLUMNS);
        present.remove("started_at");
        present.remove("user_text");
        assertEquals(List.of("user_text", "started_at"), TurnSchemaGuard.missing(present));

        // 一列都没有 → 全部缺失
        assertEquals(TurnSchemaGuard.REQUIRED_COLUMNS, TurnSchemaGuard.missing(Set.of()));
    }

    @Test
    void 自检清单被owner的schema覆盖() throws Exception {
        // 该表 DDL 已收敛到 owner（mo-server），故清单须被 owner 的 DDL 覆盖——本模块已不再 ALTER 它。
        String sql = readOwnerSchema();

        Set<String> declared = new LinkedHashSet<>();
        Matcher add = Pattern.compile(
                "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?memory_turns\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        while (add.find()) {
            declared.add(add.group(1).toLowerCase());
        }
        Matcher create = Pattern.compile(
                "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+memory_turns\\s*\\(([\\s\\S]*?)\\n\\);",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        assertTrue(create.find(), "未在 owner 的 schema.sql 解析出 memory_turns 建表语句——正则或文件路径可能变了");
        for (String line : create.group(1).split("\n")) {
            Matcher col = Pattern.compile("^\\s*(\\w+)\\s+").matcher(line);
            if (col.find()) {
                declared.add(col.group(1).toLowerCase());
            }
        }

        assertFalse(declared.isEmpty(), "没从 owner 的 schema.sql 解析出任何 memory_turns 列");
        for (String required : TurnSchemaGuard.REQUIRED_COLUMNS) {
            assertTrue(declared.contains(required.toLowerCase()),
                    "自检要求的列 " + required + " 未出现在 owner（mo-server）的 memory_turns DDL 中："
                            + "改了 DDL 就要同步改 REQUIRED_COLUMNS，否则自检形同虚设");
        }
    }

    @Test
    void 本模块schema不再触碰memory_turns() throws Exception {
        String sql;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/schema.sql"), "schema.sql 应在 classpath 上")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(sql.contains("ALTER TABLE IF EXISTS memory_turns"),
                "该表 DDL 已归 owner（mo-server），本模块不应再 ALTER 它（见 schema.sql 顶部说明）");
        assertFalse(sql.contains("idx_mt_agent_task"),
                "该索引已随 DDL 迁至 owner，本模块不应再建");
    }

    @Test
    void 本模块schema自建判据表() throws Exception {
        // 自检里查的那张表（步骤 15 的落点）必须真在本模块 schema.sql 里建出来——否则自检恒失败。
        String sql;
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/schema.sql"), "schema.sql 应在 classpath 上")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + TurnSchemaGuard.CRITERIA_TABLE),
                "本模块 schema.sql 必须建出 " + TurnSchemaGuard.CRITERIA_TABLE
                        + "（自检会先查它存在，缺则启动即失败）");
    }

    /** 读取 owner（mo-server）的 schema.sql：从工作目录上溯定位仓库根后取兄弟模块文件。 */
    private static String readOwnerSchema() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null
                && !(Files.isDirectory(root.resolve("mo-server")) && Files.isDirectory(root.resolve("mo-exoskeleton")))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("无法定位仓库根（需同时含 mo-server 与 mo-exoskeleton）");
        }
        Path ownerSchema = root.resolve("mo-server/src/main/resources/schema.sql");
        assertTrue(Files.isRegularFile(ownerSchema), "找不到 owner 的 schema.sql：" + ownerSchema);
        return new String(Files.readAllBytes(ownerSchema), StandardCharsets.UTF_8);
    }
}