package io.memobservatory.server.semantic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * laya 后端生命周期管理。
 *
 * <p><b>本项目自包含</b>：后端脚本随本仓库分发（{@code laya-backend/server.py}，
 * 本项目自有代码，按 Apache-2.0 分发，来源说明见 {@code laya-backend/NOTICE}），
 * laya 本体只作为 PyPI 依赖安装。本类<b>不依赖任何外部源码仓库</b>。
 *
 * <p>两种运行形态：
 * <ul>
 *   <li><b>容器（默认）</b>——laya 后端是 compose 里的独立服务，生命周期由 compose 管。
 *       本类等它就绪（<b>后台等待，不阻塞应用启动</b>）并做一次中文自检。</li>
 *   <li><b>本机</b>——若项目内的 {@code laya-backend/} 下已有 Python 环境，直接拉起子进程；
 *       没有则<b>后台自举</b>（建 venv + {@code pip install -r requirements.txt}），装完再拉起。
 *       应用关闭时回收。</li>
 * </ul>
 *
 * <p>四条设计原则：
 * <ul>
 *   <li><b>幂等</b>——后端已在运行（无论谁起的）则直接复用，不重复拉起</li>
 *   <li><b>不阻断</b>——任何失败都只告警，应用照常启动；语义层降级为 fail-open</li>
 *   <li><b>可观测</b>——子进程 stdout/stderr 转发到应用日志</li>
 *   <li><b>可回收</b>——只关闭自己拉起的进程，不动外部已有的后端</li>
 * </ul>
 *
 * <p>权重不在这里管：后端的 {@code server.py} 启动时会自行预检并补下，
 * 这样容器与本机两条路径共用同一套逻辑，也不必让 JVM 去猜 HF 缓存布局。
 */
@Slf4j
@Component
public class LayaBackendManager implements ApplicationRunner, DisposableBean {

    private final SemanticProperties props;
    private final LayaClient client;

    /** 是否由本进程拉起的（决定关闭时是否回收）。 */
    private final AtomicBoolean owned = new AtomicBoolean(false);
    /** 中文（multilingual checkpoint）链路是否验证可用。 */
    private final AtomicBoolean multilingualReady = new AtomicBoolean(false);
    /** 后端与权重可用性的证据：中文自检通过即置位（结果反推，比路径探测可信）。 */
    private final AtomicBoolean backendUsable = new AtomicBoolean(false);
    private volatile Process process;
    private volatile Thread watcher;
    private volatile Thread bootstrapper;
    private volatile Process bootstrapProc;

    public LayaBackendManager(SemanticProperties props, LayaClient client) {
        this.props = props;
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("[laya] 语义层未启用（mo.semantic.enabled=false），跳过后端准备");
            return;
        }

        // 1) 后端已在运行 → 复用 + 自检。容器场景由 compose 管理后端，通常走这条。
        if (client.healthy()) {
            log.info("[laya] 后端已在运行，直接复用：{}", props.getBaseUrl());
            selfCheck();
            return;
        }

        // 2) 本机形态：项目自带的 laya-backend/ 有可用 Python 环境就拉起。
        //    依赖已装好时这一步很快（秒级），所以在前台做。
        Path dir = props.isAutoStartBackend() ? resolveBackendDir() : null;
        if (dir != null) {
            String python = resolvePython(dir);
            if (python != null && startLocalBackend(dir, python)) {
                return;
            }
            // 环境还没建好 → 后台自举（建 venv + 装 laya），同样不阻塞启动
            if (python == null && props.isAutoBootstrap()) {
                bootstrapAsync(dir);
                return;
            }
        }

