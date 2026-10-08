"""TraeCode 对话历史批量导入 MemoryObservatory.

读取 ~/.trae-cn/memory/projects/<proj>/<date>/session_memory_*.jsonl，
把每条对话摘要转 MemoryEvent 批量上报到 MO 服务端。

每行 jsonl 生成两类事件：
  1. 主事件：layer=session, op=store → WRITE（代表该段对话归档到记忆）
  2. 子事件：actions 数组每个元素 → 一个细粒度事件
     operation / layer 根据 action 文本关键词判定

用法：
    python examples/trae_importer.py                       # 导入当前项目
    python examples/trae_importer.py --project all         # 导入全部项目
    python examples/trae_importer.py --project hermes      # 按项目名子串筛选
    python examples/trae_importer.py --since 20260820      # 只导入此日期之后

前提：docker-compose up 已启动（mo-server 监听 4318）。
打开 Dashboard http://localhost:5173 ，Agent 填 trae-code 查看。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from mo_sdk import MemoryObserver, MemoryEvent, OTelSpanExporter

TRAECODE_MEMORY_ROOT = Path.home() / ".trae-cn" / "memory" / "projects"


def _project_agent_id(dir_name: str) -> str:
    """从项目目录名提取 agent_id：去掉 --p2-{hash} 后缀 + Documents/Downloads 前缀 + ai- 前缀。

    -Users-you-Documents-ai-agent-harness--p2-xxx → agent-harness
    -Users-you-Documents-ai-MemoryObservatory-memory-observatory--p2-xxx → MemoryObservatory-memory-observatory
    -Users-you-Downloads-sanmou-agent--p2-xxx → sanmou-agent
    """
    base = re.sub(r"--p2-[a-f0-9]+$", "", dir_name)
    m = re.search(r"-Documents-(.+)$", base)
    if not m:
        m = re.search(r"-Downloads-(.+)$", base)
    name = m.group(1) if m else (base.split("-")[-1] or base)
    name = re.sub(r"^ai-", "", name)
    return name or "unknown"

# 操作归一：根据 action 文本关键词判定 layer / operation
LAYER_KEYWORDS = [
    ("provider", r"mem0|honcho|外部记忆|外部|provider|memory provider"),
    ("skill",    r"技能|工具调用|skill|tool|插件|interceptor|拦截"),
    ("prompt",   r"架构|文档|代码|产品|MVP|设计|prompt|生成|实现|实现|写|编码"),
    ("session",  r"会话|上下文|session|压缩|归档|摘要|整合|consolidat"),
]
OP_KEYWORDS = [
    ("UPDATE",   r"更新|修订|重写|修复|完善|调整|修改|整理|edit|update|refactor"),
    ("EXPIRE",   r"压缩|归档|过期|清理|淘汰|expire|forget|archive|cleanup"),
    ("READ",     r"读|精读|查询|检索|对齐|识别|确认|查看|探查|核对|read|query|search"),
]

# io_kind：事件**性质**归一（B4 埋点补强），词表与 mo-server 的
# `agentloop/workbench/report/IoKind.java` 逐字一致，口径见《自校准闭环：设计与实现（L6 能学）》附录 B4。
#
# 与 OP_KEYWORDS 是两把尺子，不要混：operation 回答「这是哪种记忆操作」，
# io_kind 回答「这一轮到底动了什么」——正是 operation 答不了、B4.1/B4.2 卡住的那个问题。
#
# 诚实声明：action 是自然语言摘要，不是工具名，所以这里猜出来的 io_kind 与 _classify_op 同级，
# 属**代理口径**；只有工作台中间件那条路拿到的是真工具名。故导入路径的「只读未写」不可全信（同 B5）。
IO_KIND_KEYWORDS = [
    # 顺序即优先级：记忆类先于文件类（「检索记忆」不能被 content_search 的「检索」抢走），
    # 文件写先于文件读与内容检索（「修改 read_file 说明」应算写）。
    ("memory_write",   r"记忆写入|写入记忆|记住|存记忆|save_memory|update_memory|memorize|store"),
    ("memory_read",    r"记忆读取|读取记忆|回忆|检索记忆|search_memory|remember|retrieve|recall"),
    ("file_write",     r"修改|编辑|重写|新建|创建|写入文件|改文件|重构|实现|修复|apply|edit|write|refactor"),
    ("file_read",      r"读文件|阅读|精读|查看文件|打开|read_file|view_file"),
    ("content_search", r"搜索|检索|查找|查询|grep|search|find|glob"),
    ("tool_other",     r"运行|执行|命令|bash|shell|run|测试|构建|build|test|install"),
]

# 主事件（整段对话归档）恒为生命周期事件；子事件才参与上表判定。
IO_KIND_LIFECYCLE = "lifecycle"


def _classify_layer(text: str) -> str:
    low = text.lower()
    for layer, pat in LAYER_KEYWORDS:
        if re.search(pat, low):
            return layer
    return "session"  # 默认会话层


def _classify_op(text: str) -> str:
    low = text.lower()
    for op, pat in OP_KEYWORDS:
        if re.search(pat, low):
            return op
    return "WRITE"  # 默认写入


def _classify_io_kind(text: str) -> str:
    """按 action 文本猜 io_kind（代理口径，见 IO_KIND_KEYWORDS 的诚实声明）。认不出一律 tool_other，不猜。"""
    low = text.lower()
    for kind, pat in IO_KIND_KEYWORDS:
        if re.search(pat, low):
            return kind
    return "tool_other"


def _parse_ts(summary_time: str) -> float:
    """解析 '2026-08-21 11:49:31' 格式时间 → epoch。"""
    if not summary_time:
        return time.time()
    try:
        return datetime.strptime(summary_time, "%Y-%m-%d %H:%M:%S").timestamp()
    except Exception:
        return time.time()


def _estimate_tokens(text: str) -> int:
    """粗略估算 token 数：字符数 / 4，最少 10。"""
    return max(10, int(len(text) / 4))


def _short_key(text: str, fallback: str) -> str:
    """生成 memory_key：保留中文/数字/字母，前 32 字符。"""
    cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", text)
    return (cleaned or fallback)[:32]


def find_project_dirs(project_arg: str | None) -> list[Path]:
    """根据 --project 参数定位项目目录。"""
    if not project_arg:
        # 默认匹配当前工作目录：
        #   策略1：cwd basename 子串模糊匹配（如 'memory-observatory'）
        #   策略2：cwd 完整 slug 前缀匹配（去掉 --p2-{hash} 后缀比较）
        cwd = Path.cwd().resolve()
        cwd_basename = cwd.name.lower()
        cwd_slug = re.sub(r"[^a-zA-Z0-9]", "-", str(cwd)).strip("-").lower()
        result: list[Path] = []
        for d in TRAECODE_MEMORY_ROOT.iterdir():
            if not d.is_dir():
                continue
            name_low = d.name.lower()
            # 去掉 --p2-{hash} 后缀再比较
            base = re.sub(r"--p2-[a-f0-9]+$", "", name_low)
            if cwd_basename in name_low or base == cwd_slug or name_low.startswith(cwd_slug):
                result.append(d)
        return result
    if project_arg == "all":
        return [d for d in TRAECODE_MEMORY_ROOT.iterdir() if d.is_dir()]
    low = project_arg.lower()
    dirs = [d for d in TRAECODE_MEMORY_ROOT.iterdir() if d.is_dir()]
    # 精确名优先：目录名互为子串（`foo` 与 `foo--p2-<hash>`）时，纯子串匹配会一次命中两个，
    # 按目录逐个导入就会把带 --p2 后缀的那个灌两遍。给出精确目录名时只认它自己。
    exact = [d for d in dirs if d.name.lower() == low]
    if exact:
        return exact
    return [d for d in dirs if low in d.name.lower()]


def parse_session_jsonl(jsonl_path: Path, agent_id: str) -> tuple[list[MemoryEvent], str]:
    """解析一个 session_memory_*.jsonl 文件，返回事件列表 + session_id。"""
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
            ts = _parse_ts(obj.get("message_summary_time", ""))

            # 主事件：整段对话归档到 session 层（WRITE）
            text_blob = " ".join([intent, *actions, outcome, *learned])
            events.append(MemoryEvent(
                operation="store",  # → WRITE
                layer="session",
                memory_key=_short_key(intent, message_id),
                memory_summary=intent[:200],
                token_count=_estimate_tokens(text_blob),
                session_id=session_id,
                agent_id=agent_id,
                timestamp=ts,
                metadata={
                    "source": "trae_importer",
                    "message_id": message_id,
                    "outcome": outcome[:200],
                    "action_count": str(len(actions)),
                    # 完整 turn 详情（前端抽屉显示用）
                    "turn_message_id": message_id,
                    "turn_user": intent,
                    "turn_actions": json.dumps(actions, ensure_ascii=False),
                    "turn_outcome": outcome,
                    "turn_learned": json.dumps(learned, ensure_ascii=False),
                    # 主事件是「整段对话归档」，性质恒为生命周期（B4）
                    "io_kind": IO_KIND_LIFECYCLE,
                },
            ))

            # 子事件：每个 action 一个细粒度事件
            for idx, action in enumerate(actions):
                if not isinstance(action, str) or not action.strip():
                    continue
                layer = _classify_layer(action)
                op = _classify_op(action)
                events.append(MemoryEvent(
                    operation=op,  # 直接用归一后的操作名
                    layer=layer,
                    memory_key=_short_key(action, f"{message_id}-{idx}"),
                    memory_summary=action[:200],
                    token_count=_estimate_tokens(action),
                    session_id=session_id,
                    agent_id=agent_id,
                    timestamp=ts + idx * 0.01,  # 微调时间戳便于排序
                    metadata={
                        "source": "trae_importer",
                        "message_id": message_id,
                        "action_idx": str(idx),
                        # 完整 action 文本（前端抽屉显示用）
                        "turn_message_id": message_id,
                        "action_full": action,
                        # 事件性质归一（B4）：供 §4.4 分桶判「有没有工具读 / 是否只读未写」
                        "io_kind": _classify_io_kind(action),
                    },
                ))
    return events, session_id


def main() -> None:
    parser = argparse.ArgumentParser(description="把 TraeCode 对话历史导入 MemoryObservatory")
    parser.add_argument("--project", default=None,
                        help="项目名子串；默认当前项目；'all' 导入全部项目")
    parser.add_argument("--since", default=None,
                        help="只导入此日期之后的目录（YYYYMMDD，如 20260820）")
    parser.add_argument("--agent", default=None,
                        help="Agent ID（默认按项目 basename 自动生成；指定则所有项目用同一 agent）")
    parser.add_argument("--endpoint", default="http://localhost:4318/v1/traces",
                        help="OTLP 上报端点")
    parser.add_argument("--api-key", default=os.environ.get("MO_API_KEY", ""),
                        help="接口鉴权密钥，须与服务端 MO_API_KEY 一致；默认取环境变量 MO_API_KEY，"
                             "留空则适用于开放模式（未配密钥）的部署（见 ENV.md）")
    args = parser.parse_args()

    project_dirs = find_project_dirs(args.project)
    if not project_dirs:
        print(f"未找到匹配的项目目录（--project={args.project}）")
        print(f"可用项目：{[d.name for d in TRAECODE_MEMORY_ROOT.iterdir() if d.is_dir()]}")
        sys.exit(1)

    # 每个 Agent 用独立 observer，便于不同 agent_id 上报
    observers: dict[str, MemoryObserver] = {}

    def get_observer(aid: str) -> MemoryObserver:
        if aid not in observers:
            ob = MemoryObserver(agent_id=aid)
            ob.add_exporter(OTelSpanExporter(endpoint=args.endpoint, service_name=aid,
                                             api_key=args.api_key))
            observers[aid] = ob
        return observers[aid]

    total_events = 0
    total_files = 0
    seen_sessions: set[str] = set()
    for proj_dir in project_dirs:
        agent_id = args.agent or _project_agent_id(proj_dir.name)
        print(f"\n项目: {proj_dir.name}  →  agent_id: {agent_id}")
        for date_dir in sorted(proj_dir.iterdir()):
            if not date_dir.is_dir() or not date_dir.name.isdigit():
                continue
            if args.since and date_dir.name < args.since:
                continue
            for jsonl in sorted(date_dir.glob("session_memory_*.jsonl")):
                events, session_id = parse_session_jsonl(jsonl, agent_id)
                if not events:
                    continue
                ob = get_observer(agent_id)
                for ev in events:
                    ob.record_event(ev)
                total_events += len(events)
                total_files += 1
                seen_sessions.add(session_id)
                print(f"  {date_dir.name}/{jsonl.name}: {len(events)} 事件 (session={session_id})")

    if total_events == 0:
        print("未找到可导入的事件")
        sys.exit(1)

    # 触发所有 observer 上报
    results = {}
    for aid, ob in observers.items():
        results[aid] = ob.export()
    print(f"\n完成：导入 {total_files} 个文件，{total_events} 个事件，{len(seen_sessions)} 个会话，{len(observers)} 个 agent")
    print(f"上报结果: {results}")
    print(f"打开 Dashboard http://localhost:5173 ，Agent 下拉里选择对应 agent 查看数据")


if __name__ == "__main__":
    main()
