/*******************************************************************************
 * 【模块】Agent 工作流 Workflow（Java-Maven + 前端 多角色）
 * 【文件】BuildVerifier.java（io.memobservatory.agentloop.workflow）
 * 【核心功能】真实构建门禁校验器：在工作区根目录实际执行 mvn/npm 构建，以退出码 +
 *            报告产物判定该门禁是否通过。完全取代文本关键词扫描，保证出锅产物
 *            一定是"能编译、能测试通过"的真实状态。
 * 【核心改动】2026-08-24 新增。
 * 【设计要点】命令以 bash -lc 执行并锚定工作区根目录；重定向合并输出流，仅保留尾部
 *            用于回流给前端与日志；超时强杀防挂死；构建产物按 kind 选择工程文件判定
 *            是否适用（缺工程则跳过，不算失败）。
 *******************************************************************************/
package io.memobservatory.agentloop.workflow;

import io.memobservatory.agentloop.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 真实构建门禁校验器。单次 {@link #verify(GateSpec)} 执行一条构建命令并判据。
 */
public class BuildVerifier {

    private static final Logger log = LoggerFactory.getLogger(BuildVerifier.class);

    public record GateResult(String id, String name, boolean pass, String summary) {
    }

    public BuildVerifier() {
    }

    /**
     * 校验一条门禁。kind 对应工程文件缺失时视为"不适用"，pass=true 跳过。
     *
     * @param ws      目标工作区（决定构建根目录）
     * @param spec    门禁规格
     * @param timeout 构建超时
     */
    public GateResult verify(Workspace ws, GateSpec spec, Duration timeout) {
        Path root = ws.root().toAbsolutePath().normalize();
        String cmd = switch (spec.kind()) {
            case GateSpec.KIND_BACKEND -> "mvn -q test";
            case GateSpec.KIND_FRONTEND -> "npm run build";
            default -> null;
        };
        if (cmd == null) {
            return new GateResult(spec.id(), spec.name(), true, "未知门禁类型，跳过");
        }
        if (!applicable(root, spec.kind())) {
            return new GateResult(spec.id(), spec.name(), true,
                    "无对应工程文件（" + spec.kind() + "），门禁不适用，跳过");
        }

        long ms = timeout == null ? 15 * 60_000L : timeout.toMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", cmd);
            pb.directory(root.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder tail = new StringBuilder(2048);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    appendTail(tail, line, 2000);
                }
            }
            boolean finished = p.waitFor(ms, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new GateResult(spec.id(), spec.name(), false,
                        "构建超时（>" + (ms / 60_000) + "min），已强杀。尾部输出：\n" + tail);
            }
            int code = p.exitValue();
            boolean pass = code == 0;
            return new GateResult(spec.id(), spec.name(), pass,
                    "exit=" + code + (pass ? " PASS" : " FAIL") + "，尾部输出：\n" + tail);
        } catch (IOException e) {
            return new GateResult(spec.id(), spec.name(), false,
                    "无法执行构建命令：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GateResult(spec.id(), spec.name(), false, "构建被中断");
        }
    }

    /** kind 对应的工程文件是否存在（决定门禁是否适用）。 */
    private boolean applicable(Path root, String kind) {
        return switch (kind) {
            case GateSpec.KIND_BACKEND -> Files.isRegularFile(root.resolve("pom.xml"));
            case GateSpec.KIND_FRONTEND -> Files.isRegularFile(root.resolve("package.json"));
            default -> false;
        };
    }

    private void appendTail(StringBuilder sb, String line, int cap) {
        if (sb.length() + line.length() > cap) {
            sb.delete(0, line.length());
        }
        sb.append(line).append('\n');
    }
}