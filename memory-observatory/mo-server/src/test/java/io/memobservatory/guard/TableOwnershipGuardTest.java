package io.memobservatory.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3 · 表所有权守则（对应架构重构 R1 / R2 / 文档 §4.4、§4.6）。
 *
 * <p>目标形态：<b>一张表只有一个 writer</b>。本守则跨模块扫描 mo-server 与 mo-exoskeleton
 * 的 {@code src/main/java}，找出「非 owner 模块写了某张受管表」的违例。
 *
 * <p><b>为什么要在意</b>：受管表由两个独立进程共用。非 owner 去写它，等于把数据正确性
 * 押在两边启动时序与列结构默契上，且没有任何编译期保护（这是全项目最重的耦合点）。
 *
 * <p><b>棘轮式（ratchet）</b>：{@link #KNOWN_VIOLATIONS} 登记「非 owner 模块 → 表 → 写语句条数」，
 * 断言「实际 == 白名单」：
 * <ul>
 *   <li>新增写点 → 计数变大 → 红；</li>
 *   <li>删掉一个写点却没更新白名单 → 计数变小 → 红。</li>
 * </ul>
 * 步骤 15（判据列迁出 memory_turns）完成后，本白名单应删空。
 */
class TableOwnershipGuardTest {

    /** 受管表 → 唯一 owner 模块（建表 + 唯一可写方）。对齐文档 §4.4 表所有权矩阵。 */
    private static final Map<String, String> OWNER = new LinkedHashMap<>();

    static {
        OWNER.put("memory_events", "mo-server");
        OWNER.put("memory_turns", "mo-server");
        OWNER.put("memory_rules", "mo-exoskeleton");
        // M3 步骤 15：装载事实只产生在 mo-server（只有它读技能库并装配 Agent），DDL 已收敛到 mo-server，
        // owner 由「外骨骼建表 / mo-server 直写」改为 mo-server。
        OWNER.put("memory_skill_loads", "mo-server");
        // 判据列迁出 memory_turns 后，判据侧写入的是这张外骨骼自有表（以 turn_id 关联）。
        OWNER.put("memory_turn_criteria", "mo-exoskeleton");
    }

    /**
     * 已知违例（实测）：非 owner 模块 → 表 → 该模块内写该表的语句条数。棘轮：只减不增。
     *
     * <p><b>M3 步骤 15 已清零</b>：原先「mo-exoskeleton → memory_turns」的 4 条写点随判据列迁出而消失；
     * 「mo-server → memory_skill_loads」的 1 条写点随 owner 改判 mo-server 而不再算违例。
     * 此后任何新增的跨 owner 写表都会直接变红——这正是「一张表只有一个 writer」的兜底。
     */
    private static final Map<String, Integer> KNOWN_VIOLATIONS = Map.of();

    private static final List<String> MODULES =
            List.of("mo-server", "mo-workbench", "mo-support", "mo-exoskeleton");

    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+(memory_\\w+)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("非 owner 写表违例恰为白名单内条目（棘轮：只减不增）")
    void 非owner写表违例恰为白名单内条目() throws IOException {
        Map<String, Integer> actual = scanViolations();

        String hint = """
                表写入方与白名单不一致。
                · 若某表计数变大：非 owner 模块新增了对受管表的写入，应改由 owner 模块承担；
                · 若变少或消失：你已把写入收回 owner，请从 KNOWN_VIOLATIONS 删掉对应条目（棘轮只减不增）。
                """;
        assertEquals(new TreeMap<>(KNOWN_VIOLATIONS), new TreeMap<>(actual), hint);
    }

    @Test
    @DisplayName("扫描器本身有效：能定位到各模块源码树（防止空扫导致假绿）")
    void 扫描器能读到各模块源码树() throws IOException {
        for (String module : MODULES) {
            List<Path> files = GuardSupport.javaFiles(GuardSupport.repoRoot().resolve(module + "/src/main/java"));
            assertTrue(files.size() > 5, module + " 应有若干 .java，实际 " + files.size());
        }
    }

    /** 扫描两模块源码，统计「非 owner 模块写受管表」的语句条数。 */
    private static Map<String, Integer> scanViolations() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        for (String module : MODULES) {
            Path root = GuardSupport.repoRoot().resolve(module + "/src/main/java");
            for (Path file : GuardSupport.javaFiles(root)) {
                String code = GuardSupport.stripComments(
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                Matcher m = WRITE.matcher(code);
                while (m.find()) {
                    String table = m.group(2).toLowerCase();
                    String owner = OWNER.get(table);
                    if (owner != null && !owner.equals(module)) {
                        String key = module + " -> " + table;
                        Integer current = counts.get(key);
                        counts.put(key, current == null ? 1 : current + 1);
                    }
                }
            }
        }
        return counts;
    }
}