/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】BashTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】bash 逃生舱：工作目录锚定在工作区执行 zsh 命令，经 BashGuard 黑名单
 *            校验、超时强杀、输出截断。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bash 逃生舱：在工作区目录下执行 shell 命令。第一道闸是 {@link BashGuard} 命令黑名单。
 */
public class BashTool {

    private static final int MAX_OUTPUT_CHARS = 20_000;
    private final Duration timeout;

    public BashTool(Duration timeout) {
        this.timeout = timeout;
    }

    @Tool(name = "bash",
            description = "在工作区目录下执行一条 shell 命令（zsh）。"
                    + "适用于：运行测试、git 操作、安装依赖、查找文件等"
                    + "没有专用工具的操作。优先使用专用文件工具。\n"
                    + "参数名务必填写为 command（本工具是 command，不是 content/write；"
                    + "若拿不准字段名，参考 read_file/write_file 的区别，写命令请用 command）。")
    public String bash(
            @ToolParam(name = "command", description = "要执行的命令，尽量单条简单命令")
            String command,
            @ToolParam(name = "content", required = false, description = "兼容字段：若模型误把命令写在 content，则这里兜底视为 command；正常请直接用 command")
            String content,
            UserContext ctx) {

        // 字段容错：个别模型习惯把命令写在 content，导致 command 缺失被 schema 拒绝。
        // 这里两者取其一，任一非空即视为「要执行的命令」。
        String cmd = (command != null && !command.isBlank()) ? command
                : (content != null && !content.isBlank()) ? content : null;
        if (cmd == null || cmd.isBlank()) {
            return "ERROR: 未提供命令（请把待执行命令写入 command 字段）";
        }

        // 第一道闸：命令黑名单
        String verdict = BashGuard.check(cmd);
        if (verdict != null) return "ERROR: 命令被安全策略拒绝 — " + verdict;

        try {
            ProcessBuilder pb = new ProcessBuilder("zsh", "-c", cmd)
                    .directory(ctx.workspaceRoot().toFile())   // 工作目录锚定在工作区
                    .redirectErrorStream(true);                 // stderr 合并进 stdout
            Process proc = pb.start();

            boolean finished = proc.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return "ERROR: 命令超时（>" + timeout.toSeconds() + "s）被终止: " + cmd;
            }

            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.exitValue();
            String trimmed = output.length() > MAX_OUTPUT_CHARS
                    ? output.substring(0, MAX_OUTPUT_CHARS)
                    + "\n...[输出截断，共 " + output.length() + " 字符]"
                    : output;

            return "exit_code=" + exit + "\n" + (trimmed.isBlank() ? "(无输出)" : trimmed);

        } catch (Exception e) {
            return "ERROR: 命令执行失败: " + e.getMessage();
        }
    }
}