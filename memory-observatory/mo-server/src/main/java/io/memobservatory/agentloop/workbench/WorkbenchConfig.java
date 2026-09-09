/*******************************************************************************
 * 【模块】Agent 工作台 Workbench（装配入口）
 * 【文件】WorkbenchConfig.java（io.memobservatory.agentloop.workbench）
 * 【核心功能】Spring @Configuration 装配工作台 Bean：AgentProperties / Hub /
 *            ModelFactory / MemoryReportMiddleware / AgentFactory；
 *            原独立入口 AgentWorkbenchApplication 已并入 mo-server 单进程。
 * 【核心改动】
 *   2026-08-23 并入 mo-server 单进程；新增旁路记忆观测中间件 Bean（可配置开关）。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench;

import io.memobservatory.agentloop.workbench.core.AgentFactory;
import io.memobservatory.agentloop.workspace.mcp.McpRegistry;
import io.memobservatory.agentloop.workbench.core.AgentKeyStore;
import io.memobservatory.agentloop.workbench.core.AgentProperties;
import io.memobservatory.agentloop.workbench.core.ContextSummarizer;
import io.memobservatory.agentloop.workbench.core.ConversationMemory;
import io.memobservatory.agentloop.workbench.core.ModelFactory;
import io.memobservatory.agentloop.workbench.core.SingleTurnExecutor;
import io.memobservatory.agentloop.workbench.hitl.ContextQueue;
import io.memobservatory.agentloop.workbench.hitl.UserInteractionHub;
import io.memobservatory.agentloop.workbench.report.MemoryReportMiddleware;
import io.memobservatory.agentloop.workbench.report.EventReporter;
import io.memobservatory.agentloop.workbench.report.ReportConfig;
import io.memobservatory.agentloop.workflow.WorkflowConfig;
import io.memobservatory.agentloop.workflow.WorkflowEngine;
import io.memobservatory.agentloop.workflow.WorkflowTemplateInitializer;
import io.memobservatory.agentloop.workflow.BuildVerifier;
import io.memobservatory.agentloop.workflow.TaskBoard;
import io.memobservatory.agentloop.workflow.WorkflowRunStore;
import io.memobservatory.agentloop.workflow.WorkspaceLockRegistry;
import io.memobservatory.agentloop.workspace.WorkspaceManager;
import io.memobservatory.agentloop.workspace.SkillLibrary;
import io.memobservatory.agentloop.workspace.ContentLibrary;
import io.memobservatory.agentloop.workspace.plugin.PluginRegistry;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.JsonFileAgentStateStore;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Agent 工作台（对话）装配配置。原独立入口 AgentWorkbenchApplication 已并入
 * mo-server 单进程，此处仅以 @Configuration 提供工作台所需的 Bean，
 * 并注册旁路记忆观测中间件（默认回传本服务自身的 /api/v1/events）。
 */
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class WorkbenchConfig {

    // AgentProperties 由 @EnableConfigurationProperties 注册并绑定 mo.agent.*，
    // 无需在此显式声明 bean。

    @Bean
    public UserInteractionHub userInteractionHub() {
        return new UserInteractionHub();
    }

    @Bean
    public ContextQueue contextQueue() {
        return new ContextQueue();
    }

    @Bean
    public ModelFactory modelFactory(AgentProperties props) {
        return new ModelFactory(props);
    }

    @Bean
    public ContextSummarizer contextSummarizer(ModelFactory modelFactory) {
        return new ContextSummarizer(modelFactory);
    }

    @Bean
    public ConversationMemory conversationMemory(AgentProperties props) {
        // 会话上下文落盘目录：<workspaceBase>/context，槽键与 ReActAgent 官方命名空间互操作
        Path base = props.getWorkspaceBase().resolve("context");
        return new ConversationMemory(new JsonFileAgentStateStore(base));
    }

    /**
     * 旁路记忆观测中间件。endpoint 默认跟随本服务端口（self-host 下回传自身
     * /api/v1/events，与运行端口一致；可用 agent.report.endpoint 覆盖），
     * enabled=false 关闭采集。中间件为单例、可并发挂到多个 Agent 实例。
     */
    @Bean
    public MemoryReportMiddleware memoryReportMiddleware(
            @Value("${agent.report.endpoint:}") String endpoint,
            @Value("${server.port:8080}") int serverPort,
            @Value("${agent.report.enabled:true}") boolean enabled) {
        String effective = (endpoint == null || endpoint.isBlank())
                ? "http://localhost:" + serverPort + "/api/v1/events"
                : endpoint;
        // agentName 置空：上报 agentId/SessionId 回落运行时 Agent 真实名（= 工作区 Agent id），
        // 使事件详情 / Token 分析按真实工作区 Agent 切片，而非折叠成单一 WorkbenchAgent。
        ReportConfig config = ReportConfig.builder()
                .endpoint(effective)
                .enabled(enabled)
                .build();
        return new MemoryReportMiddleware(config);
    }

    @Bean
    public WorkspaceManager workspaceManager(AgentProperties props) {
        return new WorkspaceManager(props.getWorkspaceBase());
    }

    @Bean
    public AgentKeyStore agentKeyStore(WorkspaceManager workspaceManager) {
        return new AgentKeyStore(workspaceManager);
    }

    @Bean
    public SkillLibrary skillLibrary(WorkspaceManager workspaceManager) {
        return new SkillLibrary(workspaceManager);
    }

    @Bean
    public ContentLibrary contentLibrary(WorkspaceManager workspaceManager) {
        return new ContentLibrary(workspaceManager);
    }

    @Bean
    public PluginRegistry pluginRegistry() {
        return new PluginRegistry();
    }

    /** 工作台 Agent 装配，注入工作区管理、技能库、预装内容库、插件注册表与旁路采集中间件。
     *  turnRunner 用 @Lazy 注入以打破「AgentFactory ← turnRunner ← AgentFactory」的 Bean 装配环：
     *  实际只在运行时（经理派发子 Agent）才触发，届时 factory 已就绪。 */
    @Bean
    public AgentFactory agentFactory(AgentProperties props,
                                     ModelFactory modelFactory,
                                     UserInteractionHub hub,
                                     WorkspaceManager workspaceManager,
                                     PluginRegistry pluginRegistry,
                                     SkillLibrary skillLibrary,
                                     ContentLibrary contentLibrary,
                                     MiddlewareBase memoryMiddleware,
                                     ConversationMemory conversationMemory,
                                     ContextSummarizer contextSummarizer,
                                     ContextQueue contextQueue,
                                     BuildVerifier buildVerifier,
                                     AgentKeyStore agentKeyStore,
                                     McpRegistry mcpRegistry,
                                     @Lazy SingleTurnExecutor turnRunner) {
        return AgentFactory.builder()
                .props(props)
                .modelFactory(modelFactory)
                .interactionHub(hub)
                .workspaceManager(workspaceManager)
                .pluginRegistry(pluginRegistry)
                .skillLibrary(skillLibrary)
                .content(contentLibrary)
                .memoryMiddleware(memoryMiddleware)
                .conversationMemory(conversationMemory)
                .contextSummarizer(contextSummarizer)
                .contextQueue(contextQueue)
                .buildVerifier(buildVerifier)
                .agentKeyStore(agentKeyStore)
                .mcpRegistry(mcpRegistry)
                .turnRunner(turnRunner)
                .build();
    }

    /** 统一单轮执行入口：/chat 对话与经理派发子 Agent 共用同一执行路径。 */
    @Bean
    public SingleTurnExecutor singleTurnExecutor(AgentFactory agentFactory,
                                                 ConversationMemory conversationMemory,
                                                 ContextSummarizer contextSummarizer) {
        return new SingleTurnExecutor(agentFactory, conversationMemory, contextSummarizer);
    }

    // ---------- 多角色 Agent 工作流 ----------

    @Bean
    public WorkflowConfig workflowConfig() {
        return new WorkflowConfig();
    }

    @Bean
    public BuildVerifier buildVerifier() {
        return new BuildVerifier();
    }

    @Bean
    public TaskBoard taskBoard() {
        return new TaskBoard();
    }

    @Bean
    public WorkflowRunStore workflowRunStore() {
        return new WorkflowRunStore();
    }

    @Bean
    public WorkspaceLockRegistry workspaceLockRegistry() {
        return new WorkspaceLockRegistry();
    }

    @Bean
    public WorkflowEngine workflowEngine(AgentFactory agentFactory, WorkflowConfig workflowConfig,
                                         WorkspaceManager workspaceManager, BuildVerifier buildVerifier,
                                         TaskBoard taskBoard, WorkflowRunStore runStore,
                                         WorkspaceLockRegistry lockRegistry,
                                         @Value("${agent.report.endpoint:}") String endpoint,
                                         @Value("${server.port:8080}") int serverPort,
                                         @Value("${agent.report.enabled:true}") boolean reportEnabled) {
        // 工作流阶段观测走同一旁路上报通道（/api/v1/events），异常 WARN 不阻断
        EventReporter reporter = null;
        if (reportEnabled) {
            String effective = (endpoint == null || endpoint.isBlank())
                    ? "http://localhost:" + serverPort + "/api/v1/events" : endpoint;
            reporter = new EventReporter(effective);
        }
        return new WorkflowEngine(agentFactory, workflowConfig, reporter, workspaceManager,
                buildVerifier, taskBoard, runStore, lockRegistry);
    }

    @Bean
    public WorkflowTemplateInitializer workflowTemplateInitializer(WorkspaceManager workspaceManager) {
        return new WorkflowTemplateInitializer(workspaceManager);
    }
}