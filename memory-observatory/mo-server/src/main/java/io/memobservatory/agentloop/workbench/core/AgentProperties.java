/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】AgentProperties.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】工作台 Agent 的配置实体（POJO）：从环境变量读取模型 provider / 密钥、
 *            超时、工作区根目录；唯一行为是按 userId 规范化独立工作区路径。
 * 【核心改动】
 *   2026-08-23 迁入 mo-server 单进程；模型装配职责抽离至 ModelFactory。
 * 【设计要点】纯配置无行为、低耦合；模型选择不在此处判断。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 工作台 Agent 配置项。绑定 {@code mo.agent.*}（见 application.yml），
 * 可用环境变量占位符覆盖。纯配置实体（POJO），不承载任何行为；模型装配见 {@link ModelFactory}。
 */
@ConfigurationProperties(prefix = "mo.agent")
public class AgentProperties {

    /**
     * 密钥/配置兜底文件：{@code ~/.mo-agent-workbench/agent.env}（多行 {@code KEY=VALUE}，支持 # 注释）。
     * 读取顺序：环境变量 → 本文件 → 内置默认值。这样即使工具终端不继承 shell 的 export，
     * 服务器每次重启也能从本地配置读到密钥，无需每次启动都传参。
     */
    private static final Path ENV_FILE = Path.of(System.getProperty("user.home"), ".mo-agent-workbench", "agent.env");
    private static final Map<String, String> FILE_ENV = loadEnvFile();

    /** "dashscope"（Qwen）或 "openai"（DeepSeek/GLM 等 OpenAI 兼容）。 */
    private String provider = val("MO_AGENT_PROVIDER", "dashscope");

    private String dashscopeApiKey = val("DASHSCOPE_API_KEY", null);
    private String dashscopeModel = val("DASHSCOPE_MODEL", "qwen3-max");

    private String openaiApiKey = val("LLM_API_KEY", null);
    private String openaiModel = val("LLM_MODEL", "deepseek-chat");
    private String openaiBaseUrl = val("LLM_BASE_URL", "https://api.deepseek.com");

    /** 模型元数据接口地址（可选）：用于运行时拉取真实输入上下文窗口。未配时不拉取。 */
    private String modelMetaUrl = val("MO_AGENT_MODEL_META_URL", null);

    /** 单次回复输出上限（token）。抬升可避免模型按默认 max_tokens 把长回复截断；
     *  受模型自身支持上限钳制（如 DeepSeek 单次最大约 8K）。默认不显式设置(走模型默认)。 */
    private Integer maxOutputTokens = nullableIntVal("MO_AGENT_MAX_OUTPUT_TOKENS");

    /** bash 超时；工具超时；写/命令类工具不重试，模型调用可重试。 */
    private Duration bashTimeout = Duration.ofSeconds(30);

    /** read_file 大文件防护：超过该行数则不再整体读入上下文（默认 500 行）。 */
    private int readFileLineCap = 500;

    /** read_file 大文件仅返回前 N 行头部 + grep 引导（默认 200 行）。 */
    private int readFileHeadLines = 200;

    /** [工具进度] 日志开关：每工具开始/完成各打一行，便于实时定位当前执行阶段（默认开）。 */
    private boolean toolProgressLog = true;

    /** 模型名→输入上下文窗口（token）映射表。可从配置/环境变量覆盖（默认统一 1M），
     *  供 ModelWindowResolver 运行时锚定窗口。 */
    private Map<String, Integer> modelWindows = defaultModelWindows();

    private String tavilyApiKey = val("TAVILY_API_KEY", null);

    /** 各用户工作区根目录（相对此目录再按 userId 分目录）。 */
    private Path workspaceBase = Path.of(System.getProperty("user.home"), ".mo-agent-workbench");

    /** 视觉模型名（解析截图/图片用，须支持图像输入）：未配时按 provider 回落（dashscope→qwen-vl-max；openai 需显式配置）。 */
    private String visionModel = val("MO_AGENT_VISION_MODEL", null);

