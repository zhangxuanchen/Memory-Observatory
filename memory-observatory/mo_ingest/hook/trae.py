"""Trae Hook 适配器（实时旁路，第二种接入方案）。

Trae IDE 在关键节点把事件 JSON 从 stdin 喂给本模块（``~/.trae-cn/hooks.json`` 里的
``type: "command"``），本适配器把事件翻译成标准化事件，经 :mod:`mo_ingest.otlp` 上报。
安装与配置见 examples/TRAE-HOOK-SETUP.md。安装后包装脚本形如：

    exec python3 -m mo_ingest --platform trae --mode hook

与 Qoder 的关键差异（决定了本文件的 turn 切分方式）：

- Trae **没有** ``request_set_id``，无法靠单字段切轮次。改用
  ``UserPromptSubmit`` 开新 turn / ``Stop`` 收尾的配对，轮次号由状态文件里的会话计数器维护。
- Trae **没有** ``PostToolUseFailure``，失败了要从 ``tool_response`` 判（见 ``_is_failure``）。
- ``Stop`` 的结果是文档明确的 ``last_assistant_message``，直接作 ``turn_outcome``。

工具名有两套（标准化 + Claude Code 兼容名），io_kind 映射表在 :mod:`mo_ingest.contract`，
此处只做事件分发与 turn 状态累积。
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

# tool_response 里表失败的字面量（宽松判定：形态未实测，宁多认不漏）
_FAILURE_FLAGS = ("error", "is_error", "isError")
_FAILURE_STATUS = ("error", "failed", "failure")


class TraeHookAdapter(HookAdapter):
    PLATFORM = "trae"
    SOURCE = "trae_hook"
    # 刻意不注册 PreToolUse（会与 PostToolUse 生成双份工具事件）与 Notification（异步、与 turn 无关）
    HANDLED = frozenset({
        "SessionStart",
        "UserPromptSubmit",
        "PostToolUse",
        "Stop",
    })
    SCOPE = "trae-hook-report"
    DEFAULT_AGENT = "trae"
    STATE_DIR = "~/.trae-cn/hooks/.mo_state"
    MAIN_LAYER = "session"

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        # 记录是否显式指定了 agent（显式优先于按 cwd 推断，见 _agent_for）
        explicit = (kwargs.get("agent_id") or os.environ.get("MO_AGENT_ID") or "")
        self._agent_explicit = bool(explicit)

    # ------------------------------------------------------------------
    # 分发
    # ------------------------------------------------------------------

    def handle(self, event: dict, name: str) -> None:
        if name == "UserPromptSubmit":
            self._handle_user_prompt(event)
        elif name == "PostToolUse":
            self._handle_tool(event)
        elif name == "Stop":
            self._handle_stop(event)
        elif name == "SessionStart":
            self.sweep_stale()

    # ------------------------------------------------------------------
    # turn 切分：cursor（会话轮次号）+ turn 状态（提问与动作累积）
    # ------------------------------------------------------------------

    # cursor 与 turn 状态都存进同一个 state_dir；键前缀区分，避免撞车
    def _cursor_key(self, sid: str) -> str:
        return f"cursor:{sid}"

    def _current_turn(self, sid: str) -> int:
        """当前轮次号；无 cursor 视为第 0 轮（尚未有 UserPromptSubmit）。"""
        state = self.load_state(self._cursor_key(sid))
        try:
            return int(state.get("turn") or 0)
        except (TypeError, ValueError):
            return 0

    def _turn_key(self, sid: str, n: int) -> str:
        return f"{sid}#{n}"

    def _open_turn(self, sid: str) -> str:
        """开新 turn：轮次号自增并落 cursor，返回新 turn_key。"""
        n = self._current_turn(sid) + 1
        self.save_state(self._cursor_key(sid), {"turn": n, "at": time.time()})
        return self._turn_key(sid, n)

    # ------------------------------------------------------------------
    # agent_id：显式优先，否则取 cwd basename（对齐 log 导入的项目粒度）
    # ------------------------------------------------------------------

    def _agent_for(self, event: dict) -> str:
        if self._agent_explicit:
            return self.agent_id
        roots = event.get("workspace_roots")
        root = event.get("cwd") or (roots[0] if isinstance(roots, list) and roots else "")
        base = os.path.basename(str(root).rstrip("/")) if root else ""
        return base or self.agent_id

    # ------------------------------------------------------------------
    # 事件处理
    # ------------------------------------------------------------------

    def _handle_user_prompt(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        turn_key = self._open_turn(sid)
        prompt = str(event.get("prompt") or "")
        agent = self._agent_for(event)
        # 主事件：turn_user 是 L6 判据唯一可信的测量面，先落它，保证 prompt 不丢。
        self.emit(MemoryEvent(
            agent_id=agent,
            session_id=sid,
            operation="store",
            layer=self.MAIN_LAYER,
            memory_key=f"turn:{sid}",
            memory_summary=short(prompt, 200),
            token_count=estimate_tokens(prompt, minimum=1),
            metadata=self.meta(turn_key, turn_user=prompt, action_count="0",
                               io_kind=IO_KIND_LIFECYCLE),
        ))
        # 把 agent 锚在 turn 状态里：同一 turn 的后续事件即便缺 cwd 也不换 agent
        self.save_state(turn_key, {"user": prompt, "actions": [], "agent": agent,
                                   "started": time.time()})

    def _handle_tool(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        n = self._current_turn(sid)
        if n <= 0:
            # 没见过 UserPromptSubmit（比如中途启用本 hook）——不硬凑，只记一笔
            self.log("PostToolUse 但无当前 turn，跳过")
            return
        turn_key = self._turn_key(sid, n)
        tool = str(event.get("tool_name") or "tool")
        io_kind = io_kind_of_tool(tool) or "tool_other"
        layer = TOOL_LAYER.get(tool, self.MAIN_LAYER)
        operation = operation_of_io_kind(io_kind)
        summary = summarize_tool(tool, event.get("tool_input"))
        if self._is_failure(event.get("tool_response")):
            summary += "（失败）"

        state = self.load_state(turn_key)
        actions = state.get("actions") or []
        idx = len(actions)
        actions.append(summary)
        state["actions"] = actions
        self.save_state(turn_key, state)

        # 子事件：带 io_kind，供 §4.4 分桶（有没有工具读 / 是否只读未写）
        self.emit(MemoryEvent(
            agent_id=state.get("agent") or self._agent_for(event),
            session_id=sid,
            operation=operation,
            layer=layer,
            memory_key=short(tool, 32) or "tool",
            memory_summary=summary,
            token_count=estimate_tokens(summary, minimum=1),
            metadata=self.meta(turn_key, action_idx=str(idx), action_full=summary,
                               io_kind=io_kind),
        ))

    def _handle_stop(self, event: dict) -> None:
        sid = str(event.get("session_id") or "")
        n = self._current_turn(sid)
        turn_key = self._turn_key(sid, n)
        state = self.load_state(turn_key) if n > 0 else {}
        actions = state.get("actions") or []
        if not state:
            # 没见过 UserPromptSubmit（比如中途启用本 hook）——不硬凑，只记一笔
            self.log("Stop 但无 turn 状态，跳过汇总")
            return
        outcome = str(event.get("last_assistant_message") or "")
        self.emit(MemoryEvent(
            agent_id=state.get("agent") or self._agent_for(event),
            session_id=sid,
            operation="store",
            layer=self.MAIN_LAYER,
            memory_key=f"turn:{sid}",
            memory_summary=short(outcome, 200) or "Turn 结束",
            token_count=0,
            metadata=self.meta(turn_key, action_count=str(len(actions)),
                               turn_actions=json.dumps(actions, ensure_ascii=False),
                               turn_outcome=outcome,
                               io_kind=IO_KIND_LIFECYCLE),
        ))
        # 只丢本 turn 状态，保留 cursor 供下一轮自增
        self.drop_state(turn_key)

    # ------------------------------------------------------------------
    # 失败判定（Trae 无 PostToolUseFailure，从 tool_response 形态猜）
    # ------------------------------------------------------------------

    @staticmethod
    def _is_failure(response) -> bool:
        """宽松判定工具是否失败。形态未实测，故多形态覆盖，宁多认不漏。"""
        if not isinstance(response, dict):
            return False
        for k in _FAILURE_FLAGS:
            if response.get(k):
                return True
        for k in ("status", "exit_code", "exitCode"):
            v = response.get(k)
            if isinstance(v, str):
                s = v.strip().lower()
                if s in _FAILURE_STATUS:
                    return True
                if s.lstrip("-").isdigit() and s != "0":
                    return True
            elif isinstance(v, (int, float)) and v != 0:
                return True
        return False