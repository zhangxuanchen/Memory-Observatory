/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】FileReadGuardMiddleware.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】工具调用层的大文件守卫：在 onActing 拦截点检查文件读取类工具的 path，
 *            若目标文件超过「全量读」行数上限（默认 500），则把该次调用重写为一条
 *            bash 引导命令（仅输出提示，不读取正文），强制 Agent 改用 grep 定位 →
 *            sed/片段读取，避免大文件整体塞入模型上下文。
 * 【核心改动】2026-08-27 新增（文件大小检查，>500 行强制 grep 片段读取）。
 * 【设计要点】不改写模型输入内容是否合法的框架语义；不跳过 next（跳过会触发
 *            [ERROR] 兜底且结果进不了上下文），而是把超限调用重定向到真实存在的
 *            bash 工具，使「结果能正常进入模型上下文」且「绝无整体读取」。与
 *            FileTools.readFile 的既有行数截断形成双重保障（本守卫更早，堵住
 *            其它绕过 FileTools 的读文件工具路径）。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 工具调用层的大文件守卫：拦截文件读取类工具，目标文件行数超过上限时把调用重写为
 * bash 引导命令，强制走 grep 定位 + 片段读取。错误与路径越界一律回退放行（交给
 * FileTools 自身以其 ERROR 字符串兜底），守卫本身不抛异常、不影响正常调用。
 */
public class FileReadGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(FileReadGuardMiddleware.class);

    /** 需要做行数检查的文件读取类工具名集合。 */
    private static final Set<String> GUARDED_TOOLS = Set.of("read_file");

    private final Path workspaceRoot;
    /** 「全量读」行数上限；超过则禁止整体读取（与 FileTools.readFile 同阈值）。 */
    private final int lineCap;
    /** 是否有 bash 工具可用：只读角色无 bash，无法重定向，交给 FileTools 自身截断。 */
    private final boolean hasBash;

    /** @param workspaceRoot 工作区根（路径越界边界，与 WorkspacePathGuard 一致）
     *  @param lineCap       全量读行数上限（默认 500）
     *  @param hasBash       是否有 bash 工具（只读角色为 false） */
    public FileReadGuardMiddleware(Path workspaceRoot, int lineCap, boolean hasBash) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.lineCap = Math.max(1, lineCap);
        this.hasBash = hasBash;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        // 空批次 / 无 bash（只读角色）时直接放行，不做任何干预
        if (calls == null || calls.isEmpty() || !hasBash) {
            return next.apply(input);
        }
        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock tc = calls.get(i);
            Long lineCount = tooBig(tc);
            if (lineCount == null) {
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(calls);
            }
            log.info("[file-read-guard] 拦截大文件读取 agent={} tool={} path={} lines={} (>{} 行)",
                    agent != null ? agent.getName() : "?",
                    tc.getName(), tc.getInput().getOrDefault("path", "?"),
                    lineCount, lineCap);
            rewritten.set(i, rewriteToGrepGuidance(tc, lineCount));
        }
        if (rewritten == null) {
            return next.apply(input);
        }
        return next.apply(new ActingInput(rewritten));
    }

    /** 若目标为受守卫的读取工具且文件超过行数上限，返回行数；否则 null（放行）。 */
    private Long tooBig(ToolUseBlock tc) {
        if (!GUARDED_TOOLS.contains(tc.getName())) {
            return null;
        }
        Object raw = tc.getInput().get("path");
        if (!(raw instanceof String p) || p.isBlank()) {
            return null;
        }
        Path resolved;
        try {
            resolved = workspaceRoot.resolve(p).toAbsolutePath().normalize();
            if (!resolved.startsWith(workspaceRoot)) {
                return null; // 越界交给 FileTools 报错，不在这里拦截
            }
        } catch (Exception e) {
            return null;
        }
        if (!Files.isRegularFile(resolved)) {
            return null;
        }
        long lines;
        try (Stream<String> s = Files.lines(resolved)) {
            lines = s.count();
        } catch (IOException e) {
            return null;
        }
        return lines > lineCap ? Long.valueOf(lines) : null;
    }

    /** 把超限的 read_file 重写为一条 bash 引导命令（仅输出提示，不读取正文）。 */
    private ToolUseBlock rewriteToGrepGuidance(ToolUseBlock tc, long lineCount) {
        Object raw = tc.getInput().get("path");
        String path = raw instanceof String s ? s : String.valueOf(raw);
        String lit = "[大文件保护] 文件 " + shellEsc(path) + " 共 " + lineCount
                + " 行，超过 " + lineCap + " 行上限，禁止整体读取。"
                + "请先用 grep -n \"关键词\" " + shellEsc(path) + " 定位目标行号，"
                + "再用 sed -n \"起,止 p\" " + shellEsc(path) + " 按片段精确读取。";
        Map<String, Object> in = new HashMap<>();
        // echo 整段为单引号包裹（文字内不含单引号），safe；不执行任何文件读取
        in.put("command", "echo '" + shellEsc(lit) + "'");
        // 复用原调用 id，保证 pending 工具调用能被该结果正确消解
        return new ToolUseBlock(tc.getId(), "bash", in);
    }

    /** shell 单引号转义（zsh/bash 兼容），防路径内含引号注入。 */
    private static String shellEsc(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}