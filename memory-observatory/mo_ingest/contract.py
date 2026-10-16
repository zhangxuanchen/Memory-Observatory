"""接入层标准化契约（纯 stdlib，唯一真相）。

两种接入方案（Hook 实时 / 旁路日志批量）都必须把各自的原始数据归一成这里定义的
**同一套字段**，再由 :mod:`mo_ingest.otlp` 统一上报。本模块刻意不依赖 ``mo_sdk``：
Hook 端要能整目录拷到任意机器跑（见 examples/QODER-HOOK-SETUP.md）。

字段口径与项目既有契约逐字对齐（ARCHITECTURE.md §4.2）：

- 事件级：operation / layer / memory_key / memory_summary / token_count / latency_ms /
  agent_id / session_id / timestamp
- metadata（上报为 ``memory.meta.<k>``，服务端去掉 ``meta.`` 前缀落进 metadata jsonb）：
  ``source``、``io_kind``、主事件的 ``turn_message_id``/``turn_user``/``turn_outcome``/
  ``action_count``/``turn_actions``、子事件的 ``action_idx``/``action_full``
- trace/span：一个 session = 一个 trace（MD5 32 hex），span 每次新生成 16 hex

引号约定：本仓中文正文统一用「」。
"""
from __future__ import annotations

import hashlib
import re
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

# ---------------------------------------------------------------------------
# io_kind 封闭词表（8 值，与 mo-server 的
# agentloop/workbench/report/IoKind.java 逐字一致）
# ---------------------------------------------------------------------------
IO_KIND_VOCABULARY = (
    "file_read",
    "content_search",
    "memory_read",
    "file_write",
    "memory_write",
    "tool_other",
    "model_call",
    "lifecycle",
)
IO_KIND_LIFECYCLE = "lifecycle"

# ---------------------------------------------------------------------------
# operation 归一（记忆操作轴）
# ---------------------------------------------------------------------------
OPERATION_MAP = {
    "store": "WRITE",
    "add": "WRITE",
    "retrieve": "READ",
    "forget": "EXPIRE",
    "delete": "EXPIRE",
    "consolidate": "UPDATE",
    "compact": "UPDATE",
}
VALID_OPERATIONS = ("READ", "WRITE", "UPDATE", "EXPIRE")
DEFAULT_OPERATION = "WRITE"
DEFAULT_SOURCE_OPERATION = "store"  # 非记忆类事件的原始操作，上报前归一到 WRITE


def normalize_operation(raw: Optional[str]) -> str:
    """把原始操作名（store/retrieve/forget/consolidate/delete/compact…）或已归一值统一成 READ/WRITE/UPDATE/EXPIRE。

    归一表是跨语言契约（R7）：唯一真相源为仓库根 ``contract/vocabulary.json``
    的 ``operation_aliases`` / ``operation_default``，由 ``VocabularyJsonTest`` 机器校验。
    """
    op = (raw or "").strip()
    if not op:
        return DEFAULT_OPERATION
    up = op.upper()
    if up in VALID_OPERATIONS:
        return up
    return OPERATION_MAP.get(op.lower(), DEFAULT_OPERATION)


# ---------------------------------------------------------------------------
# io_kind 判定
#
# 两把尺子不要混：operation 答「这是哪种记忆操作」，io_kind 答「这一轮到底动了什么」。
#
# 判定分两层：
#   1. io_kind_of_tool() —— 拿到**真工具名**时直接查表（观测口径，最可信）；
#   2. io_kind_of_text() —— 只有自然语言摘要或原始 shell 文本时按关键词猜（**代理口径**）。
# 代理口径不可全信（同 B5），文档里必须如实标注。
# ---------------------------------------------------------------------------

# 工具名 → io_kind：合并各平台两套命名（原生 + Claude Code 兼容名）
TOOL_IO_KIND = {
    # 记忆类（Qoder 原生名独有）
    "search_memory": "memory_read",
    "read_memory": "memory_read",
    "retrieve_memory": "memory_read",
    "update_memory": "memory_write",
    "write_memory": "memory_write",
    "save_memory": "memory_write",
    "store_memory": "memory_write",
    # 文件读
    "read_file": "file_read",
    "Read": "file_read",
    "view_file": "file_read",
    # 文件写
    "create_file": "file_write",
    "Write": "file_write",
    "search_replace": "file_write",
    "Edit": "file_write",
    "edit_file": "file_write",
    "delete_file": "file_write",
    "MultiEdit": "file_write",
    "NotebookEdit": "file_write",
    # 内容检索 / 目录列举
    "grep_code": "content_search",
    "Grep": "content_search",
    "search_file": "content_search",
    "Glob": "content_search",
    "Search": "content_search",
    "list_dir": "content_search",
    "LS": "content_search",
}

# 工具名 → layer（对齐工作台中间件的层归一）
TOOL_LAYER = {
    "search_memory": "provider",
    "read_memory": "provider",
    "update_memory": "provider",
    "write_memory": "provider",
    "Skill": "skill",
}