    /** 无头 Chrome 可执行文件路径，用于工作区网页截图。 */
    private String chromePath = val("MO_AGENT_CHROME_PATH", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");

    public String getProvider() {
        return firstNonBlank(provider, FILE_ENV.get("MO_AGENT_PROVIDER"));
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getDashscopeApiKey() {
        return firstNonBlank(dashscopeApiKey, FILE_ENV.get("DASHSCOPE_API_KEY"));
    }

    public void setDashscopeApiKey(String dashscopeApiKey) {
        this.dashscopeApiKey = dashscopeApiKey;
    }

    public String getDashscopeModel() {
        return firstNonBlank(dashscopeModel, FILE_ENV.get("DASHSCOPE_MODEL"));
    }

    public void setDashscopeModel(String dashscopeModel) {
        this.dashscopeModel = dashscopeModel;
    }

    public String getOpenaiApiKey() {
        return firstNonBlank(openaiApiKey, FILE_ENV.get("LLM_API_KEY"));
    }

    public void setOpenaiApiKey(String openaiApiKey) {
        this.openaiApiKey = openaiApiKey;
    }

    public String getOpenaiModel() {
        return firstNonBlank(openaiModel, FILE_ENV.get("LLM_MODEL"));
    }

    public void setOpenaiModel(String openaiModel) {
        this.openaiModel = openaiModel;
    }

    public String getOpenaiBaseUrl() {
        return firstNonBlank(openaiBaseUrl, FILE_ENV.get("LLM_BASE_URL"));
    }

    public void setOpenaiBaseUrl(String openaiBaseUrl) {
        this.openaiBaseUrl = openaiBaseUrl;
    }

    public String getModelMetaUrl() {
        return firstNonBlank(modelMetaUrl, FILE_ENV.get("MO_AGENT_MODEL_META_URL"));
    }

    public void setModelMetaUrl(String modelMetaUrl) {
        this.modelMetaUrl = modelMetaUrl;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Map<String, Integer> getModelWindows() {
        return modelWindows;
    }

    public void setModelWindows(Map<String, Integer> modelWindows) {
        this.modelWindows = modelWindows;
    }

    /** 默认模型映射表：常用模型统一锚定 1M 输入窗口，后续可按实配覆盖。 */
    private static Map<String, Integer> defaultModelWindows() {
        Map<String, Integer> m = new HashMap<>();
        int oneM = 1_000_000;
        String[] names = {
                "qwen3-max", "qwen-max", "qwen-plus", "qwen-turbo",
                "deepseek-chat", "deepseek-reasoner",
                "glm-4-plus", "glm-4.5", "gpt-4o"};
        for (String n : names) {
            m.put(n, oneM);
        }
        return m;
    }

    public Duration getBashTimeout() {
        return bashTimeout;
    }

    public void setBashTimeout(Duration bashTimeout) {
        this.bashTimeout = bashTimeout;
    }

    public int getReadFileLineCap() {
        return readFileLineCap;
    }

    public void setReadFileLineCap(int readFileLineCap) {
        this.readFileLineCap = readFileLineCap;
    }

    public int getReadFileHeadLines() {
        return readFileHeadLines;
    }

    public void setReadFileHeadLines(int readFileHeadLines) {
        this.readFileHeadLines = readFileHeadLines;
    }

    public boolean isToolProgressLog() {
        return toolProgressLog;
    }

    public void setToolProgressLog(boolean toolProgressLog) {
        this.toolProgressLog = toolProgressLog;
    }

    public String getTavilyApiKey() {
        return firstNonBlank(tavilyApiKey, FILE_ENV.get("TAVILY_API_KEY"));
    }

    public void setTavilyApiKey(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    public Path getWorkspaceBase() {
        return workspaceBase;
    }

    public String getVisionModel() {
        return firstNonBlank(visionModel, FILE_ENV.get("MO_AGENT_VISION_MODEL"));
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getChromePath() {
        return firstNonBlank(chromePath, FILE_ENV.get("MO_AGENT_CHROME_PATH"));
    }

    public void setChromePath(String chromePath) {
        this.chromePath = chromePath;
    }

    public void setWorkspaceBase(Path workspaceBase) {
        this.workspaceBase = workspaceBase;
    }

    /** 每个用户独立工作区根目录。 */
    public Path workspaceRootFor(String userId) {
        return workspaceBase.resolve(sanitize(userId));
    }

    /**
     * 解析本次会话的工作区根目录：优先使用用户指定的路径（须是已存在的目录），
     * 否则回落到按 userId 推导的默认根。校验失败抛 {@link IllegalArgumentException}，
     * 保证 Agent 的文件/命令工具只会落在这个用户指定目录之内。
     */
    public Path workspaceRootFor(String userId, String requestedWorkspace) {
        if (requestedWorkspace == null || requestedWorkspace.isBlank()) {
            return workspaceRootFor(userId);
        }
        Path root = Path.of(requestedWorkspace).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "工作区路径不存在或不是目录: " + requestedWorkspace);
        }
        return root;
    }

    /** 把 userId 规范化成安全的目录名（防路径穿越）。 */
    private static String sanitize(String userId) {
        if (userId == null || userId.isBlank()) return "anonymous";
        return userId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 读取顺序：环境变量 → 兜底文件 → 默认值；某级为空则继续往下取。 */
    private static String val(String key, String def) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;
        String file = FILE_ENV.get(key);
        if (file != null && !file.isBlank()) return file;
        return def;
    }

    /** 解析可空整型配置：未配置/非法时返回 null（表示不显式设置，走模型默认上限）。 */
    private static Integer nullableIntVal(String key) {
        String s = val(key, null);
        if (s == null || s.isBlank()) return null;
        try {
            Integer v = Integer.valueOf(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 返回首个非空值；都为空返回 null（字段可能被 Spring 绑定成空串时，回落文件配置）。 */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : a;
    }

    /** 懒加载读取兜底配置文件，解析 {@code KEY=VALUE} 行（# 注释忽略），文件不存在/读失败时返回空表。 */
    private static Map<String, String> loadEnvFile() {
        Map<String, String> m = new HashMap<>();
        try {
            if (!Files.exists(ENV_FILE)) return m;
            for (String line : Files.readAllLines(ENV_FILE, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                int i = line.indexOf('=');
                m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (Exception e) {
            // 兜底文件不可用不影响启动
        }
        return m;
    }
}