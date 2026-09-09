/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】WebCaptureTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】工作区「截图 + 看真图」能力：screenshot_webpage 用本机无头 Chrome 把
 *            工作区里的网页渲染成 PNG 落盘；analyze_image 借助真·视觉大模型读取任意
 *            图片并按要求作答（非 DOM/OCR）。
 * 【核心改动】2026-08-28 新增。截图走本机 Chrome headless（无需新增 Maven 依赖），
 *            视觉请求走原生 OpenAI 兼容 HTTP（dashscope 兼容端点 / OpenAI 端点），
 *            规避 agentscope 2.0.0 对图片 content 序列化不完整的问题。
 * 【设计要点】所有路径须落在工作区根之内（异常/越界即拒绝）。外部 URL 直接截图；
 *            本机文件转 file:// 渲染。视觉模型未配置时按 provider 回落默认。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.memobservatory.agentloop.workspace.Workspace;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工作区经理（admin）专属：网页截图 + 真图解析。照片/生成页面渲染成 PNG 后，
 * 用视觉模型看清图上内容，形成「生成 → 截图 → 看图自检」的闭环。
 */
public class WebCaptureTool {

    private static final Logger log = LoggerFactory.getLogger(WebCaptureTool.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** 无头截图超时（秒）。 */
    private static final int CHROME_TIMEOUT_SEC = 30;
    /** fetch_webpage 提取内容上限（字符），防止撑爆上下文。 */
    private static final int FETCH_CAP = 20000;
    /** 抓取请求使用的浏览器 UA。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private final Workspace workspace;
    private final String provider;
    private final String dashKey;
    private final String openaiKey;
    private final String openaiBaseUrl;
    private final String visionModelCfg;
    private final String chromePath;

    public WebCaptureTool(Workspace workspace,
                          String provider,
                          String dashKey,
                          String openaiKey,
                          String openaiBaseUrl,
                          String visionModelCfg,
                          String chromePath) {
        this.workspace = workspace;
        this.provider = provider == null ? "dashscope" : provider;
        this.dashKey = dashKey;
        this.openaiKey = openaiKey;
        this.openaiBaseUrl = openaiBaseUrl;
        this.visionModelCfg = visionModelCfg;
        this.chromePath = chromePath;
    }

    /**
     * 用无头 Chrome 把工作区网页渲染成 PNG 截图落盘（可让 Agent「看到」自己生成的页面）。
     * target 支持三种：外部 http(s) 网址；本机文件绝对路径；相对工作区根的路径。
     * 落盘图片路径 outputPath（绝对或相对工作区根）；不传则默认存到工作区根
     * screenshot-时间戳.png。返回图片绝对路径。
     */
    @Tool(name = "screenshot_webpage",
            description = "把工作区里的一个网页渲染成 PNG 截图保存到工作区，用于查看自己生成页面的实际效果。"
                    + "target 支持外部 http(s) 网址、本机文件绝对路径、或相对工作区根的路径三种。"
                    + "outputPath 可选：存到哪里（绝对或相对工作区根），不传默认存工作区根 screenshot-时间戳.png。"
                    + "返回截图文件的绝对路径，可接着用 analyze_image 让模型看懂截图内容。")
    public Mono<ToolResultBlock> screenshotWebpage(
            @ToolParam(name = "target", description = "要截图的网页：http(s) 网址，或本机文件绝对路径，或相对工作区根路径。")
            String target,
            @ToolParam(name = "outputPath", required = false,
                    description = "截图保存路径（绝对或相对工作区根）；不填则默认 screenshot-时间戳.png。")
            String outputPath) {
        if (target == null || target.isBlank()) {
            return Mono.just(ToolResultBlock.text("请提供 target（网页地址或文件路径）。"));
        }
        try {
            String url = resolveTargetUrl(target.strip());
            Path out = resolveOutPath(outputPath);
            ensureInWorkspace(out).getParent();
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            String screenshot = snapshot(url, out);
            return Mono.just(ToolResultBlock.text(
                    "已截屏保存：" + screenshot + "\n可调用 analyze_image 路径=" + screenshot + " 让模型看懂截图内容。"));
        } catch (Exception e) {
            log.warn("screenshot 失败: {}", e.getMessage());
            return Mono.just(ToolResultBlock.text("截图失败: " + e.getMessage()));
        }
    }

    /**
     * 用真·视觉模型解析任意图片并按要求作答（非 DOM/OCR）。path 为图片相对工作区根路径或绝对路径，
     * instruction 描述要看/确认什么。视觉模型未配置时按 provider 回落默认
     * （dashscope→qwen-vl-max；openai 需用 MO_AGENT_VISION_MODEL 显式配置）。
     */
    @Tool(name = "analyze_image",
            description = "把工作区里的一张图片（如 screenshot_webpage 截的 PNG）交给真·视觉大模型，"
                    + "请它描述/核对图片内容。适合查看自己生成的页面渲染效果、确认界面元素与文字是否正确。"
                    + "path=图片绝对路径或相对工作区根路径；instruction=你想让模型看什么/核验什么。返回模型的分析结论。")
    public Mono<ToolResultBlock> analyzeImage(
            @ToolParam(name = "path", description = "图片路径：绝对路径或相对工作区根路径。")
            String path,
            @ToolParam(name = "instruction", description = "要模型看图后回答的问题或核验要求。")
            String instruction) {
        if (path == null || path.isBlank()) {
            return Mono.just(ToolResultBlock.text("请提供图片 path。"));
        }
        try {
            Path img = resolveInWorkspace(path.strip());
            ensureInWorkspace(img);
            if (!Files.isRegularFile(img)) {
                return Mono.just(ToolResultBlock.text("图片不存在或不是普通文件: " + img));
            }
            String reply = askVisionModel(img, instruction == null ? "请描述这张图片的内容。" : instruction);
            return Mono.just(ToolResultBlock.text("视觉模型分析：\n" + reply));
        } catch (Exception e) {
            log.warn("analyze_image 失败: {}", e.getMessage());
            return Mono.just(ToolResultBlock.text("图片解析失败: " + e.getMessage()));
        }
    }

    /**
     * 抓取网页并按 CSS 选择器提取数据。url 必填：http(s) 网址（不写进工作区，直接网络抓取）；
     * selector 可选：CSS 选择器，命中多个节点时逐个取文本拼接，不传则取整页正文 text()；
     * render 可选：是否用无头浏览器抓取 JS 渲染后的 DOM（默认 false，直接请求原始 HTML）。
     * 返回内容截断到 FETCH_CAP 字符上限，防止撑爆上下文。
     */
    @Tool(name = "fetch_webpage",
            description = "抓取一个网页并按 CSS 选择器提取数据。url 为 http(s) 网址；selector 为可选 CSS 选择器"
                    + "（如 'h1'、'.price'、'table tr'），命中多个节点逐个取其文本，不传则返回整页正文；"
                    + "render 为可选布尔值，页面靠 JS 动态渲染时设 true 用无头浏览器抓渲染后的 DOM。"
                    + "返回提取到的文本。")
    public Mono<ToolResultBlock> fetchWebpage(
            @ToolParam(name = "url", description = "要抓取的网页 http(s) 网址，必须以此开头。")
            String url,
            @ToolParam(name = "selector", required = false, description = "CSS 选择器；不填返回整页正文。")
            String selector,
            @ToolParam(name = "render", required = false, description = "页面是否需 JS 渲染后再抓（默认 false）。")
            Boolean render) {
        if (url == null || url.isBlank()) {
            return Mono.just(ToolResultBlock.text("请提供 url（http(s) 网址）。"));
        }
        String u = url.strip();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return Mono.just(ToolResultBlock.text("url 必须是 http(s) 网址，收到：" + u));
        }
        try {
            boolean doRender = render != null && render;
            String html = doRender ? fetchRendered(u, chromePath) : fetchRaw(u);
            Document doc = Jsoup.parse(html);
            String extracted;
            if (selector == null || selector.isBlank()) {
                extracted = doc.body() == null ? doc.text() : doc.body().text();
            } else {
                Elements els = doc.select(selector.strip());
                if (els.isEmpty()) {
                    return Mono.just(ToolResultBlock.text("选择器 '" + selector.strip() + "' 未匹配到任何元素。"));
                }
                List<String> items = new ArrayList<>();
                for (var el : els) {
                    String t = el.text().strip();
                    if (!t.isEmpty()) {
                        items.add(t);
                    }
                }
                extracted = items.isEmpty()
                        ? "选择器 '" + selector.strip() + "' 匹配 " + els.size() + " 个节点，但均无可见文本。"
                        : String.join("\n", items);
            }
            if (extracted.isBlank()) {
                return Mono.just(ToolResultBlock.text("网页抓取成功，但未提取到文本内容。"));
            }
            if (extracted.length() > FETCH_CAP) {
                extracted = extracted.substring(0, FETCH_CAP) + "\n…（内容过长已截断到 " + FETCH_CAP + " 字符）";
            }
            return Mono.just(ToolResultBlock.text("已抓取 " + u + "\n" + extracted));
        } catch (Exception e) {
            log.warn("fetch_webpage 失败: {}", e.getMessage());
            return Mono.just(ToolResultBlock.text("抓取失败: " + e.getMessage()));
        }
    }

