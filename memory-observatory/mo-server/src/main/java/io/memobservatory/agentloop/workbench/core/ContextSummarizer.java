/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · core
 * 【文件】ContextSummarizer.java（io.memobservatory.agentloop.workbench.core）
 * 【核心功能】会话摘要器：用模型把一段历史对话压缩成简洁中文摘要（保留关键决策、
 *            涉及文件、数字结论），供上下文管理（四轮摘要压缩）使用。
 * 【设计要点】摘要属于幂等读，单次调用加超时；任何异常均返回 null，由调用方回退截断，
 *            绝不阻断主流程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.core;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 把一段历史对话压缩成简洁摘要。模型用全局配置（与 Agent 同源 provider/模型）。
 */
public class ContextSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ContextSummarizer.class);

    /** 单次摘要的超时上限，避免长历史拖慢压缩。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(40);

    private final ModelFactory modelFactory;

    public ContextSummarizer(ModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 压缩给定历史文本为简洁摘要。失败返回 null，调用方按截断回退。
     *
     * @param text 需要压缩的历史对话原文
     * @return 摘要文本；失败时为 null
     */
    public String summarize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Model model = modelFactory.build();
            Msg system = Msg.builder().textContent(
                    "你是会话记忆压缩器。把下方历史对话用中文压缩成简明摘要，保留："
                            + "已完成的关键动作与决策、涉及的文件/路径、关键数字与结论、"
                            + "尚未解决或待办的事项。不要复述对话，不要编造，控制在 150 字内。"
                    ).build();
            Msg user = Msg.builder().textContent(text).build();
            return model.stream(List.of(system, user), null, null)
                    .timeout(TIMEOUT)
                    .map(this::textOf)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce("", (a, b) -> a + b)
                    .block(TIMEOUT);
        } catch (Exception e) {
            log.warn("[ContextSummarizer] 摘要失败，回退截断: {}", e.toString());
            return null;
        }
    }

    private String textOf(ChatResponse r) {
        if (r.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : r.getContent()) {
            if (b instanceof TextBlock t && t.getText() != null) {
                sb.append(t.getText());
            }
        }
        return sb.toString();
    }
}