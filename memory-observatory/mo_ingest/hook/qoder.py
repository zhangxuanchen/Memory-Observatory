"""Qoder Hook 适配器（纯 stdlib）。

Qoder 在关键节点把事件 JSON 从 stdin 喂给本模块（Hook ``type: "command"``），
本适配器把事件翻译成标准化事件，经 :mod:`mo_ingest.otlp` 上报。

安装与配置见 examples/QODER-HOOK-SETUP.md。安装后包装脚本形如：

    exec python3 -m mo_ingest --platform qoder --mode hook

工具名有两套（原生 + Claude Code 兼容名），io_kind 映射表在
:mod:`mo_ingest.contract`，此处只做事件分发与 turn 状态累积。
"""
from __future__ import annotations

import json
import os
import time

from ..contract import (
    IO_KIND_LIFECYCLE,
    MemoryEvent,
    estimate_tokens,
    io_kind_of_tool,
    operation_of_io_kind,
    short,
    TOOL_LAYER,
)
from .base import HookAdapter, summarize_tool


class QoderHookAdapter(HookAdapter):
    PLATFORM = "qoder"
    SOURCE = "qoder_hook"
    HANDLED = frozenset({
        "UserPromptSubmit",
        "PostToolUse",
        "PostToolUseFailure",
        "Stop",
        "SessionEnd",
    })
    SCOPE = "qoder-hook-report"
    DEFAULT_AGENT = "qoder"
    STATE_DIR = "~/.qoder/hooks/.mo_state"
    MAIN_LAYER = "session"

    # ------------------------------------------------------------------
    # 分发
    # ------------------------------------------------------------------

    def handle(self, event: dict, name: str) -> None:
        if name == "UserPromptSubmit":
            self._handle_user_prompt(event)
        elif name == "PostToolUse":
            self._handle_tool(event, failed=False)
        elif name == "PostToolUseFailure":
            self._handle_tool(event, failed=True)
        elif name == "Stop":
            self._handle_stop(event)
        elif name == "SessionEnd":
            self.sweep_stale()

    # ------------------------------------------------------------------
    # turn 分组键
    # ------------------------------------------------------------------

    def _turn_key(self, event: dict) -> str:
        """turn_message_id：优先 request_set_id（当前 IDE 请求轮次），缺省退化为 session_id。

        request_set_id 是**可选**字段。缺省时整段会话并成一个 turn——保底而非期望。
        """
        rid = str(event.get("request_set_id") or "").strip()
        if rid:
            return rid
        sid = str(event.get("session_id") or "").strip() or "qoder-session"
        return f"{sid}:noset"

    # ------------------------------------------------------------------
    # 层级归属：全局(agent) › 工作区(transcript 打开根) › 项目(cwd) › 任务(session)
    # ------------------------------------------------------------------

    def _scope(self, event: dict) -> dict:
        """从事件里抽出层级字段，作为 metadata 附带（缺字段就不写、不猜）。"""
        cwd = str(event.get("cwd") or "").strip()
        sid = str(event.get("session_id") or "").strip()
        out = {}
        if sid:
            out["task_id"] = sid
        if cwd:
            proj = os.path.basename(cwd.rstrip("/"))
            if proj:
                out["project"] = proj
                out["project_path"] = cwd
            ws = self._workspace_root(cwd, str(event.get("transcript_path") or ""))
            if ws:
                out["workspace"] = ws
                wsn = os.path.basename(ws.rstrip("/"))
                if wsn:
                    out["workspace_name"] = wsn
        return out

    @staticmethod
    def _workspace_root(cwd: str, transcript_path: str) -> str:
        """Qoder transcript_path 形如 .../projects/<把/换成-的编码工作区根>/...。
        编码有歧义（目录名里的 - 与分隔符 - 撞车），故不硬解码，而是拿 cwd 的各级
        祖先去匹配：谁的 /→- 编码等于该 token，谁就是真实工作区根。匹配不到返回空。"""
        marker = "/projects/"
        i = transcript_path.find(marker)
        if i < 0 or not cwd:
            return ""
        enc = transcript_path[i + len(marker):].split("/", 1)[0]
        if not enc:
            return ""
        p = os.path.abspath(cwd)
        while True:
            if p.replace("/", "-") == enc:
                return p
            parent = os.path.dirname(p)
            if parent == p:
                return ""
            p = parent

    # ------------------------------------------------------------------
    # 事件处理
    # ------------------------------------------------------------------

    def _handle_user_prompt(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        turn_key = self._turn_key(event)
        prompt = str(event.get("prompt") or "")
        if not str(event.get("request_set_id") or "").strip():
            self.log("WARN request_set_id 缺省，退化为按 session 分组（待实测确认该字段是否稳定填充）")
        # 主事件：turn_user 是 L6 判据唯一可信的测量面，先落它，保证 prompt 不丢。
        self.emit(MemoryEvent(
            agent_id=self.agent_id,
            session_id=sid,
            operation="store",
            layer=self.MAIN_LAYER,
            memory_key=f"turn:{sid}",
            memory_summary=short(prompt, 200),
            token_count=estimate_tokens(prompt, minimum=1),
            metadata=self.meta(turn_key, **self._scope(event), turn_user=prompt,
                               action_count="0", io_kind=IO_KIND_LIFECYCLE),
        ))
        self.save_state(turn_key, {"user": prompt, "actions": [], "started": time.time()})

    def _handle_tool(self, event: dict, failed: bool) -> None:
        sid = str(event.get("session_id") or "")
        turn_key = self._turn_key(event)
        tool = str(event.get("tool_name") or "tool")
        io_kind = io_kind_of_tool(tool) or "tool_other"
        layer = TOOL_LAYER.get(tool, self.MAIN_LAYER)
        operation = operation_of_io_kind(io_kind)
        summary = self._tool_summary(tool, event.get("tool_input"))
        if failed:
            summary += "（失败）"

        state = self.load_state(turn_key)
        actions = state.get("actions") or []
        idx = len(actions)
        actions.append(summary)
        state["actions"] = actions
        self.save_state(turn_key, state)

        # 子事件：带 io_kind，供 §4.4 分桶（有没有工具读 / 是否只读未写）
        self.emit(MemoryEvent(
            agent_id=self.agent_id,
            session_id=sid,
            operation=operation,
            layer=layer,
            memory_key=short(tool, 32) or "tool",
            memory_summary=summary,
            token_count=estimate_tokens(summary, minimum=1),
            metadata=self.meta(turn_key, **self._scope(event), action_idx=str(idx),
                               action_full=summary, io_kind=io_kind),
        ))

    def _handle_stop(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        turn_key = self._turn_key(event)
        state = self.load_state(turn_key)
        actions = state.get("actions") or []
        if not state:
            # 没见过 UserPromptSubmit（比如中途启动本 hook）——不硬凑，只记一笔
            self.log("Stop 但无 turn 状态，跳过汇总")
            return
        outcome = str(event.get("outcome") or event.get("summary") or event.get("response") or "")
        self.emit(MemoryEvent(
            agent_id=self.agent_id,
            session_id=sid,
            operation="store",
            layer=self.MAIN_LAYER,
            memory_key=f"turn:{sid}",
            memory_summary=short(outcome, 200) or "Turn 结束",
            token_count=0,
            metadata=self.meta(turn_key, **self._scope(event), action_count=str(len(actions)),
                               turn_actions=json.dumps(actions, ensure_ascii=False),
                               turn_outcome=outcome,
                               io_kind=IO_KIND_LIFECYCLE),
        ))
        self.drop_state(turn_key)

    # ------------------------------------------------------------------
    # 工具摘要
    # ------------------------------------------------------------------

    @staticmethod
    def _tool_summary(tool_name: str, tool_input) -> str:
        """把工具调用压成一行人类可读摘要（共用实现见 ``hook/base.summarize_tool``）。"""
        return summarize_tool(tool_name, tool_input)
