/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】FileTools.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】文件读写三件套：read_file / write_file / edit_file。
 *            路径经 WorkspacePathGuard 白名单校验；写用原子替换避免脏文件。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 * 【设计要点】错误回传 ERROR 字符串而非抛异常；@ToolParam 显式 name。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件读写三件套：read_file / write_file / edit_file。
 * 遵循设计总原则：错误回传而非抛异常、路径白名单守卫、@ToolParam 显式 name。
 */
public class FileTools {

    /** 单次读取返回的最大字符数（中小文件）。 */
    private static final long MAX_READ_CHARS = 50_000;
    /** 「全量读」允许的最大行数默认值；超过则该文件视为大文件，不再整体读入上下文。 */
    private static final int DEFAULT_LINE_CAP = 500;
    /** 大文件默认仅返回前 N 行头部。两者均可经构造参数（mo.agent.*）覆盖。 */
    private static final int DEFAULT_HEAD_LINES = 200;

    /** 「全量读」允许的最大行数；超过则该文件视为大文件，不再整体读入上下文。 */
    private final int wholeFileLineCap;
    /** 大文件仅返回前 N 行头部，引导 Agent 用 grep/片段读取，避免整体塞入把 TOOL 区占满。 */
    private final int headLines;

    /** 默认阈值构造（500/200）。 */
    public FileTools() {
        this(DEFAULT_LINE_CAP, DEFAULT_HEAD_LINES);
    }

    /** 阈值可配构造：行数上限 + 大文件返回头部行数（对应 mo.agent.read-file-line-cap / head-lines）。 */
    public FileTools(int wholeFileLineCap, int headLines) {
        this.wholeFileLineCap = Math.max(1, wholeFileLineCap);
        this.headLines = Math.max(1, headLines);
    }

    @Tool(name = "read_file",
            description = "读取工作区内一个文本文件的内容并返回；超大文件不会整体读取，"
                    + "只会返回行数统计与开头若干行，并提示改用 grep 定位后按片段读取，"
                    + "避免撑爆上下文。路径必须是相对工作区的相对路径。")
    public String readFile(
            @ToolParam(name = "path", description = "文件相对路径，如 'src/Main.java'")
            String path,
            UserContext ctx) {
        try {
            Path p = WorkspacePathGuard.resolve(ctx, path);
            if (Files.isDirectory(p)) {
                return "ERROR: 目标是一个目录，请读取文件: " + path;
            }
            if (!Files.exists(p)) {
                return "ERROR: 文件不存在: " + path;
            }
            long lines;
            try (Stream<String> s = Files.lines(p)) {
                lines = s.count();
            } catch (IOException e) {
                lines = Integer.MAX_VALUE; // 统计失败按大文件从严处理
            }
            if (lines > wholeFileLineCap) {
                return readLargeFileHead(p, path, lines);
            }
            return WorkspacePathGuard.readGuarded(p, MAX_READ_CHARS);
        } catch (WorkspacePathGuard.GuardException g) {
            return "ERROR: " + g.getMessage();
        }
    }

    /** 大文件：仅返回行数统计与前 headLines 行，并明确引导 Agent 走「grep 定位 → 片段读取」。 */
    private String readLargeFileHead(Path p, String path, long totalLines) {
        String head;
        try (Stream<String> s = Files.lines(p)) {
            head = String.join("\n", s.limit(headLines).toList());
        } catch (IOException e) {
            head = "（读取前 " + headLines + " 行失败: " + e.getMessage() + "）";
        }
        return "【文件较大】" + path + " 共 " + totalLines + " 行，为避免占用过多上下文，仅返回前 "
                + headLines + " 行：\n" + head
                + "\n…（已按大文件策略截断，全文 " + totalLines + " 行）\n"
                + "请不要整体读取剩余内容。请先用 grep 搜索关键词定位行号，再按需读取指定片段"
                + "（说明要哪一段，可用 bash 分段读取/搜索）。";
    }

    @Tool(name = "write_file",
            description = "创建或完整覆盖一个文件（会创建缺失的父目录）。"
                    + "内容是文件的完整新内容，不是增量。")
    public String writeFile(
            @ToolParam(name = "path", description = "目标文件相对路径")
            String path,
            @ToolParam(name = "content", description = "文件完整内容")
            String content,
            UserContext ctx) {
        try {
            Path p = WorkspacePathGuard.resolve(ctx, path);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            // 原子写入：先写临时文件再移动，避免写一半崩溃留下脏文件
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, content);
            Files.move(tmp, p,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return "OK: 已写入 " + path + "（" + content.length() + " 字符）";
        } catch (IOException | WorkspacePathGuard.GuardException e) {
            return "ERROR: 写入失败: " + e.getMessage();
        }
    }

    @Tool(name = "edit_file",
            description = "精确替换文件中的一段文本。old_string 必须与文件中现有内容"
                    + "完全一致（含缩进）且在文件中唯一。适合小范围修改，"
                    + "大面积重写请用 write_file。")
    public String editFile(
            @ToolParam(name = "path", description = "文件相对路径")
            String path,
            @ToolParam(name = "old_string", description = "要被替换的原文（必须精确匹配）")
            String oldString,
            @ToolParam(name = "new_string", description = "替换后的新文本")
            String newString,
            UserContext ctx) {
        try {
            Path p = WorkspacePathGuard.resolve(ctx, path);
            if (!Files.exists(p)) return "ERROR: 文件不存在: " + path;
            if (Files.isDirectory(p)) return "ERROR: 目标是目录，无法编辑: " + path;
            String content = Files.readString(p);
            int first = content.indexOf(oldString);
            if (first < 0) {
                return "ERROR: old_string 未找到。请先用 read_file 核对文件当前内容。";
            }
            if (content.indexOf(oldString, first + 1) >= 0) {
                return "ERROR: old_string 出现多次，不唯一。请扩大匹配范围使其唯一。";
            }
            String updated = content.substring(0, first) + newString
                    + content.substring(first + oldString.length());
            Files.writeString(p, updated);
            return "OK: 已替换 1 处（" + oldString.length() + " → "
                    + newString.length() + " 字符）";
        } catch (IOException | WorkspacePathGuard.GuardException e) {
            return "ERROR: 修改失败: " + e.getMessage();
        }
    }
}