        // 3) 其余情况：后端由外部编排管理，可能还在补权重或加载模型。
        //    放后台等，**不阻塞应用启动**——应用本身（告警、看板）不依赖语义层。
        watchExternalBackend();
    }

    /**
     * 端到端自检：用一段中文探针验证 Router 会把内容路由到、且能加载 multilingual checkpoint。
     *
     * <p><b>为什么必须做这个</b>：英文 checkpoint 读非拉丁脚本会崩到接近随机却仍报高置信，
     * 所以 Router 会把中文路由到 multilingual。若 multilingual 权重缺失，判定会直接抛错，
     * 而过滤器是 fail-open 的——结果是<b>语义过滤静默失效</b>（全部候选被原样保留），
     * 上层只看数字会以为「没有假阳性」，实际根本没过滤。
     * 本项目的 Agent 内容以中文为主，这个探针就是必要前提检查。
     */
    private void selfCheck() {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("snippet", "这是一段中文探针内容，用于验证多语言 checkpoint 是否可以正常判定。");
        probe.put("rule_name", "启动自检");
        probe.put("field", "selfcheck");
        try {
            client.predict(probe, PromptLibrary.riskQuestions());
            multilingualReady.set(true);
            backendUsable.set(true);
            log.info("[laya] 自检通过：多语言（中文）判定可用");
        } catch (Exception e) {
            multilingualReady.set(false);
            String reason = String.valueOf(e.getMessage());
            log.warn("[laya] ⚠️ 自检失败：中文内容无法判定，语义过滤将静默失效"
                    + "（fail-open 会保留全部候选，看起来像「没问题」）；现有告警本身不受影响");
            log.warn("[laya] 常见原因：multilingual checkpoint 未就绪（后端首次启动需补下权重），"
                    + "或后端加载出错。排查：docker compose logs laya-backend");
            log.warn("[laya] 原始错误：{}", reason.length() > 400 ? reason.substring(0, 400) : reason);
        }
    }

    /** 中文（多语言 checkpoint）链路是否可用；false 时语义过滤等同未启用。 */
    public boolean isMultilingualReady() {
        return multilingualReady.get();
    }

    /** 后端是否已被验证可用（中文自检通过）。 */
    public boolean isBackendUsable() {
        return backendUsable.get();
    }

    /** 后端是否由本进程拉起（本机形态）。容器形态下为 false。 */
    public boolean isOwned() {
        return owned.get();
    }

    @Override
    public void destroy() {
        Thread w = watcher;
        if (w != null) {
            w.interrupt();
        }
        Thread b = bootstrapper;
        if (b != null) {
            b.interrupt();
        }
        Process bp = bootstrapProc;
        if (bp != null && bp.isAlive()) {
            bp.destroyForcibly();
        }
        stopProcess();
    }

    // ==================== 本机形态：拉起项目自带的后端 ====================

    /**
     * 尝试用项目自带的 {@code laya-backend/} 在本机拉起后端（环境已就绪的快路径）。
     *
     * @param dir    项目内的后端目录（含 {@code server.py}）
     * @param python 已验证可 {@code import laya} 的解释器
     * @return true 表示已由本进程成功拉起并就绪（后续无需再等外部后端）
     */
    private boolean startLocalBackend(Path dir, String python) {
        try {
            startProcess(dir, python);
            if (awaitReady()) {
                log.info("[laya] 本机后端就绪 ✓ {}（预加载 {}，设备 {}）",
                        props.getBaseUrl(),
                        props.getPreload().isBlank() ? "lazy" : props.getPreload(),
                        props.getDevice().isBlank() ? "自动" : props.getDevice());
                selfCheck();
                return true;
            }
            log.warn("[laya] 本机后端 {}s 内未就绪，释放子进程，转入外部等待",
                    props.getReadyTimeoutSeconds());
            stopProcess();
            return false;
        } catch (Exception e) {
            log.warn("[laya] 本机拉起失败：{}", e.getMessage());
            stopProcess();
            return false;
        }
    }

    // ==================== 本机形态：后台自举 Python 环境 ====================

    /**
     * 后台自举：{@code python3 -m venv .venv} + {@code pip install -r requirements.txt}，
     * 装完再拉起后端。
     *
     * <p>放后台的原因：torch 等依赖要下几十到上百 MB，几十秒到几分钟不等。
     * 这期间应用照常启动，语义层只是还没生效（fail-open 保留全部候选），
     * 装好之后自动补上。<b>不阻塞、不报错、不要求用户做任何事</b>。
     */
    private void bootstrapAsync(Path dir) {
        bootstrapper = new Thread(() -> {
            log.info("[laya] {} 下尚无可用 Python 环境，开始在后台自举（首次约 1~3 分钟，"
                    + "期间语义层暂不生效，应用照常使用）", dir);
            if (!runBootstrap(dir)) {
                log.warn("[laya] 自举失败，语义层保持降级。手工执行："
                        + "bash scripts/setup-laya-backend.sh（或在容器里用 docker compose up -d laya-backend）");
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String python = resolvePython(dir);
            if (python == null) {
                log.warn("[laya] 自举完成但仍无法 import laya，放弃本机拉起");
                return;
            }
            log.info("[laya] 自举完成，拉起后端");
            if (startLocalBackend(dir, python)) {
                return;
            }
            log.warn("[laya] 自举后仍未能拉起后端，转入外部等待");
            bootstrapProc = null;
            watchExternalBackend();
        }, "laya-backend-bootstrap");
        bootstrapper.setDaemon(true);
        bootstrapper.start();
    }

    /** 建 venv 并装依赖。任一步失败返回 false（细节已写日志）。 */
    private boolean runBootstrap(Path dir) {
        Path vpy = venvPython(dir);
        if (vpy == null) {
            String py = notBlank(props.getPython()) ? props.getPython() : "python3";
            if (!run(dir, List.of(py, "-m", "venv", ".venv"), props.getBootstrapTimeoutSeconds())) {
                return false;
            }
            vpy = venvPython(dir);
            if (vpy == null) {
                log.warn("[laya] venv 已建但找不到解释器（{}/.venv），跳过自举", dir);
                return false;
            }
        }
        List<String> cmd = new ArrayList<>(List.of(vpy.toString(), "-m", "pip", "install",
                "--disable-pip-version-check", "-r", "requirements.txt"));
        if (notBlank(props.getPipIndexUrl())) {
            cmd.addAll(List.of("-i", props.getPipIndexUrl()));
        }
        return run(dir, cmd, props.getBootstrapTimeoutSeconds());
    }

    /** 执行一条子进程命令，输出转发到日志；超时或非零退出返回 false。 */
    private boolean run(Path dir, List<String> cmd, int timeoutSeconds) {
        try {
            log.info("[laya] $ {}", String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.environment().put("PYTHONUNBUFFERED", "1");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            bootstrapProc = p;
            pipeLogs(p);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("[laya] 命令超时（{}s）：{}", timeoutSeconds, String.join(" ", cmd));
                p.destroyForcibly();
                return false;
            }
            int code = p.exitValue();
            if (code != 0) {
                log.warn("[laya] 命令退出码 {}：{}", code, String.join(" ", cmd));
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("[laya] 命令执行失败：{}（{}）", e.getMessage(), String.join(" ", cmd));
            return false;
        } finally {
            bootstrapProc = null;
        }
    }

    /** 项目内 venv 的解释器，跨平台（POSIX / Windows）。 */
    private static Path venvPython(Path dir) {
        Path unix = dir.resolve(".venv/bin/python");
        if (Files.isExecutable(unix)) {
            return unix;
        }
        Path win = dir.resolve(".venv/Scripts/python.exe");
        if (Files.isExecutable(win)) {
            return win;
        }
        return null;
    }

    /**
     * 定位项目自带的 laya 后端目录（含 {@code server.py}）。
     *
     * <p><b>只认项目内的路径</b>——这个改造刻意不依赖任何外部仓库。
     * 解析顺序：配置 → 环境变量 {@code MO_LAYA_BACKEND_DIR} → 工作目录下的
     * {@code ../laya-backend}（从 mo-server/ 启动）→ {@code laya-backend}（从仓库根启动）。
     */
    private Path resolveBackendDir() {
        List<String> candidates = new ArrayList<>();
        if (notBlank(props.getBackendDir())) {
            candidates.add(props.getBackendDir());
        }
        String env = System.getenv("MO_LAYA_BACKEND_DIR");
        if (notBlank(env)) {
            candidates.add(env);
        }
        candidates.add("../laya-backend");
        candidates.add("laya-backend");

        for (String c : candidates) {
            try {
                Path p = Path.of(c).toAbsolutePath().normalize();
                if (Files.isRegularFile(p.resolve("server.py"))) {
                    return p;
                }
            } catch (Exception ignored) {
                // 路径非法，试下一个
            }
        }
        return null;
    }

    /** 找一个既能跑起来、又能 import laya 的解释器。优先项目内的 venv（自包含）。 */
    private String resolvePython(Path dir) {
        List<String> candidates = new ArrayList<>();
        if (notBlank(props.getPython())) {
            candidates.add(props.getPython());
        }
        Path venvPy = venvPython(dir);
        if (venvPy != null) {
            candidates.add(venvPy.toString());
        }
        candidates.add("python3");

        for (String c : candidates) {
            if (canImportLaya(c)) {
                return c;
            }
        }
        return null;
    }

    private boolean canImportLaya(String python) {
        try {
            Process p = new ProcessBuilder(python, "-c", "import sys, laya; sys.exit(0)")
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void startProcess(Path dir, String python) throws Exception {
        URI u = URI.create(props.getBaseUrl());
        String host = notBlank(u.getHost()) ? u.getHost() : "127.0.0.1";
        int port = u.getPort() > 0 ? u.getPort() : 8770;

        List<String> cmd = new ArrayList<>(List.of(
                python, "server.py",
                "--host", host,
                "--port", String.valueOf(port)));
        if (notBlank(props.getPreload())) {
            cmd.addAll(List.of("--preload", props.getPreload()));
        }
        if (notBlank(props.getDevice())) {
            cmd.addAll(List.of("--device", props.getDevice()));
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 脚本与本目录同在（server.py 只 import laya，不 import 项目内模块），无需 PYTHONPATH
        pb.directory(dir.toFile());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        if (notBlank(props.getHfEndpoint())) {
            pb.environment().put("HF_ENDPOINT", props.getHfEndpoint());
        }
        pb.redirectErrorStream(true);

        log.info("[laya] 启动本机后端：{}（工作目录 {}）", String.join(" ", cmd), dir);
        process = pb.start();
        owned.set(true);
        pipeLogs(process);
    }

    /** 子进程输出转发到应用日志，便于在同一个日志流里排查。 */
    private void pipeLogs(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[laya] {}", line);
                }
            } catch (Exception ignored) {
                // 进程结束时的流关闭异常，无需处理
            }
        }, "laya-log-pipe");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询 /health 直到就绪；子进程提前退出则立即失败，不空等满超时。 */
    private boolean awaitReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + props.getReadyTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p != null && !p.isAlive()) {
                log.warn("[laya] 子进程提前退出（exit={}），请检查上方日志", p.exitValue());
                return false;
            }
            if (client.healthy()) {
                return true;
            }
            Thread.sleep(700);
        }
        return false;
    }

    // ==================== 容器形态：后台等待外部后端 ====================

    /**
     * 后台等待外部后端就绪，<b>不阻塞应用启动</b>。
     *
     * <p>容器场景下 laya-backend 首次要补下 ~1.5GB 权重，可能好几分钟。让 mo-server
     * 干等它没有意义——应用本身（告警、看板）不依赖语义层。等到了就自检并启用，
     * 等不到就保持 fail-open 并告警一次。
     */
    private void watchExternalBackend() {
        int waitSeconds = Math.max(30, props.getExternalWaitSeconds());
        watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
            log.info("[laya] 后端暂不可达（{}），已在后台等待（最多 {}s）；应用照常启动",
                    props.getBaseUrl(), waitSeconds);
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (client.healthy()) {
                    log.info("[laya] 外部后端已就绪，开始自检");
                    selfCheck();
                    return;
                }
            }
            log.warn("[laya] 等待 {}s 后后端仍不可达，语义层保持降级"
                    + "（fail-open，现有告警不受影响）", waitSeconds);
            log.warn("[laya] 排查：curl {}/health；容器方式看 docker compose logs laya-backend",
                    props.getBaseUrl());
        }, "laya-backend-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** 只回收本进程拉起的后端；外部已在运行的后端不动。 */
    private void stopProcess() {
        Process p = process;
        process = null;
        if (!owned.compareAndSet(true, false) || p == null || !p.isAlive()) {
            return;
        }
        log.info("[laya] 关闭本机后端子进程");
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
