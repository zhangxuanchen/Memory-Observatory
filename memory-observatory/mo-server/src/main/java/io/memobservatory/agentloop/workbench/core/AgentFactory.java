/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】AgentFactory.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】ReActAgent 装配工厂：从工作区解析边界根，按 Agent 清单装配工具（受 tools
 *            开关）、系统提示（拼 skills 片段）、模型（清单可覆盖），并按 priority 挂载
 *            hooks（MiddlewareBase）；保持旁路记忆采集中间件。
 * 【核心改动】2026-08-23 迁入 mo-server；从按 userId 推导根改为按工作区 Agent 清单装配
 *            （workspace-agent-design.md 第 3.3 节）。
 * 【设计要点】Agent/Toolkit 有状态不可并发复用 → 每请求 create() 新建独立实例。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.Toolkit;
import io.memobservatory.agentloop.workbench.hitl.UserInteractionHub;
import io.memobservatory.agentloop.workbench.hitl.ContextQueue;
import io.memobservatory.agentloop.workbench.tools.AskUserTool;
import io.memobservatory.agentloop.workbench.tools.BashTool;
import io.memobservatory.agentloop.workbench.tools.FileTools;
import io.memobservatory.agentloop.workbench.tools.ManagerOrchestratorTool;
import io.memobservatory.agentloop.workbench.tools.MemoryArchiveTool;
import io.memobservatory.agentloop.workbench.tools.UserContext;
import io.memobservatory.agentloop.workbench.tools.WebCaptureTool;
import io.memobservatory.agentloop.workbench.tools.WebSearchTool;
import io.memobservatory.agentloop.workflow.BuildVerifier;
import io.memobservatory.agentloop.workspace.Workspace;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import io.memobservatory.agentloop.workspace.ContentItem;
import io.memobservatory.agentloop.workspace.ContentLibrary;
import io.memobservatory.agentloop.workspace.SkillLibrary;
import io.memobservatory.agentloop.workspace.SkillView;
import io.memobservatory.agentloop.workspace.UserSkill;
import io.memobservatory.agentloop.workspace.mcp.McpRegistry;
import io.memobservatory.agentloop.workspace.manifest.AgentManifest;
import io.memobservatory.agentloop.workspace.plugin.HookPlugin;
import io.memobservatory.agentloop.workspace.plugin.PluginRegistry;
import io.memobservatory.agentloop.workspace.plugin.SkillContext;
import io.memobservatory.agentloop.workspace.plugin.SkillPlugin;
import io.memobservatory.agentloop.workspace.plugin.SkillToolFactory;
import io.memobservatory.agentloop.workspace.plugin.YamlSkillPlugin;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装配 ReActAgent。核心约束：Agent 与 Toolkit 有状态、不可并发复用，
 * 因此每个请求调用 {@link #create} 新建独立实例。工作区边界由清单决定，
 * 目录校验复用在 WorkspaceManager；Agent 影响面严格等于工作区根。
 */
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final AgentProperties props;
    private final ModelFactory modelFactory;

    /** 全局默认 AK：界面配置（GlobalKeyStore）优先，其次环境变量/兜底文件（props）。 */
    private String globalAk(String provider) {
        String ui = GlobalKeyStore.plain(provider);
        if (ui != null && !ui.isBlank()) return ui;
        return "openai".equals(provider) ? props.getOpenaiApiKey() : props.getDashscopeApiKey();
    }
    private final UserInteractionHub interactionHub;
    private final WorkspaceManager workspaceManager;
    private final PluginRegistry pluginRegistry;
    /** 能力包库：为静态插件之外的动态 yaml 技能提供解析来源。 */
    private final SkillLibrary skillLibrary;
    /** 预装内容库：规则 / 记忆 / 钩子的「全局预装基线 + 工作区合并」装载来源。 */
    private final ContentLibrary content;
    /** 动态能力包装配内置工具用的工厂（FileTools / BashTool / WebSearchTool）。 */
    private final SkillToolFactory skillToolFactory;
    /** 旁路记忆观测中间件（可为 null，不挂则无采集上报）。 */
    private final MiddlewareBase memoryMiddleware;

    /** 会话上下文管理（预算超限后触发四轮自收敛压缩）。 */
    private final ConversationMemory conversationMemory;
    /** 会话摘要器（压缩时调用模型重摘要；可为 null 则回退规则截断）。 */
    private final ContextSummarizer contextSummarizer;
    /** 真实构建门禁校验器（工作区经理编排工具用）；可为 null。 */
    private final BuildVerifier buildVerifier;
    /** 会话补充上下文队列（工作区经理派发子 Agent 时清空其排队补充并入上下文）；可为 null。 */
    private final ContextQueue contextQueue;
    /** 统一单轮执行入口（装配给工作区经理编排工具跑派发子 Agent；与 /chat 共用同一执行路径）。 */
    private final SingleTurnExecutor turnRunner;
    /** 加密密钥库：按 Agent 维度取绑定的模型 AK（未绑定回落全局）。 */
    private final AgentKeyStore agentKeyStore;
    /** MCP Client 注册中心：skill tools[] 里 mcp:serverId/toolName 的解析来源。 */
    private final McpRegistry mcpRegistry;

    private AgentFactory(AgentProperties props,
                     ModelFactory modelFactory,
                     UserInteractionHub interactionHub,
                     WorkspaceManager workspaceManager,
                     PluginRegistry pluginRegistry,
                     SkillLibrary skillLibrary,
                     ContentLibrary content,
                     MiddlewareBase memoryMiddleware,
                     ConversationMemory conversationMemory,
                     ContextSummarizer contextSummarizer,
                     BuildVerifier buildVerifier,
                     ContextQueue contextQueue,
                     SingleTurnExecutor turnRunner,
                     AgentKeyStore agentKeyStore,
                     McpRegistry mcpRegistry) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.interactionHub = interactionHub;
        this.workspaceManager = workspaceManager;
        this.pluginRegistry = pluginRegistry;
        this.skillLibrary = skillLibrary;
        this.content = content;
        this.skillToolFactory = new SkillToolFactory() {
            @Override
            public Object fileTools() {
                return new FileTools(props.getReadFileLineCap(), props.getReadFileHeadLines());
            }

            @Override
            public Object bashTool() {
                return new BashTool(props.getBashTimeout());
            }

            @Override
            public Object webSearchTool() {
                return new WebSearchTool(props.getTavilyApiKey());
            }
        };
        this.memoryMiddleware = memoryMiddleware;
        this.conversationMemory = conversationMemory;
        this.mcpRegistry = mcpRegistry;
        this.contextSummarizer = contextSummarizer;
        this.buildVerifier = buildVerifier;
        this.contextQueue = contextQueue;
        this.turnRunner = turnRunner;
        this.agentKeyStore = agentKeyStore;
    }

    /** 装配参数较多，统一走 Builder 以避免长参数列表（高内聚、可读、扩展友好）。 */
    public static Builder builder() {
        return new Builder();
    }

    /** {@link AgentFactory} 装配器：必填依赖集中校验，可选依赖（memoryMiddleware / buildVerifier /
     *  contextQueue / turnRunner）缺省为 null。 */
    public static class Builder {
        private AgentProperties props;
        private ModelFactory modelFactory;
        private UserInteractionHub interactionHub;
        private WorkspaceManager workspaceManager;
        private PluginRegistry pluginRegistry;
        private SkillLibrary skillLibrary;
        private ContentLibrary content;
        private MiddlewareBase memoryMiddleware;
        private ConversationMemory conversationMemory;
        private ContextSummarizer contextSummarizer;
        private BuildVerifier buildVerifier;
        private ContextQueue contextQueue;
        private SingleTurnExecutor turnRunner;
        private AgentKeyStore agentKeyStore;
        private McpRegistry mcpRegistry;

        public Builder props(AgentProperties v) {
            this.props = v;
            return this;
        }

        public Builder modelFactory(ModelFactory v) {
            this.modelFactory = v;
            return this;
        }

        public Builder interactionHub(UserInteractionHub v) {
            this.interactionHub = v;
            return this;
        }

        public Builder workspaceManager(WorkspaceManager v) {
            this.workspaceManager = v;
            return this;
        }

        public Builder pluginRegistry(PluginRegistry v) {
            this.pluginRegistry = v;
            return this;
        }

        public Builder skillLibrary(SkillLibrary v) {
            this.skillLibrary = v;
            return this;
        }

        public Builder content(ContentLibrary v) {
            this.content = v;
            return this;
        }

        public Builder memoryMiddleware(MiddlewareBase v) {
            this.memoryMiddleware = v;
            return this;
        }

        public Builder conversationMemory(ConversationMemory v) {
            this.conversationMemory = v;
            return this;
        }

        public Builder contextSummarizer(ContextSummarizer v) {
            this.contextSummarizer = v;
            return this;
        }

        public Builder buildVerifier(BuildVerifier v) {
            this.buildVerifier = v;
            return this;
        }

        public Builder contextQueue(ContextQueue v) {
            this.contextQueue = v;
            return this;
        }

        public Builder turnRunner(SingleTurnExecutor v) {
            this.turnRunner = v;
            return this;
        }

        public Builder agentKeyStore(AgentKeyStore v) {
            this.agentKeyStore = v;
            return this;
        }

        public Builder mcpRegistry(McpRegistry v) {
            this.mcpRegistry = v;
            return this;
        }
        public AgentFactory build() {
            return new AgentFactory(props, modelFactory, interactionHub, workspaceManager,
                    pluginRegistry, skillLibrary, content, memoryMiddleware, conversationMemory,
                    contextSummarizer, buildVerifier, contextQueue, turnRunner, agentKeyStore,
                    mcpRegistry);
        }
    }

    /**
     * 为指定（workspaceId, agentId, sessionId, userId）创建一个全新的 ReActAgent 实例。
     * 工作区根由清单决定，缺省回落按 userId 推导；绝不并发复用。
     */
    public HarnessAgent create(String workspaceId, String agentId, String sessionId, String userId)
            throws IOException {
        return create(workspaceId, agentId, sessionId, userId, null);
    }

    /**
     * 同 {@link #create(String, String, String, String)}，额外注入该会话的历史上下文
     * （运行摘要 + 最近轮），让无状态 Agent 在多轮之间保持连续性（会话上下文管理）。
     *
     * @param memoryContext 会话历史上下文块；null 表示无记忆注入
     */
    public HarnessAgent create(String workspaceId, String agentId, String sessionId, String userId,
                             String memoryContext) throws IOException {
        Workspace ws = workspaceManager.byId(workspaceId);
        AgentManifest m = workspaceManager.readAgent(ws, agentId);
        String memKey = ConversationMemory.key(workspaceId, agentId, sessionId);
        return build(ws, m, sessionId, userId, null, false, props.getBashTimeout(),
                memoryContext, memKey);
    }

    /**
     * 同 {@link #create(String, String, String, String, String)}，额外注入一个进度回传通道，
     * 供工作区经理（admin）编排工具在派发/验收期间向经理会话 SSE 推进度（保持连接活跃）。
     *
     * @param progressSink 进度文本回传；可为 null 表示静默（普通 Agent 线程池可直接传 null）
     */
    public HarnessAgent create(String workspaceId, String agentId, String sessionId, String userId,
                             String memoryContext, java.util.function.Consumer<String> progressSink)
            throws IOException {
        return create(workspaceId, agentId, sessionId, userId, memoryContext, progressSink, null);
    }

    /**
     * 同 6 参重载，额外注入目标 Agent 运行态回传（agentId, running），
     * 供前端在 Agent 按钮上显示“运行中…”，覆盖经理 run_worker/run_parallel 派发的目标 Agent。
     *
     * @param runningSink 运行态回传；(marker：id, running)。可为 null。
     */
    public HarnessAgent create(String workspaceId, String agentId, String sessionId, String userId,
                             String memoryContext, java.util.function.Consumer<String> progressSink,
                             java.util.function.BiConsumer<String, Boolean> runningSink)
            throws IOException {
        Workspace ws = workspaceManager.byId(workspaceId);
        AgentManifest m = workspaceManager.readAgent(ws, agentId);
        String memKey = ConversationMemory.key(workspaceId, agentId, sessionId);
        return build(ws, m, sessionId, userId, null, false, props.getBashTimeout(),
                memoryContext, memKey, progressSink, runningSink);
    }

    /**
     * 同 {@link #create(String, String, String, String, String)}，额外注入进度、目标 Agent 运行态与
     * 思维链 plan 事件三条回传通道。仅供工作区经理（admin）编排工具注入用；普通 Agent 传 null 即可。
     *
     * @param planSink 思维链结构化事件回传（建节点/状态/一句思考）；可为 null 表示静默
     */
    public HarnessAgent create(String workspaceId, String agentId, String sessionId, String userId,
                             String memoryContext, java.util.function.Consumer<String> progressSink,
                             java.util.function.BiConsumer<String, Boolean> runningSink,
                             java.util.function.Consumer<String> planSink)
            throws IOException {
        Workspace ws = workspaceManager.byId(workspaceId);
        AgentManifest m = workspaceManager.readAgent(ws, agentId);
        String memKey = ConversationMemory.key(workspaceId, agentId, sessionId);
        return build(ws, m, sessionId, userId, null, false, props.getBashTimeout(),
                memoryContext, memKey, progressSink, runningSink, planSink);
    }
    public String sessionMemoryContext(String workspaceId, String agentId, String sessionId) {
        return conversationMemory.context(ConversationMemory.key(workspaceId, agentId, sessionId));
    }

    /**
     * 把经理派发的一段「工作指令/答复」写入目标 Agent 会话记忆并持久化，
     * 使其会话上下文面板可见、后续派发可延续。异常仅 WARN 不阻断。
     */
    public void recordSessionTurn(String workspaceId, String agentId, String sessionId,
                                  String userMsg, String reply) {
        try {
            conversationMemory.append(ConversationMemory.key(workspaceId, agentId, sessionId),
                    userMsg == null ? "" : userMsg,
                    reply == null ? "" : reply, contextSummarizer);
        } catch (Exception e) {
            log.warn("记录目标 Agent 会话上下文失败（不阻断）: {}", e.toString());
        }
    }

    /**
     * 为工作流创建「角色 Agent」：复用模板清单（java-mvn / frontend），追加角色提示作为最终
     * 系统提示，并支持只读开关。只读角色仅装配 readFile / webSearch / askUser，禁止写代码。
     *
     * @param workspaceId    工作区 id
     * @param templateAgentId 模板清单 id（须已预置在 $ROOT/.workbench/agents/ 下）
     * @param sessionId      会话 id（ask_user 绑定此会话）
     * @param userId         用户 id
     * @param rolePrompt     角色系统提示（追加为最终提示）
     * @param readOnly       true=只读角色（不装写/命令工具）
     * @param bashTimeout    bash 工具超时（跑 mvn/npm 构建需放宽）
     */
    public HarnessAgent createRole(String workspaceId, String templateAgentId, String sessionId,
                                 String userId, String rolePrompt, boolean readOnly, Duration bashTimeout)
            throws IOException {
        Workspace ws = workspaceManager.byId(workspaceId);
        AgentManifest m = workspaceManager.readAgent(ws, templateAgentId);
        return build(ws, m, sessionId, userId, rolePrompt, readOnly, bashTimeout);
    }

    /** 工作流角色走默认 bash 超时（等价于普通 Agent）。 */
    public HarnessAgent createRole(String workspaceId, String templateAgentId, String sessionId,
                                 String userId, String rolePrompt, boolean readOnly)
            throws IOException {
        return createRole(workspaceId, templateAgentId, sessionId, userId, rolePrompt, readOnly,
                props.getBashTimeout());
    }

    /**
     * 装配核心：按清单 + 角色覆盖构建一个独立 ReActAgent 实例。
     *
     * @param ws           工作区（决定影响边界根）
     * @param m            模板/普通 Agent 清单
     * @param sessionId    会话 id
     * @param userId       用户 id
     * @param extraPrompt  角色追加提示；null 表示普通 Agent（无追加）
     * @param readOnly     只读角色开关（只开 readFile/webSearch/askUser）
     * @param bashTimeout  bash 工具超时
     */
    private HarnessAgent build(Workspace ws, AgentManifest m, String sessionId, String userId,
                             String extraPrompt, boolean readOnly, Duration bashTimeout)
            throws IOException {
        return build(ws, m, sessionId, userId, extraPrompt, readOnly, bashTimeout, null, null);
    }

    /**
     * 装配核心：按清单 + 角色覆盖构建一个独立 ReActAgent 实例。
     *
     * @param ws            工作区（决定影响边界根）
     * @param m             模板/普通 Agent 清单
     * @param sessionId     会话 id
     * @param userId        用户 id
     * @param extraPrompt   角色追加提示；null 表示普通 Agent（无追加）
     * @param readOnly      只读角色开关（只开 readFile/webSearch/askUser）
     * @param bashTimeout   bash 工具超时
     * @param memoryContext 会话历史上下文块；null 表示无记忆注入
     * @param memKey        会话上下文键（预算超限后定位自收敛目标状态）；null 表示不触发收敛
     */
    private HarnessAgent build(Workspace ws, AgentManifest m, String sessionId, String userId,
                             String extraPrompt, boolean readOnly, Duration bashTimeout,
                             String memoryContext, String memKey)
            throws IOException {
        return build(ws, m, sessionId, userId, extraPrompt, readOnly, bashTimeout,
                memoryContext, memKey, null);
    }

    /** 同 9 参 {@link #build(Workspace, AgentManifest, String, String, String, boolean, Duration, String, String)}，
     *  额外透传进度回传通道（仅供 admin 经理编排工具使用；普通 Agent 传 null）。 */
    private HarnessAgent build(Workspace ws, AgentManifest m, String sessionId, String userId,
                             String extraPrompt, boolean readOnly, Duration bashTimeout,
                             String memoryContext, String memKey,
                             java.util.function.Consumer<String> progressSink)
            throws IOException {
        return build(ws, m, sessionId, userId, extraPrompt, readOnly, bashTimeout,
                memoryContext, memKey, progressSink, null);
    }

    /** 同 10 参重载，额外透传目标 Agent 运行态回传（供前端 Agent 按钮“运行中…”，普通 Agent 传 null）。 */
    private HarnessAgent build(Workspace ws, AgentManifest m, String sessionId, String userId,
                             String extraPrompt, boolean readOnly, Duration bashTimeout,
                             String memoryContext, String memKey,
                             java.util.function.Consumer<String> progressSink,
                             java.util.function.BiConsumer<String, Boolean> runningSink)
            throws IOException {
        return build(ws, m, sessionId, userId, extraPrompt, readOnly, bashTimeout,
                memoryContext, memKey, progressSink, runningSink, null);
    }

    /** 同 11 参重载，额外透传思维链 plan 事件回传（仅 admin 经理编排工具使用；普通 Agent 传 null）。 */
    private HarnessAgent build(Workspace ws, AgentManifest m, String sessionId, String userId,
                             String extraPrompt, boolean readOnly, Duration bashTimeout,
                             String memoryContext, String memKey,
                             java.util.function.Consumer<String> progressSink,
                             java.util.function.BiConsumer<String, Boolean> runningSink,
                             java.util.function.Consumer<String> planSink)
            throws IOException {
        var workspaceRoot = ws.root();
        Files.createDirectories(workspaceRoot);

        // 1. 工具集：按清单 tools 开关装配（AskUserTool 恒绑定本次会话）
        //    只读角色仅暴露 readFile / webSearch / askUser，杜绝写/命令工具。
        Toolkit toolkit = new Toolkit();
        AgentManifest.ToolFlags tf = m.tools() != null ? m.tools() : AgentManifest.ToolFlags.defaults();
        if (readOnly) {
            toolkit.registerTool(new FileTools(props.getReadFileLineCap(), props.getReadFileHeadLines()));
            if (tf.webSearch()) {
                toolkit.registerTool(new WebSearchTool(props.getTavilyApiKey()));
            }
            toolkit.registerTool(new AskUserTool(interactionHub, sessionId));
            // 记忆召回：只读，工作流角色暂无可回找归档（memKey 为 null 时工具报错提示）
            toolkit.registerTool(new MemoryArchiveTool(conversationMemory, memKey));
        } else {
            if (tf.readFile() || tf.writeFile() || tf.editFile()) {
                toolkit.registerTool(new FileTools(props.getReadFileLineCap(), props.getReadFileHeadLines()));
            }
            if (tf.bash()) {
                toolkit.registerTool(new BashTool(bashTimeout));
            }
            if (tf.webSearch()) {
                toolkit.registerTool(new WebSearchTool(props.getTavilyApiKey()));
            }
            toolkit.registerTool(new AskUserTool(interactionHub, sessionId));
            // 记忆召回：让 Agent 能按折叠标记里的 id 找回被压缩的完整原文
            toolkit.registerTool(new MemoryArchiveTool(conversationMemory, memKey));
        }

        // 该 Agent 绑定的模型 AK（未绑定回落全局默认）：装配模型与截图视觉工具都用到，提前加载复用
        Map<String, String> agentAk = (agentKeyStore == null)
                ? Map.of()
                : agentKeyStore.loadPlain(ws, m.id());

        // 工作区经理（admin）：装配多 Agent 协作编排工具 + 网页截图/真图解析工具，并可驱动其他 Agent 闭环交付
        boolean adminAgent = m.admin() && !readOnly;
        if (adminAgent) {
            String mgrProvider = (m.model() != null && m.model().provider() != null && !m.model().provider().isBlank())
                    ? m.model().provider() : props.getProvider();
            // 全局默认取钥：界面配置（GlobalKeyStore）优先，其次环境变量/兜底文件（props）
            String mgrDashKey = (agentAk.get("dashscope") != null && !agentAk.get("dashscope").isBlank())
                    ? agentAk.get("dashscope") : globalAk("dashscope");
            String mgrOpenaiKey = (agentAk.get("openai") != null && !agentAk.get("openai").isBlank())
                    ? agentAk.get("openai") : globalAk("openai");
            toolkit.registerTool(new WebCaptureTool(ws, mgrProvider, mgrDashKey, mgrOpenaiKey,
                    props.getOpenaiBaseUrl(), props.getVisionModel(), props.getChromePath()));
            toolkit.registerTool(new ManagerOrchestratorTool(this, workspaceManager, ws,
                    m.id(), sessionId, userId, turnRunner, contextQueue, buildVerifier, progressSink, runningSink, planSink));
        }

        // 2. skills：Agent 启动即自动装载三级渐进披露（全局技能 + 当前工作区技能 + Agent 自身技能），
        //    清单 enable 的技能也并入（按 id 去重）。注册自带工具 + 追加提示片段。
        //    只读角色跳过 skills，避免意外引入写/命令类工具。
        //    解析顺序：静态插件优先（pluginRegistry），未命中再回落动态 yaml 技能（SkillLibrary，自身→工作区→全局）。
        StringBuilder promptBuf = new StringBuilder();
        if (!readOnly) {
            Set<String> ids = new LinkedHashSet<>();
            try {
                for (SkillView sv : skillLibrary.global()) {
                    if (sv != null && sv.id() != null) ids.add(sv.id());
                }
                for (SkillView sv : skillLibrary.workspace(ws)) {
                    if (sv != null && sv.id() != null) ids.add(sv.id());
                }
                for (SkillView sv : skillLibrary.agent(ws, m.id())) {
                    if (sv != null && sv.id() != null) ids.add(sv.id());
                }
            } catch (Exception e) {
                log.warn("读取全局/工作区/自身默认技能失败: {}", e.getMessage());
            }
            if (m.skills() != null) {
                for (AgentManifest.SkillRef ref : m.skills()) {
                    if (ref != null && ref.enabled() && ref.id() != null) ids.add(ref.id());
                }
            }
            // 逐个 id 实例化能力包并装配：
            //   ids 是去重后的技能集合（全局 + 工作区 + Agent 自身 + 清单 enable 的技能）。
            //   1) 先查静态插件注册表 pluginRegistry —— 代码内置、可校验的能力包，解析最快；
            //   2) 未命中则回落 dynamicSkill(...) 动态解析 —— 按「自身 → 工作区 → 全局」三级
            //      渐进披露读 .yaml 技能文件并包装成 SkillPlugin；
            //   3) 两者都拿不到说明是未知技能 id，仅告警跳过，避免单个坏技能卡死整个 Agent 装配。
            //   拿到 SkillPlugin 后用 contribute(SkillContext) 做副作用：把技能自带的工具
            //   注册进 toolkit、把技能说明/使用提示追加进 promptBuf（sysPrompt 的一部分）。
            for (String id : ids) {
                SkillPlugin sp = pluginRegistry.skill(id);
                if (sp == null) {
                    sp = dynamicSkill(ws, m.id(), id);
                }
                if (sp == null) {
                    log.warn("未知 skill 能力包: {}", id);
                    continue;
                }
                sp.contribute(new SkillContext(toolkit, promptBuf));
            }
        }

        // 3. 工具执行上下文：按类型自动注入工具方法参数，LLM 不可见
        ToolExecutionContext ctx = ToolExecutionContext.builder()
                .register(new UserContext(userId, sessionId, workspaceRoot))
                .build();

        // 工作区经理专属编排指南：注入系统提示，明确「任务分级→自办/派发→门禁→验收→修改闭环」工作法
        if (adminAgent) {
            promptBuf.append("\n\n【工作区经理·任务分级与多 Agent 协同交付编排指南】\n")
                    .append("作为工作区经理，接到需求后第一步必须先【给任务分级】：判断该任务能否由你自己完成。\n")
                    .append("- 自己能完成的（改动在你能力与权限内、无需协同、风险低）→ 直接用自带文件/命令工具自己完成，不必派发。\n")
                    .append("- 自己不能单独完成或无把握的 → 先拆解成若干可独立交付的子任务，再派给工作区其他 Agent 协作。\n")
                    .append("需要协同时的编排步骤：\n")
                    .append("1. 用 list_workers 查看当前工作区可协作的 Agent（排除你自己）。\n")
                    .append("2. 为拆解出的每个子任务写明【验收门禁】（尽量具体可判定，如“后端 mvn test 通过 / 前端可构建 / 接口可编译”）。\n")
                    .append("3. 派发前【先判断任务能否并行】——默认持保守态度，只有满足下面条件才并行，否则必须串行：\n")
                    .append("   · 可并行：各子任务相互独立、谁先谁后都一样、彼此不读对方中间结果、不存在共享写的冲突；此时才可用 run_parallel 一次并行派给多个 Agent（workItems 每行 `agentId::工作指令`，全部完成统一汇总）。\n")
                    .append("   · 必须串行：后一项依赖前一项的输出/产物、共享同一份资源会互相覆盖、或需要按顺序验证（如先实现后端接口再让前端对接）——这些一律用 run_worker 逐项顺序派发，切勿并行，否则会产生脏数据和互相踩踏。\n")
                    .append("   · 拿不准时按串行处理，宁可更慢也不要并行出错。\n")
                    .append("4. 汇总汇报，并用 verify_gates 把门禁块交给真实构建校验，对照判定是否通过。\n")
                    .append("5. 全部通过 → 总结交付物与验收结论；某 Agent 未通过 → 用 run_worker 携带 roundHint（第几轮+具体修改要求）再次派发（run_parallel 若需继续修改，拆成单条再逐项派发），直到通过或达最大轮次。\n")
                    .append("6. 开始每个新需求编排前，先调用 reset_rounds 清零轮次计数。\n")
                    .append("7. 最终回复务必说明：任务分级结论、为何自办或派发、该需求采用了并行还是串行及判断依据、各项门禁、每个 Agent 的成果/状态、验收结论，未通过的给出客观原因。")
                    .append("\n8. 想确认自己或子 Agent 生成的页面实际效果时，可先用 screenshot_webpage 把页面渲染成截图保存到工作区，"
                            + "再用 analyze_image 让视觉模型看懂截图里显示的内容，以此自检排版/文字/元素是否正确。");
        }

        // 4. 超时与重试：模型可重试（幂等读），工具不重试（写操作重试=重复执行）
        //    工作流跑 mvn/npm 构建时工具超时放宽到 bashTimeout 上限，避免长任务被掐断。
        ExecutionConfig modelExec = ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(3)
                .build();
        // 经理跑 run_worker（内部嵌套跑目标 Agent，最长可达 15min）与 verify_gates（mvn/npm 构建），
        // 需显著放宽工具执行超时；普通 Agent 维持原 timeout。
        ExecutionConfig toolExec = ExecutionConfig.builder()
                .timeout(adminAgent ? Duration.ofMinutes(30) : workflowToolTimeout(bashTimeout))
                .maxAttempts(1)
                .build();

        // 5. 装配
        //   模型构建一次复用：既作注入，也用于读取输入上下文窗口（预算锚定）；
        //   该 Agent 绑定的模型 AK 已在上面复用加载（agentAk）
        Model model = modelFactory.build(m.model(), agentAk);
        int window = modelFactory.effectiveWindow(model);
        PromptParts pp = composeSystemPrompt(workspaceRoot, ws, m, promptBuf, extraPrompt,
                memoryContext, window);
        ReActAgent.Builder b = ReActAgent.builder()
                .name(m.id())
                .sysPrompt(pp.composed())
                .model(model)
                .toolkit(toolkit)
                .toolExecutionContext(ctx)
                .maxIters(adminAgent ? 80 : 25)
                .checkRunning(true)
                .modelExecutionConfig(modelExec)
                .toolExecutionConfig(toolExec);

        // 5.1 hooks：按 priority 升序挂载 MiddlewareBase（manifest + 全局/工作区预装 hook 合并）
        collectMiddlewares(ws, m).forEach(b::middleware);
        // 5.1b 大文件守卫：onActing 拦截 read_file，>行数上限重写为 grep 引导（只读角色无
        //   bash，放行交给 FileTools 自身截断）
        b.middleware(new FileReadGuardMiddleware(workspaceRoot,
                props.getReadFileLineCap(), !readOnly));
        // 5.2 运行时五区预算判定 + 超限自收敛
        //   seed=装配期 SYSTEM/MEMORY，运行时补 TASK/TOOL；窗口按模型侧真值锚定，
        //   MEMORY 收紧目标由 memory/memKey 定位（保证 FREE≥10% 窗口）
        b.middleware(new ConversationMemory.ContextBudgetMiddleware(
                ConversationMemory.ContextBudget.of()
                        .zone(ConversationMemory.ContextBudget.Zone.SYSTEM, pp.baseSys())
                        .zone(ConversationMemory.ContextBudget.Zone.MEMORY, pp.memory())
                        .window(window),
                conversationMemory, memKey, contextSummarizer, props.isToolProgressLog()));
        // 5.3 旁路记忆观测中间件（可选挂载，保持既有观测）
        if (memoryMiddleware != null) {
            b.middleware(memoryMiddleware);
        }
        // 5.4 试点：外包一层 HarnessAgent 委托壳（继承 ReActAgent 全部可见配置，仅补 agentId / 工作区）。
        //   harness 工作区默认收敛到本工作区的 .workbench 子目录（agents/、sessions/、tasks/ 之类
        //   都落在其下），避免把 harness 状态散落在工作区根，与现有 .workbench 布局同根共存。
        ReActAgent built = b.build();
        return HarnessAgent.Builder.fromAgent(built)
                .workspace(ws.root().resolve(".workbench"))
                .agentId(m.id())
                .build();
    }

    /** 工具执行超时：原生 60s 下限，工作流角色依据 bashTimeout 放宽（long build 用）。 */
    private Duration workflowToolTimeout(Duration bashTimeout) {
        Duration min = Duration.ofSeconds(60);
        return bashTimeout.compareTo(min) > 0 ? bashTimeout : min;
    }

    /** 动态 yaml 技能解析：按 id 从「自身→工作区→全局」技能库取 UserSkill 并包装为 SkillPlugin。未命中返回 null。 */
    private SkillPlugin dynamicSkill(Workspace ws, String agentId, String id) {
        try {
            SkillView sv = skillLibrary.find(ws, agentId, id);
            if (sv == null) {
                return null;
            }
            UserSkill us = new UserSkill(sv.id(), sv.name(), sv.description(), sv.prompt(), sv.tools());
            return new YamlSkillPlugin(us, skillToolFactory, mcpRegistry);
        } catch (Exception e) {
            log.warn("动态技能装配失败 {}: {}", id, e.toString());
            return null;
        }
    }

    /** 按「全局/工作区/Agent 自身预装 hook + 清单 hooks[]」合并解析并排序插件中间件（按 type 去重，清单显式项优先）。 */
    private List<MiddlewareBase> collectMiddlewares(Workspace ws, AgentManifest m) {
        Map<String, AgentManifest.HookRef> byType = new LinkedHashMap<>();
        // 三级渐进披露：全局预装在前，工作区居中，Agent 自身在后；manifest 显式声明最后（同 type 覆盖）
        for (ContentItem it : content.merge("hook", ws, m.id())) {
            if (it.enabled() && it.ref() != null && !it.ref().isBlank()) {
                byType.put(it.ref(), new AgentManifest.HookRef(it.ref(), true, it.config()));
            }
        }
        if (m.hooks() != null) {
            for (AgentManifest.HookRef ref : m.hooks()) {
                if (ref != null && ref.enabled()) {
                    byType.put(ref.type(), ref);
                }
            }
        }
        List<HookPlugin> plugins = new ArrayList<>();
        for (AgentManifest.HookRef ref : byType.values()) {
            if (ref == null || !ref.enabled()) {
                continue;
            }
            HookPlugin hp = pluginRegistry.hook(ref.type());
            if (hp == null) {
                log.warn("未知 hook 插件: {}", ref.type());
                continue;
            }
            plugins.add(hp);
        }
        plugins.sort(Comparator.comparingInt(HookPlugin::priority));
        return plugins.stream().map(HookPlugin::toMiddleware).toList();
    }

    /** 预装规则（全局基线 + 工作区私有 + Agent 自身）渲染为系统提示「## 工作规则」片段；空则返回空串。 */
    private String preloadedRules(Workspace ws, String agentId) {
        List<ContentItem> items = content.merge("rules", ws, agentId);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 工作规则（全局预装 + 工作区 + Agent 自配置）\n");
        for (ContentItem it : items) {
            if (it.enabled()) {
                sb.append("### ").append(it.name()).append("\n")
                  .append(it.content().strip()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 预装记忆（全局基线 + 工作区私有 + Agent 自身）渲染为「## 预装记忆」片段；空则返回空串。并入 MEMORY 区顶格。 */
    private String preloadedMemory(Workspace ws, String agentId) {
        List<ContentItem> items = content.merge("memory", ws, agentId);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 预装记忆（全局预装 + 工作区 + Agent 自配置）\n");
        for (ContentItem it : items) {
            if (it.enabled()) {
                sb.append("### ").append(it.name()).append("\n")
                  .append(it.content().strip()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 系统提示装配结果：composed=最终喂模型文本，baseSys=不含记忆的基础系统提示（SYSTEM 区），
     *  memory=会话记忆（MEMORY 区）。三者供运行时预算中间件作为 seed 复用同一个度量口径。 */
    private record PromptParts(String composed, String baseSys, String memory) {
    }

    /** 系统提示：基础提示 + 工作区工作台目录说明 + 预装规则 + skills 片段 + 清单自定义提示 + 角色追加提示；
     *  记忆区（MEMORY）由「预装记忆（全局+工作区）+ 会话记忆」合并构成。
     *  五区隔离：基础系统提示归 SYSTEM 区、合并后记忆归 MEMORY 区，各自独立计量后 log，
     *  为 Token 预算计算（ContextBudget）提供真实占比输入。 */
    private PromptParts composeSystemPrompt(Path workspaceRoot, Workspace ws, AgentManifest m,
                                            StringBuilder skillBuf, String extraPrompt,
                                            String memoryContext, int window) {
        StringBuilder sb = new StringBuilder(SystemPrompt.TEXT);
        sb.append(workspaceContext(workspaceRoot));
        sb.append(preloadedRules(ws, m.id())); // 全局预装 + 工作区 + Agent 自身规则（归 SYSTEM 区）
        if (skillBuf.length() > 0) {
            sb.append("\n\n## 附加能力\n").append(skillBuf);
        }
        if (m.systemPrompt() != null && !m.systemPrompt().isBlank()) {
            sb.append("\n\n").append(m.systemPrompt().strip());
        }
        if (extraPrompt != null && !extraPrompt.isBlank()) {
            sb.append("\n\n").append(extraPrompt.strip());
        }
        String base = sb.toString(); // SYSTEM 区（不含记忆）

        // MEMORY 区：预装记忆（全局+工作区+Agent 自身）在前，会话记忆在后
        StringBuilder mem = new StringBuilder();
        String preload = preloadedMemory(ws, m.id());
        if (!preload.isBlank()) {
            mem.append(preload);
        }
        String session = memoryContext == null ? "" : memoryContext.strip();
        if (!session.isBlank()) {
            if (mem.length() > 0) {
                mem.append("\n\n");
            }
            mem.append("## 会话记忆（历史上下文）\n")
               .append("以下是本会话此前的对话摘要与最近几轮原文，作为你的连续记忆，据此延续工作，不要重复已完成的事。\n")
               .append(session);
        }
        String memory = mem.toString();

        ConversationMemory.ContextBudget budget = ConversationMemory.ContextBudget.of()
                .zone(ConversationMemory.ContextBudget.Zone.SYSTEM, base)
                .zone(ConversationMemory.ContextBudget.Zone.MEMORY, memory)
                .window(window);
        log.info("五区上下文[装配]隔离(窗口={}):\n{}", window, budget.report());

        if (!memory.isBlank()) {
            sb.append("\n\n").append(memory);
        }
        return new PromptParts(sb.toString(), base, memory);
    }

    /** 工作区 .workbench 目录说明：列出各子目录可读取的素材，让 Agent 知道能从哪读技能/Agent/记忆/规则。 */
    private String workspaceContext(Path root) {
        Path meta = root.resolve(WorkspaceManager.META_DIR);
        StringBuilder sb = new StringBuilder("\n\n## 工作区工作台目录 workspace/.workbench\n");
        sb.append("本工作区的元数据目录。工作时可用文件工具以相对路径 .workbench/... 读取其中的技能、Agent 配置、记忆、规则等素材，不必依赖外部知识。\n");
        List<String> subs = new ArrayList<>(List.of(WorkspaceManager.WORKSPACE_SUBDIRS));
        subs.add(WorkspaceManager.AGENTS_DIR);
        for (String sub : subs) {
            sb.append("- `.workbench/").append(sub).append("/`");
            try {
                List<String> names;
                try (var s = Files.list(meta.resolve(sub))) {
                    names = s.map(p -> p.getFileName().toString())
                            .filter(n -> !n.startsWith("."))
                            .sorted()
                            .limit(8)
                            .toList();
                }
                if (names.isEmpty()) {
                    sb.append(" — 空");
                } else {
                    sb.append(" — ").append(String.join(", ", names));
                    if (names.size() >= 8) sb.append("…");
                }
            } catch (IOException e) {
                sb.append(" — 不可读");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}