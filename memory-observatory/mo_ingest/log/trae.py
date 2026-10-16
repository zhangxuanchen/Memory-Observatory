"""Trae 旁路日志导入器（纯 stdlib）。

读 ``~/.trae-cn/memory/projects/<proj>/<date>/session_memory_*.jsonl``，把每行
对话摘要转成标准化事件（1 主事件 + N 子事件），交给基类批量上报。

每行 jsonl 的字段：``intent`` / ``actions[]`` / ``outcome`` / ``learned[]`` /
``message_id`` / ``message_summary_time``。
"""
from __future__ import annotations

import json
import re
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from ..contract import (
    IO_KIND_LIFECYCLE,
    MemoryEvent,
    estimate_tokens,
    io_kind_of_text,
    short_key,
)
from .base import LogImporter

# 操作归一：根据 action 文本关键词判定 layer / operation
LAYER_KEYWORDS = [
    ("provider", r"mem0|honcho|外部记忆|外部|provider|memory provider"),
    ("skill",    r"技能|工具调用|skill|tool|插件|interceptor|拦截"),
    ("prompt",   r"架构|文档|代码|产品|mvp|设计|prompt|生成|实现|写|编码"),
    ("session",  r"会话|上下文|session|压缩|归档|摘要|整合|consolidat"),
]
OP_KEYWORDS = [
    ("UPDATE",   r"更新|修订|重写|修复|完善|调整|修改|整理|edit|update|refactor"),
    ("EXPIRE",   r"压缩|归档|过期|清理|淘汰|expire|forget|archive|cleanup"),
    ("READ",     r"读|精读|查询|检索|对齐|识别|确认|查看|探查|核对|read|query|search"),
]


