package io.memobservatory.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2 · 依赖方向守则（对应架构重构 R3 / 文档 §4.6）。
 *
 * <p>断言 {@code io.memobservatory.server.**}（mo-server）与 {@code io.memobservatory.agentloop.**}
 * （mo-workbench）之间<b>不存在</b>任何跨包 import。
 *
 * <p>M2 已把所有历史违例改走中立契约包 {@code io.memobservatory.contract}（依赖倒置）。
 * M3 进一步把 agentloop 提成独立模块 {@code mo-workbench}，二者不再同处一个 jar——
 * 依赖方向由 Maven 编译期强制，但**是否真的没人 import 对方**仍需守则兜底
 * （同一模块内可以靠纪律违规；跨模块也可能有人用 {@code <scope>test</scope>} 之类绕开）。
 *
 * <p><b>零容忍</b>：不再有棘轮白名单——断言「违例数 == 0」。新增任何跨包 import 直接红。
 *
 * <p><b>契约纯度（R8）</b>：中立契约 {@code io.memobservatory.contract}（mo-contract 模块）是
 * 依赖倒置的支点，必须是「叶子」——只依赖 JDK，不得 import 任一侧实现（server / agentloop）。
 * 否则契约会反向依赖实现，R3 以更隐蔽的形式复发。
 */
class DependencyDirectionGuardTest {

    private static final String SERVER_PKG = "io.memobservatory.server.";
    private static final String AGENTLOOP_PKG = "io.memobservatory.agentloop.";
    private static final String PKG_PREFIX = "io/memobservatory/";

    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(io\\.memobservatory\\.(?:server|agentloop)\\.[\\w.$]+)\\s*;");

    @Test
    @DisplayName("跨包依赖违例必须为 0（mo-server 与 mo-workbench 不得互 import）")
    void 跨包依赖违例必须为0() throws IOException {
        Set<String> actual = scanViolations();

        String hint = """
                检测到 %d 处跨包依赖违例（应为 0）：
                %s
                · 请改设计：把被依赖能力抽象成中立契约接口（io.memobservatory.contract），
                  由提供方实现、依赖方只认接口；不要让 server/agentloop 相互 import。
                """.formatted(actual.size(), String.join("\n", actual));
        assertTrue(actual.isEmpty(), hint);
    }

    @Test
    @DisplayName("扫描器本身有效：能定位到两侧源码树（防止空扫导致假绿）")
    void 扫描器能读到两侧源码树() throws IOException {
        Path root = GuardSupport.repoRoot();
        int serverFiles = GuardSupport.javaFiles(root.resolve("mo-server/src/main/java")).size();
        int workbenchFiles = GuardSupport.javaFiles(root.resolve("mo-workbench/src/main/java")).size();
        assertTrue(serverFiles > 20, "mo-server/src/main/java 下应有数十个 .java，实际 " + serverFiles);
        assertTrue(workbenchFiles > 50, "mo-workbench/src/main/java 下应有数十个 .java，实际 " + workbenchFiles);
    }

    @Test
    @DisplayName("契约包不得 import 任一侧实现（contract 必须是叶子）")
    void 契约包不得import任一侧实现() throws IOException {
        Path root = GuardSupport.repoRoot().resolve("mo-contract/src/main/java/io/memobservatory/contract");
        List<Path> files = GuardSupport.javaFiles(root);
        assertTrue(!files.isEmpty(), "契约包应有源文件（防空扫假绿）：" + root);

        Set<String> actual = new TreeSet<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            for (String line : GuardSupport.stripComments(javaSource(file)).split("\n", -1)) {
                // IMPORT 只匹配 io.memobservatory.server.** / agentloop.**，故命中即违例
                Matcher m = IMPORT.matcher(line);
                if (m.find()) {
                    actual.add("contract/" + rel + " -> " + m.group(1));
                }
            }
        }
        String hint = """
                检测到 %d 处「契约包 import 实现侧」的违例（应为 0）：
                %s
                · io.memobservatory.contract 必须是叶子：只依赖 JDK / 标准库，
                  不得 import io.memobservatory.server.** 或 io.memobservatory.agentloop.**。
                · 契约若需要某类型，应把该类型也上移到契约，或改用更窄的接口。
                """.formatted(actual.size(), String.join("\n", actual));
        assertTrue(actual.isEmpty(), hint);
    }

    /** 扫描两侧源码树，收集所有 forbidden 方向的 import。 */
    private static Set<String> scanViolations() throws IOException {
        Path root = GuardSupport.repoRoot();
        Set<String> violations = new TreeSet<>();
        collectForbidden(root.resolve("mo-server/src/main/java"), AGENTLOOP_PKG, violations);
        collectForbidden(root.resolve("mo-workbench/src/main/java"), SERVER_PKG, violations);
        return violations;
    }

    private static void collectForbidden(Path root, String forbidden, Set<String> violations) throws IOException {
        for (Path file : GuardSupport.javaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String shortRel = rel.startsWith(PKG_PREFIX) ? rel.substring(PKG_PREFIX.length()) : rel;
            for (String line : GuardSupport.stripComments(javaSource(file)).split("\n", -1)) {
                Matcher m = IMPORT.matcher(line);
                if (m.find()) {
                    String fqn = m.group(1);
                    if (fqn.startsWith(forbidden)) {
                        violations.add(shortRel + " -> " + fqn);
                    }
                }
            }
        }
    }

    private static String javaSource(Path file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }
}