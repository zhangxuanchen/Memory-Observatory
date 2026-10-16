/*******************************************************************************
 * 【模块】Workspace-Agent · workspace · plugin
 * 【文件】AuditMiddleware.java（io.memobservatory.agentloop.workspace.plugin）
 * 【核心功能】内置审计钩子：在 onActing 拦截点记录本次 Agent 触发的全部工具调用。
 *            基于 MiddlewareBase（AgentScope 2.0 官方推荐），仅观测不改事件。
 * 【核心改动】2026-08-23 新增（workspace-agent-design.md 第 4.3 节，audit 类型）。
 *******************************************************************************/
package io.memobservatory.agentloop.workspace.plugin;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 内置审计钩子：拦截工具执行点（onActing），把本次工具调用留痕。只读日志，不改事件，
 * 也不阻塞主流程，异常仅 WARN（与旁路观测容错约定一致）。
 */
public class AuditMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AuditMiddleware.class);

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        try {
            List<String> names = (input.toolCalls() == null)
                    ? List.of() : input.toolCalls().stream().map(String::valueOf).toList();
            log.info("[audit] agent={} session={} toolCalls={}",
                    agent != null ? agent.getName() : "?",
                    ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : "?",
                    names);
        } catch (Exception e) {
            log.warn("[audit] 记录工具调用失败: {}", e.toString());
        }
        return next.apply(input);
    }

    /** 供 {@link PluginRegistry} 注册的钩子插件实现。 */
    public record AuditPlugin() implements HookPlugin {
        @Override
        public String id() {
            return "audit";
        }

        @Override
        public MiddlewareBase toMiddleware() {
            return new AuditMiddleware();
        }

        @Override
        public int priority() {
            return 50;
        }
    }
}