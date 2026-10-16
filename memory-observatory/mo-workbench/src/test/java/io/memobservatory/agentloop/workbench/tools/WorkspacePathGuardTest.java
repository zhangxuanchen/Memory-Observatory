/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】WorkspacePathGuardTest.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】路径穿越闸门的行为回归：区内路径必解析成功，越界路径必抛 GuardException，
 *            readGuarded 的截断/缺失/目录三种回包格式固定。
 * 【核心改动】2026-10-12 架构审视「workbench 零测试」补齐——安全闸门先上锁。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link WorkspacePathGuard} 行为测试：LLM 给的路径，出了工作区就必须被拦下。
 */
class WorkspacePathGuardTest {

    private static UserContext ctx(Path root) {
        return new UserContext("u1", "s1", root);
    }

    /** 工作区内的相对路径：正常解析，落在根之下。 */
    @Test
    void 区内路径解析成功(@TempDir Path root) {
        Path p = WorkspacePathGuard.resolve(ctx(root), "src/main/App.java");
        // 断字符串前缀而非 Path.startsWith：后者会 toRealPath，文件不存在时直接抛
        assertThat(p.toString()).startsWith(root.toAbsolutePath().normalize().toString());
        assertThat(p.getFileName().toString()).isEqualTo("App.java");
    }

    /** 路径穿越必须被拒：`..` 爬出根、绝对路径指到根外，两种姿势都拦。 */
    @Test
    void 越界路径抛GuardException(@TempDir Path root) {
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(ctx(root), "../../etc/passwd"))
                .isInstanceOf(WorkspacePathGuard.GuardException.class)
                .hasMessageContaining("越界");
        // 区内一个不存在的子目录下的 ../.. 也一样拦（规范化后才比较，不只看字面）
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(ctx(root), "a/b/../../../secret"))
                .isInstanceOf(WorkspacePathGuard.GuardException.class);
    }

    /** 空/null 路径拒绝；区内绝对路径（恰在根下）放行。 */
    @Test
    void 空路径拒绝且合法绝对路径放行(@TempDir Path root) {
        assertThatThrownBy(() -> WorkspacePathGuard.resolve(ctx(root), "  "))
                .isInstanceOf(WorkspacePathGuard.GuardException.class)
                .hasMessageContaining("不能为空");
        Path inside = root.toAbsolutePath().normalize().resolve("ok.txt");
        assertThat(WorkspacePathGuard.resolve(ctx(root), inside.toString())).isEqualTo(inside);
    }

    /** readGuarded：正常读取、超长截断（带固定提示）、缺文件/目录各有 ERROR 前缀。 */
    @Test
    void 读取守卫的四种回包(@TempDir Path root) throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "hello");
        assertThat(WorkspacePathGuard.readGuarded(f, 100)).isEqualTo("hello");

        Files.writeString(f, "x".repeat(50));
        String cut = WorkspacePathGuard.readGuarded(f, 10);
        assertThat(cut).startsWith("xxxxxxxxxx").contains("已截断").contains("全文 50 字符");

        assertThat(WorkspacePathGuard.readGuarded(root.resolve("missing.txt"), 100))
                .startsWith("ERROR: 文件不存在");
        assertThat(WorkspacePathGuard.readGuarded(root, 100))
                .startsWith("ERROR: 目标是一个目录");
    }
}