class TraeLogImporter(LogImporter):
    PLATFORM = "trae"
    SOURCE = "trae_importer"
    DEFAULT_ROOT = "~/.trae-cn/memory/projects"
    SCOPE = "mo-trae-importer"

    # ------------------------------------------------------------------
    # load：定位项目目录 + 逐文件解析
    # ------------------------------------------------------------------

    def load(self, since: Optional[str], project: Optional[str] = None,
             agent: Optional[str] = None, **opts):
        dirs = self._find_project_dirs(project)
        if not dirs:
            raise SystemExit(f"未找到匹配的项目目录（project={project}）；根目录={self.root}")
        out = []
        for proj_dir in dirs:
            agent_id = agent or self.agent or self._project_agent_id(proj_dir.name)
            print(f"\n项目: {proj_dir.name}  →  agent_id: {agent_id}")
            for date_dir in sorted(proj_dir.iterdir()):
                if not date_dir.is_dir() or not date_dir.name.isdigit():
                    continue
                if since and date_dir.name < since:
                    continue
                for jsonl in sorted(date_dir.glob("session_memory_*.jsonl")):
                    events = self._parse_jsonl(jsonl, agent_id)
                    if events:
                        out.append((date_dir.name, jsonl, agent_id, events))
        return out

    def build_turns(self, raw, **opts):
        return raw

    def to_events(self, turn) -> list:
        _, _, _, events = turn
        return events

    def report(self, raw, turns, events, opts) -> None:
        total_files = len(turns)
        total_events = len(events)
        agents = {t[2] for t in turns}
        for date_name, jsonl, agent_id, evs in turns:
            print(f"  {date_name}/{jsonl.name}: {len(evs)} 事件 (agent={agent_id})")
        print(f"\n完成：{total_files} 个文件，{total_events} 个事件，{len(agents)} 个 agent")

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    def _find_project_dirs(self, project_arg: Optional[str]) -> list:
        root = Path(self.root)
        if not root.is_dir():
            return []
        if not project_arg:
            cwd = Path.cwd().resolve()
            cwd_basename = cwd.name.lower()
            cwd_slug = re.sub(r"[^a-zA-Z0-9]", "-", str(cwd)).strip("-").lower()
            result = []
            for d in root.iterdir():
                if not d.is_dir():
                    continue
                name_low = d.name.lower()
                base = re.sub(r"--p2-[a-f0-9]+$", "", name_low)
                if cwd_basename in name_low or base == cwd_slug or name_low.startswith(cwd_slug):
                    result.append(d)
            return result
        dirs = [d for d in root.iterdir() if d.is_dir()]
        if project_arg == "all":
            return dirs
        low = project_arg.lower()
        # 精确名优先：`foo` 与 `foo--p2-<hash>` 互为子串时，纯子串匹配会一次命中两个，导致重复灌库。
        exact = [d for d in dirs if d.name.lower() == low]
        if exact:
            return exact
        return [d for d in dirs if low in d.name.lower()]

    @staticmethod
    def _project_agent_id(dir_name: str) -> str:
        base = re.sub(r"--p2-[a-f0-9]+$", "", dir_name)
        m = re.search(r"-Documents-(.+)$", base)
        if not m:
            m = re.search(r"-Downloads-(.+)$", base)
        name = m.group(1) if m else (base.split("-")[-1] or base)
        name = re.sub(r"^ai-", "", name)
        return name or "unknown"

    def _parse_jsonl(self, jsonl_path: Path, agent_id: str) -> list:
        session_id = jsonl_path.stem.replace("session_memory_", "")
        events: list[MemoryEvent] = []
        with jsonl_path.open(encoding="utf-8") as f:
            for line_no, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                intent = obj.get("intent", "") or ""
                actions = obj.get("actions", []) or []
                outcome = obj.get("outcome", "") or ""
                learned = obj.get("learned", []) or []
                message_id = obj.get("message_id", f"{session_id}-{line_no}")
                ts = self._parse_ts(obj.get("message_summary_time", ""))

                text_blob = " ".join([intent, *actions, outcome, *learned])
                events.append(MemoryEvent(
                    agent_id=agent_id,
                    session_id=session_id,
                    operation="store",
                    layer="session",
                    memory_key=short_key(intent, message_id),
                    memory_summary=intent[:200],
                    token_count=estimate_tokens(text_blob),
                    timestamp=ts,
                    metadata={
                        "source": self.SOURCE,
                        "message_id": message_id,
                        "outcome": outcome[:200],
                        "action_count": str(len(actions)),
                        "turn_message_id": message_id,
                        "turn_user": intent,
                        "turn_actions": json.dumps(actions, ensure_ascii=False),
                        "turn_outcome": outcome,
                        "turn_learned": json.dumps(learned, ensure_ascii=False),
                        "io_kind": IO_KIND_LIFECYCLE,
                    },
                ))
                for idx, action in enumerate(actions):
                    if not isinstance(action, str) or not action.strip():
                        continue
                    events.append(MemoryEvent(
                        agent_id=agent_id,
                        session_id=session_id,
                        operation=self._classify_op(action),
                        layer=self._classify_layer(action),
                        memory_key=short_key(action, f"{message_id}-{idx}"),
                        memory_summary=action[:200],
                        token_count=estimate_tokens(action),
                        timestamp=ts + idx * 0.01,
                        metadata={
                            "source": self.SOURCE,
                            "message_id": message_id,
                            "action_idx": str(idx),
                            "turn_message_id": message_id,
                            "action_full": action,
                            "io_kind": io_kind_of_text(action),
                        },
                    ))
        return events

    @staticmethod
    def _classify_layer(text: str) -> str:
        low = text.lower()
        for layer, pat in LAYER_KEYWORDS:
            if re.search(pat, low):
                return layer
        return "session"

    @staticmethod
    def _classify_op(text: str) -> str:
        low = text.lower()
        for op, pat in OP_KEYWORDS:
            if re.search(pat, low):
                return op
        return "WRITE"

    @staticmethod
    def _parse_ts(summary_time: str) -> float:
        if not summary_time:
            return time.time()
        try:
            return datetime.strptime(summary_time, "%Y-%m-%d %H:%M:%S").timestamp()
        except Exception:
            return time.time()