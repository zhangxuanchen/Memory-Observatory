/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】WorkspacePathGuard.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】文件工具的第一道安全闸门：相对路径 → 工作区内绝对路径，
 *            规范化后必须仍位于工作区根之下，否则抛 GuardException 防路径穿越。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件工具的第一道安全闸门：将相对路径解析为工作区内绝对路径，并防止路径穿越劫持。
 * 规范化后的路径必须仍在工作区根目录之下，否则抛出 {@link GuardException}。
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {
    }

    /**
     * 将 LLM 给出的（相对）路径解析为工作区内的绝对路径；越界直接抛 {@link GuardException}。
     *
     * @param ctx     工具执行上下文（提供 workspaceRoot）
     * @param rawPath LLM 传入的路径
     * @return 规范化且位于工作区内的绝对路径
     */
    public static Path resolve(UserContext ctx, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new GuardException("路径不能为空。请传入一个相对工作区的路径。");
        }
        Path root = ctx.workspaceRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(rawPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new GuardException(
                    "路径越界被拒绝: '" + rawPath + "'。只允许访问工作区内的相对路径。");
        }
        return resolved;
    }

    /**
     * 读取文件内容并做上限截断；文件不存在或读取失败返回 "ERROR:" 开头字符串（供 LLM 自纠）。
     *
     * @param p        待读取文件（已通过 {@link #resolve} 校验）
     * @param maxChars 返回内容的最大字符数
     * @return 文件内容，或以 "ERROR:" 开头的错误描述
     */
    public static String readGuarded(Path p, long maxChars) {
        try {
            if (!Files.exists(p)) {
                return "ERROR: 文件不存在: " + p.getFileName();
            }
            if (Files.isDirectory(p)) {
                return "ERROR: 目标是一个目录，本工具只支持读取文件: " + p;
            }
            String content = Files.readString(p);
            if (content.length() > maxChars) {
                return content.substring(0, (int) maxChars)
                        + "\n...[已截断，全文 " + content.length() + " 字符。"
                        + "如需查看后文请用其它方式（bash 分段读取）。]";
            }
            return content;
        } catch (IOException e) {
            return "ERROR: 读取失败: " + e.getMessage();
        }
    }

    /** 路径越界/非法访问时抛出的受控异常，由调用方工具捕获并转成 ERROR 字符串。 */
    public static class GuardException extends RuntimeException {
        public GuardException(String msg) {
            super(msg);
        }
    }
}