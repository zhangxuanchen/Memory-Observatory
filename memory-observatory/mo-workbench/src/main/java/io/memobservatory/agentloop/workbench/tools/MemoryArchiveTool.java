/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】MemoryArchiveTool.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】记忆召回工具：输入「可找回标记」里的 archiveId，读回该轮被压缩/折叠的完整原文。
 *            被压缩的记忆在折叠时已把整轮原文归档落盘，本工具按其 id 取回，供 Agent 在需要
 *            早前对话细节时补齐上下文。无副作用，会话隔离由 memKey 定位。
 * 【设计要点】只读型工具，不修改任何状态；id 不存在回传 ERROR 而非抛异常。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.memobservatory.agentloop.workbench.core.ConversationMemory;

/**
 * 记忆召回工具：输入折叠/压缩时留下的【记忆归档 #id】标记里的 id，
 * 读回该轮被折叠掉的完整原文（用户+助手）。用于上下文仅存摘要、但用户需要早前细节时。
 */
public class MemoryArchiveTool {

    private final ConversationMemory memory;
    private final String memKey;

    public MemoryArchiveTool(ConversationMemory memory, String memKey) {
        this.memory = memory;
        this.memKey = memKey;
    }

    @Tool(name = "memory_recall",
            description = "按归档 id 找回某一次被压缩/折叠的记忆完整原文（用户+助手）。"
                    + "当上下文里只有摘要、而用户需要早前对话的完整细节时调用。"
                    + "id 来自系统提示/摘要中的【记忆归档 #id】标记。")
    public String find(
            @ToolParam(name = "archiveId", description = "记忆归档标记里给出的 id 字符串（如“记忆归档 #xxx”中的 xxx）")
            String archiveId) {
        if (memory == null || memKey == null) {
            return "ERROR: 当前会话未启用记忆召回（无归档寻址）。";
        }
        return memory.findArchive(memKey, archiveId);
    }
}