# 关键词回退表（**代理口径**，顺序即优先级）。合并自 trae_importer 的 IO_KIND_KEYWORDS
# 与 workbuddy_importer 的 shell 落盘正则。
# 优先级：记忆类 > 文件写（含 shell 落盘）> 文件读 > 内容检索 > tool_other。
# 注意：匹配前文本会被 lower()，故所有模式一律小写（真工具名 Grep/Read 落成 grep/read 仍能命中）。
IO_KIND_KEYWORDS = [
    ("memory_write", r"记忆写入|写入记忆|记住|存记忆|save_memory|update_memory|write_memory|store_memory|memorize"),
    ("memory_read", r"记忆读取|读取记忆|回忆|检索记忆|search_memory|read_memory|remember|retrieve|recall"),
    # shell 重定向落盘：`> f` / `>> f`。三处否定是必须的——`=>`、`>=`、`->` 都含 `>`；
    # 写 /dev/null 是丢输出不是写盘。
    ("file_write", r"(?<![=\-<>])>>?(?![&=>])(?!\s*/dev/null\b)(?=\s*[^&\s])"),
    ("file_write", r"(?<![\w-])(?:rm|mv|cp|mkdir|touch|tee|truncate|chmod|ln)\b"),
    ("file_write", r"(?<![\w-])sed\b[^|;]*-i"),
    ("file_write", r"(?<![\w-])(?:write|edit|multiedit|notebookedit)\b"),
    ("file_write", r"修改|编辑|重写|新建|创建|写入文件|改文件|重构|实现|修复|apply|edit|write|refactor"),
    ("file_read", r"(?<![\w-])(?:cat|head|tail|less|more|wc|read)\b"),
    ("file_read", r"读文件|阅读|精读|查看文件|打开|read_file|view_file"),
    ("content_search", r"(?<![\w-])(?:grep|rg|ripgrep|find|glob|search)\b"),
    ("content_search", r"(?<![\w-])ls\b"),
    ("content_search", r"搜索|检索|查找|查询|grep|search|find|glob"),
    ("tool_other", r"运行|执行|命令|bash|shell|run|测试|构建|build|test|install"),
]

# io_kind → 记忆操作轴（**代理口径**：Hook 只暴露工具，不带记忆原语，只能按性质推）
IO_KIND_OPERATION = {
    "memory_read": "retrieve",
    "memory_write": "store",
}


def io_kind_of_tool(tool_name: Optional[str]) -> Optional[str]:
    """真工具名 → io_kind；认不出返回 None（交回关键词回退判）。"""
    return TOOL_IO_KIND.get((tool_name or "").strip())


def io_kind_of_text(text: Optional[str], default: Optional[str] = "tool_other") -> Optional[str]:
    """按关键词猜 io_kind（代理口径）。认不出返回 ``default``（默认 ``tool_other``，不猜）。

    ``default=None`` 时用于「先按关键词试，认不出再退回别的判据」的场景（WorkBuddy 会退回 messageKey）。
    """
    low = (text or "").lower()
    for kind, pat in IO_KIND_KEYWORDS:
        if re.search(pat, low):
            return kind
    return default


def operation_of_io_kind(io_kind: str) -> str:
    """io_kind → 原始 operation（代理口径）；非记忆类回退默认。"""
    return IO_KIND_OPERATION.get(io_kind, DEFAULT_SOURCE_OPERATION)


# ---------------------------------------------------------------------------
# trace / span 推导
# ---------------------------------------------------------------------------

def trace_id_for(session_id: Optional[str]) -> str:
    """一个 session = 一个 trace（MD5 32 hex，与 Java deterministicTraceId 对齐）。"""
    return hashlib.md5((session_id or "mo-unknown").encode("utf-8")).hexdigest()


def new_span_id() -> str:
    """新 span id：16 hex。"""
    return uuid.uuid4().hex[:16]


# ---------------------------------------------------------------------------
# 文本工具
# ---------------------------------------------------------------------------

def short(text: Optional[str], n: int) -> str:
    """压成一行并截断（换行替换为空格）。"""
    return (text or "").replace("\n", " ").strip()[:n]


def short_key(text: Optional[str], fallback: str, n: int = 32) -> str:
    """生成 memory_key：保留中文/数字/字母，前 n 字符；空则退 fallback。"""
    cleaned = re.sub(r"[^\w\u4e00-\u9fff]", "", text or "")
    return (cleaned or fallback)[:n]


def estimate_tokens(text: Optional[str], minimum: int = 10) -> int:
    """粗略估算 token（字符数 / 4，最少 minimum）。**属估算，不是读数**。"""
    return max(minimum, int(len(text or "") / 4))


# ---------------------------------------------------------------------------
# 标准化事件（两种方案产出的统一结构）
#
# 这不是 ``mo_sdk.MemoryEvent`` 的替代——那个挂在 SDK 上、可被业务代码 import；
# 这个只活在接入层内部，刻意零依赖，Hook 端整目录拷走即可跑。
# ---------------------------------------------------------------------------

@dataclass
class MemoryEvent:
    """接入层标准化事件。字段口径见模块 docstring。"""

    agent_id: str
    session_id: str
    operation: str = DEFAULT_SOURCE_OPERATION   # store/retrieve/forget/consolidate 或已归一的 READ/WRITE/UPDATE/EXPIRE
    layer: str = "session"
    memory_key: str = ""
    memory_summary: str = ""
    token_count: int = 0
    latency_ms: float = 0.0
    timestamp: float = field(default_factory=time.time)
    metadata: dict = field(default_factory=dict)