    /** 直接 HTTP GET 抓取原始 HTML。 */
    private String fetchRaw(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "（" + resp.uri() + "）");
        }
        return resp.body();
    }

    /** 用无头 Chrome --dump-dom 抓取 JS 渲染后的完整 DOM（HTML）。headless Chrome 偶发
     *  allocator/单例锁瞬时报错，做少量重试以吸收。 */
    private String fetchRendered(String url, String chromePath) throws Exception {
        if (chromePath == null || chromePath.isBlank() || !Files.isExecutable(Path.of(chromePath))) {
            throw new IllegalStateException("找不到无头 Chrome: " + chromePath + "（可用 MO_AGENT_CHROME_PATH 配置）");
        }
        int attempts = 3;
        Exception last = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                return dumpDomOnce(url, chromePath);
            } catch (Exception e) {
                last = e;
                log.warn("Chrome dump-dom 第 {} 次失败: {}", i, e.getMessage());
                if (i < attempts) {
                    Thread.sleep(800L * i);
                }
            }
        }
        throw last != null
                ? new IllegalStateException("Chrome 渲染" + attempts + "次失败：" + truncate(last.getMessage(), 200))
                : new IllegalStateException("Chrome 渲染失败");
    }

    private String dumpDomOnce(String url, String chromePath) throws Exception {
        // 每次调用使用独立临时 user-data-dir，避免与已有 Chrome 实例的 profile/allocator 锁冲突
        Path profileDir = Files.createTempDirectory("mo-chrome-");
        List<String> cmd = new ArrayList<>();
        cmd.add(chromePath);
        cmd.add("--headless=new");
        cmd.add("--user-data-dir=" + profileDir);
        cmd.add("--dump-dom");
        cmd.add(url);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder html = new StringBuilder();
        Thread drain = new Thread(() -> pump(p.getInputStream(), html));
        drain.start();
        if (!p.waitFor(CHROME_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            p.waitFor(5, TimeUnit.SECONDS);
        }
        drain.join(3000);
        try {
            Files.deleteIfExists(profileDir);
        } catch (Exception ignored) {
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("Chrome 渲染失败（exit=" + p.exitValue() + "）：" + truncate(html.toString(), 400)
                    + "\n提示：本机 Chrome 的 --dump-dom 在该运行环境下不可用，可改用 render=false 抓原始 HTML，"
                    + "或改用 screenshot_webpage + analyze_image 查看渲染后的页面。");
        }
        return html.toString();
    }

    /** 把 target 解析成 Chrome 可打开的 URL：http(s) 原样；本机路径转 file://（须在工作区内）。 */
    private String resolveTargetUrl(String raw) {
        String t = raw.trim().replaceAll("^[\"']|[\"']$", "");
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return t;
        }
        Path p = resolveInWorkspace(t);
        ensureInWorkspace(p);
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("要截图的文件不存在或不是普通文件: " + p);
        }
        return p.toUri().toString();
    }

    /** 解析输出路径：绝对路径直接用；相对路径锚定工作区根；空则生成默认截图名。 */
    private Path resolveOutPath(String outputPath) {
        if (outputPath == null || outputPath.isBlank()) {
            return workspace.root().resolve("screenshot-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".png");
        }
        return resolveInWorkspace(outputPath.trim());
    }

    /** 把入参路径解析为绝对路径：已是绝对直接规范化；否则锚定工作区根。 */
    private Path resolveInWorkspace(String raw) {
        Path p = Path.of(raw.trim());
        if (!p.isAbsolute()) {
            p = workspace.root().resolve(p);
        }
        return p.normalize();
    }

    /** 边界守卫：路径必须落在工作区根之内，越界即拒绝。返回归一化后的绝对路径。 */
    private Path ensureInWorkspace(Path target) {
        Path root = workspace.root().toAbsolutePath().normalize();
        Path abs = target.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            throw new IllegalArgumentException("路径越界工作区，已拒绝: " + abs);
        }
        return abs;
    }

    /** 调无头 Chrome 渲染并落盘 PNG，返回实际保存路径。 */
    private String snapshot(String url, Path out) {
        if (chromePath == null || chromePath.isBlank() || !Files.isExecutable(Path.of(chromePath))) {
            throw new IllegalStateException("找不到无头 Chrome: " + chromePath + "（可用 MO_AGENT_CHROME_PATH 配置）");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(chromePath);
        cmd.add("--headless=new");
        cmd.add("--disable-gpu");
        cmd.add("--hide-scrollbars");
        cmd.add("--force-device-scale-factor=1");
        cmd.add("--window-size=1440,960");
        cmd.add("--virtual-time-budget=1200");
        cmd.add("--screenshot=" + out.toAbsolutePath());
        cmd.add(url);
        log.info("[screenshot_webpage] chrome target={}", url);
        String dump;
        int code;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder logs = new StringBuilder();
            Thread drain = new Thread(() -> pump(p.getInputStream(), logs));
            drain.start();
            if (!p.waitFor(CHROME_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            drain.join(3000);
            code = p.exitValue();
            dump = logs.toString();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Chrome 截图执行异常: " + e.getMessage());
        }
        long size;
        try {
            size = Files.size(out);
        } catch (Exception e) {
            size = -1;
        }
        if (code != 0 || !Files.exists(out) || size <= 0) {
            throw new IllegalStateException("Chrome 截图失败（exit=" + code + "）：" + truncate(dump, 600));
        }
        return out.toAbsolutePath().toString();
    }

    private static void pump(InputStream in, StringBuilder sink) {
        byte[] buf = new byte[4096];
        try (InputStream is = in) {
            int n;
            while ((n = is.read(buf)) >= 0) {
                sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** 原生 OpenAI 兼容视觉请求：把图片 base64 塞进 image_url content，拿视觉模型答复。 */
    private String askVisionModel(Path img, String instruction) throws Exception {
        String model = resolveVisionModel();
        // 组装 OpenAI 兼容请求体
        String mime = mimeOf(img);
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(img));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("model", model);
        List<Map<String, Object>> msgs = new ArrayList<>();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        List<Object> content = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", instruction);
        content.add(textPart);
        Map<String, Object> imgPart = new LinkedHashMap<>();
        imgPart.put("type", "image_url");
        Map<String, Object> iu = new LinkedHashMap<>();
        iu.put("url", "data:" + mime + ";base64," + b64);
        imgPart.put("image_url", iu);
        content.add(imgPart);
        user.put("content", content);
        msgs.add(user);
        root.put("messages", msgs);
        String body = OM.writeValueAsString(root);

        String endpoint = endpoint();
        String key = (provider.equalsIgnoreCase("openai")) ? openaiKey : dashKey;
        log.info("[analyze_image] provider={} model={} endpoint={} imgSize={}b", provider, model, endpoint, Files.size(img));
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(provider + " 的 API Key 未配置，无法调用视觉模型");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("视觉模型接口错误 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 500));
        }
        JsonNode node = OM.readTree(resp.body());
        JsonNode msg = node.path("choices").path(0).path("message");
        String text = msg.path("content").asText("");
        if (!text.isBlank()) {
            return text.strip();
        }
        // 部分实现返回 reasoning_content：兜底拼接
        String rc = msg.path("reasoning_content").asText("");
        return rc.isBlank() ? "（模型未返回可展示文本）" : rc.strip();
    }

    private String resolveVisionModel() {
        if (visionModelCfg != null && !visionModelCfg.isBlank()) {
            return visionModelCfg.strip();
        }
        if (provider.equalsIgnoreCase("openai")) {
            throw new IllegalStateException("openai provider 未配置视觉模型，请设置 MO_AGENT_VISION_MODEL");
        }
        return "qwen-vl-max";
    }

    private String endpoint() {
        if (provider.equalsIgnoreCase("openai")) {
            String base = (openaiBaseUrl == null || openaiBaseUrl.isBlank())
                    ? "https://api.deepseek.com" : openaiBaseUrl.strip();
            return base.replaceAll("/+$", "") + "/chat/completions";
        }
        // dashscope OpenAI 兼容端点
        return "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    }

    private static String mimeOf(Path p) {
        try {
            String c = Files.probeContentType(p);
            if (c != null && !c.isBlank()) return c;
        } catch (Exception ignored) {
        }
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}